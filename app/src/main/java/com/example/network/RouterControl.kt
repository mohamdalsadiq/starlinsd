package com.example.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.AtomicFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal interface RouterControlLink {
    val cloud: Boolean get() = false
    val localIps: Set<String>
    suspend fun exchange(payload: ByteArray): ByteArray
}
internal data class PendingPause(val router: String, val device: StarlinkProtocol.Client, val marker: String, val cloud: Boolean = false)
internal interface PauseJournal {
    fun read(): PendingPause?
    fun write(pending: PendingPause)
    fun clear()
}
internal class FilePauseJournal(context: Context) : PauseJournal {
    private val file = AtomicFile(File(context.noBackupFilesDir, "starlink-pause-v1.json"))
    override fun read(): PendingPause? {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        require(file.baseFile.length() <= 16384) { "invalid_recovery_file" }
        val json = JSONObject(file.readFully().toString(Charsets.UTF_8))
        val id = json.getLong("id")
        val marker = json.getString("marker")
        val cloud = json.optBoolean("cloud", false)
        require(id in 1..0xffffffffL && (if (cloud) marker == "_permanent" else marker.matches(Regex("slotra-[a-f0-9-]{36}")))) { "invalid_recovery_file" }
        return PendingPause(json.getString("router"), StarlinkProtocol.Client(json.getString("name"),
            json.getString("ip"), json.getString("mac"), null, id, role = 1), marker, cloud)
    }
    override fun write(pending: PendingPause) {
        val json = JSONObject().put("router", pending.router).put("id", pending.device.id)
            .put("cloud", pending.cloud).put("name", pending.device.name).put("ip", pending.device.ip).put("mac", pending.device.mac).put("marker", pending.marker)
        val output = file.startWrite()
        try { output.write(json.toString().toByteArray()); file.finishWrite(output) }
        catch (e: Exception) { file.failWrite(output); throw e }
    }
    override fun clear() { file.delete(); check(!file.baseFile.exists()) { "recovery_clear_failed" } }
}

internal class AndroidRouterLink(context: Context) : RouterControlLink {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val network = connectivity.allNetworks.firstOrNull {
        val capabilities = connectivity.getNetworkCapabilities(it)
        capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    } ?: error("no_wifi")
    private val probe = StarlinkProbe(context.applicationContext)
    override val localIps: Set<String> = connectivity.getLinkProperties(network)?.linkAddresses.orEmpty()
        .mapNotNull { it.address.hostAddress }.toSet()
    init { check(localIps.isNotEmpty()) { "unknown_local_ip" }; checkNetwork() }
    private fun checkNetwork() {
        val caps = connectivity.getNetworkCapabilities(network)
        check(caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) { "wifi_changed" }
        val properties = connectivity.getLinkProperties(network) ?: error("wifi_changed")
        check(properties.routes.any { it.isDefaultRoute && it.gateway?.hostAddress == "192.168.1.1" }) { "wrong_gateway" }
        check(properties.linkAddresses.mapNotNull { it.address.hostAddress }.toSet() == localIps) { "wifi_changed" }
    }
    override suspend fun exchange(payload: ByteArray): ByteArray {
        checkNetwork()
        return probe.exchange(network, "192.168.1.1", 9000, false, payload)
    }
}

internal class PausePreview internal constructor(
    val pending: PendingPause, val pause: Boolean, internal val link: RouterControlLink,
    internal val original: ByteArray?, internal val revision: Long, internal val createdAt: Long,
    internal val collection: List<ByteArray>? = null,
) { internal var used = false }
internal data class PauseResult(val message: String, val diagnostic: String, val verified: Boolean = false)

