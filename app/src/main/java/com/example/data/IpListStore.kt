package com.example.data

import android.content.Context
import com.example.db.AppDatabase
import com.example.db.DeviceIdentity
import com.example.db.HomeIp
import com.example.db.WatchIp
import kotlinx.coroutines.flow.Flow

/**
 * Storage for the home and watch lists AND the schema-v8 identity records.
 *
 * Two layers, by design:
 * - IDENTITY (device_identities): the router's stable clientId is the primary
 *   identity, MAC the secondary. A DHCP IP change can never reclassify a device.
 * - LEGACY IP rows (home_ips / watch_ips): the owner's manual entries are KEPT
 *   untouched (never deleted by the app). When the device sitting at a listed IP
 *   is next seen with a stable clientId, DeviceIdentityEngine promotes it into an
 *   identity record; afterwards the IP is display/last-known only.
 */
class IpListStore(context: Context, private val db: AppDatabase = AppDatabase.getDatabase(context)) {
    private val dao get() = db.businessDao()

    companion object { /** UI-facing validity check for manual entry. */ fun validate(ip: String): Boolean = IpLists.valid(ip.trim()) }
    fun observeHome(): Flow<List<HomeIp>> = dao.observeHomeIps()
    fun observeWatch(): Flow<List<WatchIp>> = dao.observeWatchIps()

    /** Accepts only an address inside the router's subnet, so garbage never enters a list. */
    private fun checked(ip: String): String {
        require(IpLists.valid(ip)) { "أدخل عنوان IP صحيحًا مثل 192.168.1.55" }
        return ip.trim()
    }

    suspend fun addHome(ip: String, label: String = "") = dao.homeIp(HomeIp(checked(ip), label.trim().take(60), System.currentTimeMillis()))
    suspend fun removeHome(ip: String) = dao.deleteHomeIp(checked(ip))
    suspend fun addWatch(ip: String, label: String = "") = dao.watchIp(WatchIp(checked(ip), label.trim().take(60), System.currentTimeMillis()))
    suspend fun removeWatch(ip: String) = dao.deleteWatchIp(checked(ip))

    /** Saves/updates an identity record (owner re-classifying a device by clientId). */
    suspend fun setHomeIdentity(deviceId: Long, name: String, mac: String, ip: String) =
        dao.identity(DeviceIdentity(deviceId, "HOME", mac, name, ip, System.currentTimeMillis(), System.currentTimeMillis()))
    suspend fun setWatchIdentity(deviceId: Long, name: String, mac: String, ip: String) =
        dao.identity(DeviceIdentity(deviceId, "WATCH", mac, name, ip, System.currentTimeMillis(), System.currentTimeMillis()))
    fun observeIdentities(): Flow<List<DeviceIdentity>> = dao.observeIdentities()

    /** Removes an identity record (owner un-listing a device); legacy IP rows untouched. */
    suspend fun removeIdentity(deviceId: Long) = dao.deleteIdentity(deviceId)

    /**
     * The classification inputs at one instant: identity records plus the legacy
     * manual IP rows. One read → one consistent classification pass.
     */
    suspend fun identitySnapshot(): IdentityLists = IdentityLists(
        dao.identities(), dao.homeIps().map { it.ip }.toSet(), dao.watchIps().map { it.ip }.toSet())

    /**
     * Reconciles a SUCCESSFUL snapshot: promotes legacy IP matches to clientId
     * identity records and refreshes lastIp/name/MAC of known devices. A null
     * snapshot (discovery failure) reconciles nothing. Returns the resolved list
     * for the caller to track with. Never deletes any record, and NEVER changes
     * an existing record's list assignment — the owner's HOME/WATCH decision is
     * final; only the display fields (lastIp, name, blank MAC) refresh.
     */
    suspend fun reconcile(tracked: List<TrackedDevice>): List<TrackedDevice> {
        val lists = identitySnapshot()
        val resolved = DeviceIdentityEngine.reconcile(tracked, lists.identities, lists.legacyHome, lists.legacyWatch)
        val upserts = DeviceIdentityEngine.planUpserts(resolved, lists.identities, System.currentTimeMillis())
        val existingById = lists.identities.associateBy { it.deviceId }
        upserts.forEach { row ->
            val existing = existingById[row.deviceId]
            when {
                existing == null -> dao.insertIdentity(row) // promotion or first sight
                existing.lastIp != row.lastIp || existing.name != row.name || (existing.mac.isBlank() && row.mac.isNotBlank()) ->
                    dao.identity(existing.copy(lastIp = row.lastIp, name = row.name,
                        mac = existing.mac.ifBlank { row.mac }, updated = row.updated))
            }
        }
        return tracked.map { device ->
            val category = resolved.first { it.clientId == device.clientId }.category
            device.copy(category = category)
        }
    }

    /** Immutable lists at one instant; identity classification must use one consistent set. */
    data class Lists(val home: Set<String>, val watch: Set<String>) {
        fun classify(ip: String): IpLists.Category = IpLists.classify(ip, home, watch)
    }

    /** Kept for legacy callers/tests that need the raw manual IP rows. */
    suspend fun snapshot(): Lists {
        val home = dao.homeIps().map { it.ip }.toSet()
        val watch = dao.watchIps().map { it.ip }.toSet()
        return Lists(home, watch)
    }
}
