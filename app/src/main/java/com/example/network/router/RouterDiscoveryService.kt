package com.example.network.router

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.example.network.RouterControlProtocol
import com.example.network.StarlinkProtocol
import com.example.network.StarlinkProbe
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException

/** Bounded request/response transport to the router. Implementations must only send read requests. */
internal fun interface RouterTransport {
    suspend fun exchange(payload: ByteArray): ByteArray
}

/** Read-only evidence about whether the firmware exposes the per-client config carrier at all. */
internal data class ClientConfigEvidence(
    val present: Boolean?,
    val entries: Int,
    val withBlockSchedules: Int,
    val revision: Long?,
)

/** Outcome of one bounded, read-only router discovery attempt. Never a mutation. */
internal data class RouterDiscoveryReport(
    val reachable: Boolean,
    val routerHardware: String,
    val routerSoftware: String,
    val routerId: String,
    val clients: List<RouterClient>,
    val steps: List<RouterStep>,
    val errorCode: String,
    val configEvidence: ClientConfigEvidence? = null,
    val at: Long = System.currentTimeMillis(),
) {
    val duplicates: List<DuplicateGroup> get() = RouterClientIdentity.duplicateGroups(clients)

    /** Sanitized diagnostic: no client names, MACs, IPs, client IDs, serial numbers or credentials. */
    fun diagnostic(): String = buildString {
        appendLine("ROUTER CLIENT DISCOVERY (read-only)")
        appendLine("at=$at reachable=$reachable error=${errorCode.ifBlank { "none" }}")
        appendLine("router_hw=${routerHardware.ifBlank { "unknown" }} router_sw=${routerSoftware.ifBlank { "unknown" }}")
        appendLine("router_identity=${if (routerId.isBlank()) "absent" else "present"}")
        appendLine("clients=${clients.size}")
        appendLine("with_full_mac=${clients.count { it.mac != null }} with_masked_mac=${clients.count { it.macMasked }}")
        appendLine("with_ip=${clients.count { it.ip != null }} with_client_id=${clients.count { it.clientId != null }}")
        appendLine("with_device_id=${clients.count { it.deviceId.isNotBlank() }} with_role=${clients.count { it.role != null }}")
        appendLine("with_active=${clients.count { it.active != null }} with_blocked=${clients.count { it.blocked != null }}")
        appendLine("with_hw_ver=${clients.count { it.hardwareVersion.isNotBlank() }} with_sw_ver=${clients.count { it.softwareVersion.isNotBlank() }}")
        appendLine("duplicate_groups=${duplicates.size}")
        duplicates.forEach { appendLine("duplicate kind=${it.kind} count=${it.count}") }
        configEvidence?.let {
            appendLine("client_configs_present=${it.present ?: "unknown"} entries=${it.entries} with_block_schedules=${it.withBlockSchedules} revision=${it.revision ?: "absent"}")
        }
        steps.forEach { appendLine("${it.target}: ${it.outcome}") }
    }
}

internal data class RouterStep(val target: String, val outcome: String, val success: Boolean = false)

/** Which identity candidate a client currently has available, for the on-screen diagnostic. */
internal fun RouterClient.identityLabel(): String = when (RouterClientIdentity.stableIdentity(this)) {
    is StableIdentity.Mac -> "MAC (كامل)"
    is StableIdentity.DeviceId -> "DEVICE_ID"
    is StableIdentity.ClientId -> "CLIENT_ID"
    StableIdentity.None -> "بلا معرّف ثابت"
}

/**
 * Pure read-only discovery engine. Sends only the published read requests and decodes replies.
 * Kept free of Android types so the exact production logic runs under unit tests.
 */
internal object RouterDiscoveryEngine {
    const val ROUTER_HOST = "192.168.1.1"
    const val ROUTER_PORT = 9000

