package com.example.network

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.net.URI
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The account session is never sent to a LAN endpoint, diagnostics, or a third-party server. */
internal object CloudPolicy {
    const val LOGIN = "https://www.starlink.com/account"
    const val AUTH = "https://api.starlink.com/auth-rp/auth/user"
    const val HANDLE = "https://starlink.com/api/SpaceX.API.Device.Device/Handle"
    private val names = setOf("Starlink.Com.Sso", "Starlink.Com.Access.V1")
    fun loginUrlAllowed(value: String): Boolean = runCatching {
        val uri = URI(value)
        val host = uri.host?.lowercase().orEmpty()
        uri.scheme == "https" && uri.userInfo == null && uri.port in setOf(-1, 443) &&
            (host == "starlink.com" || host.endsWith(".starlink.com"))
    }.getOrDefault(false)
    fun cookies(vararg headers: String?): String {
        val result = linkedMapOf<String, String>()
        headers.filterNotNull().forEach { header ->
            require(header.length <= 32768 && !header.contains('\r') && !header.contains('\n')) { "cloud_invalid_cookie" }
            header.split(';').forEach {
                val name = it.substringBefore('=').trim()
                val value = it.substringAfter('=', "").trim()
                if (name in names && value.isNotEmpty()) {
                    require(value.all { c -> c.code in 33..126 && c != ';' }) { "cloud_invalid_cookie" }
                    result[name] = value
                }
            }
        }
        return result.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }
    fun hasLogin(cookie: String): Boolean = cookie.split(';').any { it.trim().startsWith("Starlink.Com.Sso=") }
    fun target(router: String, payload: ByteArray): ByteArray {
        require(router.matches(Regex("Router-[A-Za-z0-9-]{1,120}"))) { "cloud_invalid_router_id" }
        val fields = StarlinkProtocol.fields(payload)
        require(fields.size == 1 && fields.single().number in setOf(1004, 3002, 3009, 3001)) { "cloud_request_not_allowed" }
        return StarlinkProtocol.field(13, router.toByteArray()) + payload
    }
}

internal interface CloudSessionStore {
    fun read(): String?
    fun write(cookie: String)
    fun clear()
}
internal class CloudSessionVault(context: Context, private val keyProvider: (() -> SecretKey)? = null) : CloudSessionStore {
    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, "starlink-session-v1.enc"))
    fun exists(): Boolean = file.baseFile.exists()
    private fun key(): SecretKey {
        keyProvider?.let { return it() }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("slotra-starlink-session-v1", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("slotra-starlink-session-v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    override fun read(): String? {
        if (!exists()) return null
        try {
            require(file.baseFile.length() in 29..65536)
            val bytes = file.readFully()
            require(bytes[0] == 1.toByte())
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
            return CloudPolicy.cookies(cipher.doFinal(bytes.copyOfRange(13, bytes.size)).toString(Charsets.UTF_8))
        } catch (_: Exception) { error("cloud_session_storage") }
    }
    override fun write(cookie: String) {
        try {
            val safe = CloudPolicy.cookies(cookie)
            require(CloudPolicy.hasLogin(safe))
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
            require(cipher.iv.size == 12)
            val bytes = byteArrayOf(1) + cipher.iv + cipher.doFinal(safe.toByteArray())
            val out = file.startWrite()
            try { out.write(bytes); file.finishWrite(out) }
            catch (e: Exception) { file.failWrite(out); throw e }
        } catch (_: Exception) { error("cloud_session_storage") }
    }
    override fun clear() { file.delete(); check(!exists()) { "cloud_session_storage" } }
}

internal data class CloudHttpReply(val code: Int, val body: ByteArray, val contentType: String,
    val cookies: List<String> = emptyList(), val grpcStatus: String? = null)
internal fun interface CloudHttp {
    suspend fun request(url: String, cookie: String, body: ByteArray?): CloudHttpReply
}
internal class AccountHttp : CloudHttp {
    override suspend fun request(url: String, cookie: String, body: ByteArray?): CloudHttpReply {
        require(url == CloudPolicy.AUTH || url == CloudPolicy.HANDLE) { "cloud_endpoint_not_allowed" }
        val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS).build()
        val request = Request.Builder().url(url).header("Cookie", CloudPolicy.cookies(cookie))
            .header("Accept-Encoding", "identity").apply {
                if (body == null) header("Accept", "application/json")
                else header("x-grpc-web", "1").post(StarlinkProtocol.frame(body).toRequestBody("application/grpc-web+proto".toMediaType()))
            }.build()
        try {
            return suspendCancellableCoroutine { continuation ->
                val call = client.newCall(request)
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(IOException("cloud_network_failed"))
                    }
                    override fun onResponse(call: Call, response: Response) {
                        try {
                            val result = response.use {
                                val source = it.body?.source() ?: error("cloud_missing_body")
                                require(!source.request((StarlinkProtocol.MAX_BYTES + 1).toLong())) { "response_too_large" }
                                CloudHttpReply(it.code, source.readByteArray(), it.header("Content-Type").orEmpty(),
                                    it.headers("Set-Cookie"), it.header("grpc-status"))
                            }
                            if (continuation.isActive) continuation.resume(result)
                        } catch (_: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(IOException("cloud_response_invalid"))
                        }
                    }
                })
            }
        } finally {
            client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
        }
    }
}