/** One explicit device mutation per confirmation. Never retries a mutation or falls back to bulk config. */
internal class RouterControl(
    private val openLink: () -> RouterControlLink,
    private val journal: PauseJournal,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val settle: suspend () -> Unit = { delay(700) },
) {
    constructor(context: Context, cloud: Boolean = false) : this({
        val saved = FilePauseJournal(context.applicationContext).read()
        if (saved?.cloud ?: cloud) AuthenticatedRouterLink(AndroidRouterLink(context), StarlinkCloud(CloudSessionVault(context)))
        else AndroidRouterLink(context)
    }, FilePauseJournal(context.applicationContext))
    companion object { private val operation = Mutex() }
    fun pending(): PendingPause? = journal.read()
    private data class State(val router: String, val config: RouterControlProtocol.Config, val clients: List<StarlinkProtocol.Client>)
    private suspend fun read(link: RouterControlLink): State {
        val status = StarlinkProtocol.decode(link.exchange(StarlinkProtocol.request(StarlinkProtocol.Query.STATUS)))
        check(status.kind == "ROUTER") { "not_router" }
        val config = RouterControlProtocol.decodeConfig(link.exchange(RouterControlProtocol.getConfigRequest()))
        val router = status.routerId.ifBlank { config.routerMac.takeIf(RouterControlProtocol::fullMac).orEmpty() }
        check(router.isNotBlank()) { "unknown_router_identity" }
        val clients = StarlinkProtocol.decode(link.exchange(StarlinkProtocol.request(StarlinkProtocol.Query.CLIENTS)))
        check(clients.kind == "CLIENTS" && clients.clients != null) { "missing_clients" }
        return State(router, config, clients.clients)
    }
    private fun target(state: State, expected: StarlinkProtocol.Client, required: Boolean): StarlinkProtocol.Client {
        check(expected.id != null) { "missing_client_id" }
        val matches = state.clients.filter { it.id == expected.id }
        check(matches.size <= 1) { "ambiguous_client_id" }
        val current = matches.singleOrNull()
        check(!required || current != null) { "client_not_present" }
        if (current != null) {
            check(current.mac.equals(expected.mac, true) && current.name == expected.name) { "client_identity_changed" }
            check(current.role == null || current.role == 0L || current.role == 1L) { "infrastructure_device" }
        }
        return current ?: expected
    }
    private fun checkTarget(device: StarlinkProtocol.Client, link: RouterControlLink) {
        check(device.id != null && device.id in 1..0xffffffffL) { "missing_client_id" }
        check(device.role == null || device.role == 0L || device.role == 1L) { "infrastructure_device" }
        check(device.ip.matches(Regex("192\\.168\\.1\\.[0-9]{1,3}")) &&
            device.ip.substringAfterLast('.').toInt() in 2..254) { "invalid_client_ip" }
        check(device.ip !in link.localIps) { "management_phone" }
    }
    suspend fun prepare(device: StarlinkProtocol.Client): PausePreview = operation.withLock {
        check(journal.read() == null) { "pending_test_exists" }
        val link = openLink()
        checkTarget(device, link)
        val state = read(link)
        val current = target(state, device, true)
        checkTarget(current, link)
        check(current.ip == device.ip) { "client_identity_changed" }
        val entry = RouterControlProtocol.entry(state.config, current)
        check(current.blocked != true && !RouterControlProtocol.hasSchedules(entry)) { "existing_block_schedule" }
        if (link.cloud) RouterControlProtocol.cloudEntries(state.config, current, entry ?: StarlinkProtocol.numberField(1, current.id!!))
        PausePreview(PendingPause(state.router, current, if (link.cloud) "_permanent" else "slotra-${UUID.randomUUID()}", link.cloud),
            true, link, entry, state.config.revision, clock(), if (link.cloud) state.config.entries else null)
    }
    suspend fun prepareRestore(): PausePreview = operation.withLock {
        val pending = journal.read() ?: error("no_pending_test")
        val link = openLink()
        check(link.cloud == pending.cloud) { "recovery_transport_changed" }
        val state = read(link)
        check(state.router == pending.router) { "different_router" }
        val current = target(state, pending.device, false)
        val entry = RouterControlProtocol.entry(state.config, current)
        // A missing marker needs no write: applying a stale backup could overwrite user changes.
        PausePreview(pending, false, link, entry, state.config.revision, clock(), if (link.cloud) state.config.entries else null)
    }
    suspend fun apply(preview: PausePreview): PauseResult = operation.withLock {
        check(!preview.used && clock() - preview.createdAt in 0..60000) { "expired_confirmation" }
        preview.used = true
        val p = preview.pending
        var sent = false
        try {
            val state = read(preview.link)
            check(state.router == p.router) { "different_router" }
            check(preview.link.cloud == p.cloud) { "recovery_transport_changed" }
            val device = target(state, p.device, preview.pause)
            if (preview.pause) {
                checkTarget(device, preview.link)
                check(device.ip == p.device.ip) { "client_identity_changed" }
                check(journal.read() == null) { "pending_test_exists" }
            } else check(journal.read()?.marker == p.marker) { "recovery_changed" }
            val old = RouterControlProtocol.entry(state.config, device)
            check(state.config.revision == preview.revision && RouterControlProtocol.sameBytes(old, preview.original)) { "config_changed" }
            if (p.cloud) check(preview.collection != null && RouterControlProtocol.sameEntries(state.config.entries, preview.collection)) { "config_changed" }
            if (!preview.pause && !RouterControlProtocol.hasMarker(old, p.marker)) {
                withContext(Dispatchers.IO) { journal.clear() }
                return@withLock PauseResult("لا يوجد جدول حظر تابع لهذا الاختبار. لو الإنترنت ما زال موقوفًا، راجع تطبيق Starlink.", "RESTORE: owned_schedule_absent; no_write")
            }
            if (preview.pause) {
                check(device.blocked != true && !RouterControlProtocol.hasSchedules(old)) { "existing_block_schedule" }
                // Durable before dispatch, so process death or a lost reply still exposes recovery.
                withContext(Dispatchers.IO) { journal.write(p) }
            }
            if (p.cloud && !preview.pause) check(old != null && RouterControlProtocol.permanentIsFullWeek(old)) { "recovery_schedule_changed" }
            val updated = RouterControlProtocol.updatedEntry(old, device, p.marker, preview.pause)
            val expectedEntries = if (p.cloud) RouterControlProtocol.cloudEntries(state.config, device, updated) else null
            val request = if (expectedEntries != null) RouterControlProtocol.setCloudClientsRequest(expectedEntries) else RouterControlProtocol.setClientRequest(updated)
            sent = true
            val response = preview.link.exchange(request)
            StarlinkProtocol.checkStatus(response)
            // RPC acceptance alone never proves a successful block. Only bounded reads are retried.
            var configConfirmed = false
            var live: StarlinkProtocol.Client? = null
            repeat(3) {
                if (!configConfirmed || live?.blocked != preview.pause) {
                    settle()
                    val after = read(preview.link)
                    check(after.router == p.router) { "different_router" }
                    val entry = RouterControlProtocol.entry(after.config, p.device)
                    val markerMatches = RouterControlProtocol.hasMarker(entry, p.marker) == preview.pause
                    val expectedOther = RouterControlProtocol.withoutMarker(updated, p.marker)
                    configConfirmed = entry != null && markerMatches &&
                        RouterControlProtocol.withoutMarker(entry, p.marker).contentEquals(expectedOther)
                    if (p.cloud) {
                        val otherBefore = state.config.entries.filterNot { it.contentEquals(old) }
                        val otherAfter = after.config.entries.filterNot { it.contentEquals(entry) }
                        configConfirmed = configConfirmed && RouterControlProtocol.sameEntries(otherBefore, otherAfter)
                    }
                    live = after.clients.singleOrNull { c -> c.id == p.device.id && c.mac.equals(p.device.mac, true) }
                }
            }
            if (configConfirmed && !preview.pause) withContext(Dispatchers.IO) { journal.clear() }
            val verified = configConfirmed && live?.blocked == preview.pause
            val message = when {
                verified && preview.pause -> "الراوتر أكد إيقاف الإنترنت. جرّب التصفح من الجهاز المستهدف بعد إغلاق بيانات الشريحة، ثم أعد الإنترنت."
                verified -> "الراوتر أكد إزالة الإيقاف. تحقق من عودة الإنترنت على الجهاز."
                configConfirmed && preview.pause -> "حُفظ جدول الإيقاف، لكن حالة الحظر لم تُؤكد من الرد. اختبر الإنترنت فعليًا ثم استخدم إعادة الإنترنت."
                configConfirmed -> "أُزيل جدول Slotra، لكن الرد لم يؤكد حالة الإنترنت. تحقق من الجهاز أو تطبيق Starlink."
                else -> "لم نتأكد من تطبيق الأمر. قد يكون نُفذ رغم غياب التأكيد؛ استخدم إعادة الإنترنت أو تطبيق Starlink."
            }
            PauseResult(message, "${if (preview.pause) "PAUSE" else "RESTORE"}: route=${if (p.cloud) "cloud" else "lan"}; config_confirmed=$configConfirmed; blocked_field=${live?.blocked ?: "absent"}; verified=$verified", verified)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            PauseResult("${controlError(e)}${if (sent) " قد يكون الأمر وصل؛ سجل الاسترجاع محفوظ. راجع الجهاز ثم استخدم إعادة الإنترنت." else " لم يُرسل أمر تغيير."}",
                "${if (preview.pause) "PAUSE" else "RESTORE"}: route=${if (p.cloud) "cloud" else "lan"}; dispatched=$sent; ${errorCode(e)}")
        }
    }
}