    suspend fun discover(transport: RouterTransport): RouterDiscoveryReport {
        val steps = mutableListOf<RouterStep>()
        var routerHardware = ""
        var routerSoftware = ""
        var routerId = ""
        try {
            val status = StarlinkProtocol.decode(transport.exchange(RouterClientCodec.statusRequest()))
            if (status.kind == "ROUTER") {
                routerHardware = status.hardware
                routerSoftware = status.software
                routerId = status.routerId
                steps += RouterStep("$ROUTER_HOST:$ROUTER_PORT · get_status", "ردّ الراوتر: ${status.hardware} · ${status.software}", true)
            } else {
                steps += RouterStep("$ROUTER_HOST:$ROUTER_PORT · get_status", "ردّ من نوع ${status.kind} بدل الراوتر.")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return RouterDiscoveryReport(false, "", "", "", emptyList(), steps + step("get_status", e), explainCode(e))
        }
        val evidence = capabilityEvidence(transport, steps)
        return try {
            val payload = transport.exchange(RouterClientCodec.clientsRequest())
            val clients = RouterClientCodec.decodeClients(payload)
                ?: return RouterDiscoveryReport(true, routerHardware, routerSoftware, routerId, emptyList(),
                    steps + RouterStep("$ROUTER_HOST:$ROUTER_PORT · wifi_get_clients", "ردّ الراوتر بدون قائمة أجهزة."), "clients_absent", evidence)
            steps += RouterStep("$ROUTER_HOST:$ROUTER_PORT · wifi_get_clients", "استلمنا ${clients.size} سجلًا.", true)
            RouterDiscoveryReport(true, routerHardware, routerSoftware, routerId, clients, steps, "", evidence)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RouterDiscoveryReport(true, routerHardware, routerSoftware, routerId, emptyList(), steps + step("wifi_get_clients", e), explainCode(e), evidence)
        }
    }

    /**
     * Read-only check of whether the firmware exposes the per-client config collection that is the
     * only identified carrier for a block schedule. Credentials and raw config are never retained:
     * the existing tested decoder keeps only the revision, raw client entries and router MAC.
     */
    private suspend fun capabilityEvidence(transport: RouterTransport, steps: MutableList<RouterStep>): ClientConfigEvidence? = try {
        val config = RouterControlProtocol.decodeConfig(transport.exchange(RouterControlProtocol.getConfigRequest()))
        val withSchedules = config.entries.count { RouterControlProtocol.hasSchedules(it) }
        steps += RouterStep("$ROUTER_HOST:$ROUTER_PORT · wifi_get_config (read-only)",
            "client_configs: ${config.entries.size} · بجداول حظر: $withSchedules", true)
        ClientConfigEvidence(true, config.entries.size, withSchedules, config.revision)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        steps += step("wifi_get_config (read-only)", e)
        ClientConfigEvidence(null, 0, 0, null)
    }

    private fun step(target: String, e: Exception) = RouterStep("$ROUTER_HOST:$ROUTER_PORT · $target", explain(e))

    /** Stable, non-sensitive error code reused by tests and the on-screen diagnostic. */
    fun explainCode(e: Exception): String = when (e) {
        is StarlinkProtocol.RpcFailure -> "${e.layer}=${e.code}"
        is IOException -> "io=${e.javaClass.simpleName.lowercase()}"
        else -> e.message?.takeIf { it.matches(Regex("[a-z_][a-z0-9_]{0,59}")) } ?: e.javaClass.simpleName.lowercase()
    }

    fun explain(e: Exception): String = when (e) {
        is StarlinkProtocol.RpcFailure -> when (e.code) {
            7, 16 -> "رفض صلاحية القراءة أو طلب مصادقة (${e.layer}=${e.code})."
            12 -> "طلب القراءة غير مدعوم على هذا الإصدار (${e.layer}=12)."
            4 -> "انتهت مهلة الاستجابة (${e.layer}=4)."
            else -> "ردّ البروتوكول بخطأ ${e.layer}=${e.code}."
        }
        is SocketTimeoutException -> "انتهت مهلة الاتصال بالراوتر."
        is ConnectException -> "تعذر فتح الاتصال بالراوتر؛ تحقق من الشبكة والمنفذ."
        is IOException -> "فشل الاتصال أو البروتوكول (${e.javaClass.simpleName})."
        else -> "ردّ غير متوافق أو غير مكتمل (${explainCode(e)})."
    }
}

/**
 * Android-facing wrapper for the read-only prototype. Binds to the current non-VPN Wi-Fi network
 * only, never polls, never schedules background work, and never sends a mutation.
 */
internal class RouterDiscoveryService(context: Context, private val probe: StarlinkProbe = StarlinkProbe(context)) {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    fun wifiNetwork(): Network? = connectivity.allNetworks.firstOrNull { network ->
        connectivity.getNetworkCapabilities(network)?.let {
            it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        } == true
    }

    suspend fun discover(): RouterDiscoveryReport {
        val network = wifiNetwork()
            ?: return RouterDiscoveryReport(false, "", "", "", emptyList(), emptyList(), "no_wifi")
        val transport = RouterTransport { payload ->
            probe.exchange(network, RouterDiscoveryEngine.ROUTER_HOST, RouterDiscoveryEngine.ROUTER_PORT, false, payload)
        }
        return RouterDiscoveryEngine.discover(transport)
    }
}