internal class StarlinkCloud(private val store: CloudSessionStore, private val http: CloudHttp = AccountHttp()) {
    private var cookie: String? = null
    private suspend fun refresh(base: String): String {
        val reply = http.request(CloudPolicy.AUTH, base, null)
        check(reply.code == 200) { "cloud_http_${reply.code}" }
        require(reply.contentType.startsWith("application/json")) { "cloud_response_invalid" }
        val next = CloudPolicy.cookies(base, *reply.cookies.toTypedArray())
        require(CloudPolicy.hasLogin(next)) { "cloud_login_missing" }
        return next
    }
    suspend fun connect(candidate: String, local: RouterControlLink) = withContext(Dispatchers.IO) {
        val safe = CloudPolicy.cookies(candidate)
        check(CloudPolicy.hasLogin(safe)) { "cloud_login_missing" }
        cookie = refresh(safe)
        try {
            // The complete link verification is main-safe: auth, LAN verification and session persistence
            // may contain blocking platform/network/storage work and therefore stay on Dispatchers.IO.
            AuthenticatedRouterLink(local, this@StarlinkCloud)
                .exchange(StarlinkProtocol.request(StarlinkProtocol.Query.STATUS))
            store.write(cookie!!)
        } catch (e: Exception) {
            cookie = null
            throw e
        }
    }
    suspend fun exchange(router: String, payload: ByteArray): ByteArray {
        if (cookie == null) {
            val base = withContext(Dispatchers.IO) { store.read() } ?: error("cloud_session_missing")
            cookie = refresh(base)
        }
        val reply = http.request(CloudPolicy.HANDLE, cookie!!, CloudPolicy.target(router, payload))
        check(reply.code == 200) { "cloud_http_${reply.code}" }
        require(reply.contentType.startsWith("application/grpc-web")) { "cloud_response_invalid" }
        return StarlinkProtocol.unwrap(reply.body, reply.grpcStatus, null, true)
    }
}

internal class AuthenticatedRouterLink(private val local: RouterControlLink, private val remote: StarlinkCloud) : RouterControlLink {
    override val cloud = true
    override val localIps: Set<String> get() = local.localIps
    private var router: String? = null
    override suspend fun exchange(payload: ByteArray): ByteArray {
        val statusRequest = StarlinkProtocol.request(StarlinkProtocol.Query.STATUS)
        // Also rechecks that the phone remains on the original Wi-Fi before each operation.
        val status = StarlinkProtocol.decode(local.exchange(statusRequest))
        check(status.kind == "ROUTER" && status.routerId.startsWith("Router-")) { "cloud_invalid_router_id" }
        check(router == null || router == status.routerId) { "cloud_router_mismatch" }
        router = status.routerId
        val reply = remote.exchange(status.routerId, payload)
        if (payload.contentEquals(statusRequest)) {
            val actual = StarlinkProtocol.decode(reply)
            check(actual.kind == "ROUTER" && actual.routerId == status.routerId) { "cloud_router_mismatch" }
        }
        return reply
    }
}