internal fun errorCode(e: Exception): String = when (e) {
    is StarlinkProtocol.RpcFailure -> "${e.layer}=${e.code}"
    else -> e.message?.takeIf { it.matches(Regex("[a-z_][a-z0-9_]{0,59}")) } ?: e.javaClass.simpleName
}
internal fun controlError(e: Exception): String = when (errorCode(e)) {
    "gRPC=7", "Starlink=7", "cloud_http_403" -> "الراوتر رفض صلاحية التحكم لهذا الطلب."
    "gRPC=16", "Starlink=16", "cloud_http_401", "cloud_session_missing", "cloud_session_expired" -> "جلسة Starlink غير صالحة أو انتهت. أعد ربط الحساب؛ لن نكرر أمر الحظر تلقائيًا."
    "cloud_http_429" -> "Starlink طلب تقليل المحاولات. انتظر قليلًا ثم أعد الفحص."
    "cloud_router_mismatch", "cloud_invalid_router_id" -> "تعذر مطابقة راوتر الشبكة مع الراوتر المتاح عبر الحساب؛ لن نرسل تغييرًا."
    "cloud_login_missing" -> "أكمل تسجيل الدخول في صفحة Starlink أولًا، ثم اضغط التحقق من الربط."
    "recovery_schedule_changed" -> "تغير جدول الإيقاف بعد التجربة. استخدم تطبيق Starlink لإلغائه حتى لا نمسح تغييرًا آخر."
    "cloud_session_storage" -> "تعذر فتح جلسة Starlink المحفوظة. افصل الربط ثم سجّل الدخول من جديد."
    "gRPC=12", "Starlink=12" -> "الراوتر لا يدعم هذا الطلب."
    "management_phone" -> "لا يمكن إيقاف الإنترنت عن هاتف الإدارة."
    "infrastructure_device", "invalid_client_ip" -> "هذا الجهاز غير مناسب للاختبار؛ اختر هاتفًا آخر متصلًا مباشرة."
    "missing_client_id", "ambiguous_client_id", "client_identity_conflict" -> "هوية الجهاز غير كافية أو متعارضة؛ لن نرسل أمرًا لجهاز غير مؤكد."
    "client_identity_changed", "client_not_present", "config_changed", "expired_confirmation" -> "تغيرت البيانات أو انتهت صلاحية التأكيد؛ حدّث القراءة وافحص الجهاز مجددًا."
    "existing_block_schedule" -> "للجهاز إيقاف أو جدول سابق؛ ألغِه من تطبيق Starlink قبل تجربة Slotra."
    "pending_test_exists" -> "أكمل إعادة الإنترنت للاختبار السابق أولًا."
    "different_router", "unknown_router_identity", "wrong_gateway", "wifi_changed", "no_wifi", "unknown_local_ip" -> "تعذر تأكيد الاتصال بنفس راوتر Starlink. اتصل بشبكته الرئيسية وأعد المحاولة."
    else -> "تعذر إكمال فحص التحكم (${errorCode(e)})."
}
