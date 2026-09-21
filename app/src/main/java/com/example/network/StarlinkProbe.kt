package com.example.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.net.Proxy
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class ProbeStep(val target: String, val outcome: String, val success: Boolean = false)
internal data class ProbeReport(val steps: List<ProbeStep>, val clients: List<StarlinkProtocol.Client>?, val summary: String, val at: Long = System.currentTimeMillis()) {
    // No client names, addresses, serial numbers, credentials or raw RPC bodies in shared diagnostics.
    fun diagnostic(): String = buildString {
        appendLine("Slotra ${com.example.BuildConfig.VERSION_NAME} · اختبار Starlink")
        appendLine("Android ${android.os.Build.VERSION.SDK_INT}")
        appendLine("وقت الاختبار: $at")
        appendLine(summary)
        steps.forEach { appendLine("${it.target}: ${it.outcome}") }
        append("عدد السجلات المستلمة: ${clients?.size ?: "غير متاح"}")
    }
}

internal class StarlinkProbe(context: Context) {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    suspend fun run(progress: (String) -> Unit): ProbeReport {
        val steps = mutableListOf<ProbeStep>()
        val wifi = connectivity.allNetworks.firstOrNull { network ->
            connectivity.getNetworkCapabilities(network)?.let {
                it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            } == true
        } ?: return ProbeReport(emptyList(), null, "اتصل أولًا بشبكة Wi-Fi الخاصة براوتر Starlink ثم أعد الاختبار.")
        val gateway = connectivity.getLinkProperties(wifi)?.routes?.firstOrNull { it.isDefaultRoute && it.gateway?.address?.size == 4 }?.gateway?.hostAddress
        steps += ProbeStep("Wi-Fi", "متصل · البوابة ${gateway ?: "غير معروفة"}", true)
        if (gateway != "192.168.1.1") steps += ProbeStep("تنبيه الشبكة", "بوابة مختلفة؛ لو تستخدم راوترًا إضافيًا أو شبكة ضيوف اتصل براوتر Starlink مباشرة.")
        connectivity.activeNetwork?.let { active ->
            if (connectivity.getNetworkCapabilities(active)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true)
                steps += ProbeStep("VPN", "يوجد VPN؛ قد يمنع الاتصال المحلي. الاختبار مربوط بشبكة Wi-Fi فقط.")
        }
        var clients: List<StarlinkProtocol.Client>? = null
        var routerSeen = false
        // Bounded probes to two known Starlink endpoints; never scan the subnet or mutate router state.
        for (web in listOf(false, true)) {
            for (query in listOf(StarlinkProtocol.Query.CLIENTS, StarlinkProtocol.Query.STATUS)) {
                currentCoroutineContext().ensureActive()
                if (clients != null) break
                val target = "192.168.1.1:9000 · ${if (web) "gRPC-Web" else "gRPC"} · ${query.name}"
                progress(if (query == StarlinkProtocol.Query.CLIENTS) "جاري طلب أجهزة الراوتر…" else "جاري فحص استجابة الراوتر…")
                try {
                    val reply = call(wifi, "192.168.1.1", 9000, web, query)
                    routerSeen = routerSeen || reply.kind in setOf("ROUTER", "CLIENTS")
                    if (reply.kind in setOf("ROUTER", "CLIENTS")) clients = reply.clients
                    steps += ProbeStep(target, when {
                        reply.kind == "DISH" -> "ردّ الطبق؛ هذا ليس إثباتًا لقائمة أجهزة الراوتر."
                        reply.clients != null -> "استلمنا ${reply.clients.size} سجلًا من الراوتر."
                        else -> "الراوتر ردّ، لكن لم يُرجع قائمة أجهزة قابلة للقراءة."
                    }, reply.kind != "DISH")
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { steps += ProbeStep(target, explain(e)) }
            }
        }
        currentCoroutineContext().ensureActive()
        progress("جاري فحص الوصول إلى طبق Starlink…")
        try {
            val reply = call(wifi, "192.168.100.1", 9200, false, StarlinkProtocol.Query.STATUS)
            val dish = reply.kind == "DISH"
            steps += ProbeStep("192.168.100.1:9200 · gRPC · STATUS", if (dish) "الطبق ردّ · ${reply.hardware} · ${reply.software}" else "ورد ردّ مختلف عن حالة الطبق.", dish)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { steps += ProbeStep("192.168.100.1:9200 · gRPC · STATUS", explain(e)) }
        return ProbeReport(steps, clients, when {
            clients != null -> "نجحت قراءة سجلات الأجهزة من الراوتر. قارنها بقائمة تطبيق Starlink."
            routerSeen -> "وصلنا للراوتر، لكن قراءة قائمة الأجهزة لم تنجح بهذا البروتوكول."
            else -> "لم نحصل على قائمة أجهزة. راجع تفاصيل الاختبار؛ عدم الاستجابة لا يعني عدم وجود أجهزة."
        })
    }

    private suspend fun call(network: Network, host: String, port: Int, web: Boolean, query: StarlinkProtocol.Query): StarlinkProtocol.Reply {
        require((host == "192.168.1.1" && port == 9000) || (host == "192.168.100.1" && port == 9200))
        val client = OkHttpClient.Builder()
            .socketFactory(network.socketFactory).proxy(Proxy.NO_PROXY)
            .protocols(listOf(if (web) Protocol.HTTP_1_1 else Protocol.H2_PRIOR_KNOWLEDGE))
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(3, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS).writeTimeout(3, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS).connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS)).build()
        val contentType = if (web) "application/grpc-web+proto" else "application/grpc+proto"
        val request = Request.Builder().url("http://$host:$port/SpaceX.API.Device.Device/Handle")
            .header("te", "trailers").header("grpc-timeout", "4S").header("grpc-accept-encoding", "identity")
            .header("Accept-Encoding", "identity")
            .apply { if (web) header("x-grpc-web", "1") }
            .post(StarlinkProtocol.frame(StarlinkProtocol.request(query)).toRequestBody(contentType.toMediaType())).build()
        try {
            return suspendCancellableCoroutine { continuation ->
                val call = client.newCall(request)
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
                    override fun onResponse(call: Call, response: Response) {
                        try {
                            val reply = response.use {
                                if (it.code != 200) throw HttpFailure(it.code)
                                require(it.header("Content-Type").orEmpty().startsWith(if (web) "application/grpc-web" else "application/grpc")) { "unexpected_content_type" }
                                val source = it.body?.source() ?: error("missing_body")
                                if (source.request((StarlinkProtocol.MAX_BYTES + 1).toLong())) error("response_too_large")
                                val body = source.readByteArray()
                                StarlinkProtocol.decode(StarlinkProtocol.unwrap(body, it.header("grpc-status"), it.trailers()["grpc-status"], web))
                            }
                            if (continuation.isActive) continuation.resume(reply)
                        } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
                    }
                })
            }
        } finally {
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }
    private class HttpFailure(val code: Int) : IOException()
    private fun explain(e: Exception): String = when (e) {
        is StarlinkProtocol.RpcFailure -> when (e.code) {
            7, 16 -> "رفض صلاحية القراءة أو طلب مصادقة (${e.layer}=${e.code})."
            12 -> "طلب القراءة غير مدعوم (${e.layer}=12)."
            4 -> "انتهت مهلة الاستجابة (${e.layer}=4)."
            else -> "ردّ البروتوكول بخطأ ${e.layer}=${e.code}."
        }
        is HttpFailure -> "ردّ HTTP ${e.code}؛ لم تثبت قراءة الأجهزة."
        is SocketTimeoutException -> "انتهت مهلة الاتصال؛ قد تكون الواجهة غير متاحة على هذه الشبكة."
        is ConnectException -> "تعذر فتح الاتصال؛ تحقق من الشبكة وإتاحة المنفذ."
        is SecurityException -> "النظام منع الوصول للشبكة؛ راجع أذونات التطبيق وVPN."
        is IOException -> "فشل الاتصال أو البروتوكول (${e.javaClass.simpleName})."
        else -> "ردّ غير متوافق أو غير مكتمل (${e.message?.takeIf { it.matches(Regex("[a-z_]{1,60}")) } ?: "invalid_response"})."
    }
}
