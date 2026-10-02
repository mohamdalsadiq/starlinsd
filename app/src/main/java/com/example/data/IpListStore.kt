package com.example.data

import android.content.Context
import com.example.db.AppDatabase
import com.example.db.DeviceIdentity
import com.example.db.HomeIp
import com.example.db.WatchIp
import kotlinx.coroutines.flow.Flow
import androidx.room.withTransaction

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

    /**
     * Explicit "Add to Home" — a PERSISTENT, IDEMPOTENT registry write. The
     * strongest currently available identity is stored: clientId primary, a real
     * MAC secondary, current IP as display-only metadata. If the same MAC already
     * lives under a churned clientId the existing row is re-keyed, never
     * duplicated, and the original `added` moment is preserved.
     */
    suspend fun addHomeDevice(device: TrackedDevice) = registerDevice("HOME", device)
    suspend fun addWatchDevice(device: TrackedDevice) = registerDevice("WATCH", device)

    private suspend fun registerDevice(list: String, device: TrackedDevice) {
        val now = System.currentTimeMillis()
        val name = device.name.trim().take(60)
        db.withTransaction {
            val mac = DeviceAlerts.usableMac(device.mac)
            val byMac = mac?.let { dao.identityByMac(it) }
            if (byMac != null) {
                // Same physical device, churned clientId: move the one row, don't fork it.
                dao.identity(DeviceIdentity(device.clientId, list, device.mac,
                    byMac.name.ifBlank { name }, device.ip, byMac.added, now))
                if (byMac.deviceId != device.clientId) dao.deleteIdentity(byMac.deviceId)
            } else {
                val previous = dao.identity(device.clientId)
                dao.identity(DeviceIdentity(device.clientId, list, device.mac.ifBlank { previous?.mac ?: "" },
                    name.ifBlank { previous?.name ?: "" }, device.ip, previous?.added ?: now, now))
            }
        }
    }

    /** Renames a known HOME/WATCH device; identity, list, and IP metadata are untouched. */
    suspend fun renameIdentity(deviceId: Long, name: String) {
        val trimmed = name.trim().take(60)
        require(trimmed.isNotBlank()) { "أدخل اسمًا للجهاز" }
        dao.identity(deviceId)?.let { dao.identity(it.copy(name = trimmed, updated = System.currentTimeMillis())) }
    }
    fun observeIdentities(): Flow<List<DeviceIdentity>> = dao.observeIdentities()

    /** Removes an identity record (owner un-listing a device); legacy IP rows untouched. */
    suspend fun removeIdentity(deviceId: Long) = dao.deleteIdentity(deviceId)

    /**
     * Atomically re-links a HOME identity to a new clientId (id churn after a
     * Wi-Fi password change): delete + insert in one transaction, so a failure
     * can never lose the HOME record.
     */
    suspend fun relinkHomeIdentity(oldDeviceId: Long, newDeviceId: Long, name: String, mac: String, ip: String) =
        db.withTransaction {
            dao.deleteIdentity(oldDeviceId)
            dao.identity(DeviceIdentity(newDeviceId, "HOME", mac, name, ip, System.currentTimeMillis(), System.currentTimeMillis()))
        }

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
        // MAC-matched, churned clientId: ONE physical device keeps ONE row. Delete the
        // stale row ONLY when its replacement is actually being written (an UNKNOWN
        // record's refresh is skipped by planUpserts, and deleting it would lose data).
        val upsertIds = upserts.map { it.deviceId }.toSet()
        val rekeyFrom = resolved.mapNotNull { r -> r.rekeyFrom?.takeIf { r.clientId in upsertIds } }.toSet()
        db.withTransaction {
            rekeyFrom.forEach { stale -> dao.deleteIdentity(stale) }
            upserts.forEach { row ->
                val existing = existingById[row.deviceId]
                when {
                    existing == null -> dao.insertIdentity(row) // promotion, re-key, or first sight
                    existing.lastIp != row.lastIp || existing.name != row.name || (existing.mac.isBlank() && row.mac.isNotBlank()) ->
                        dao.identity(existing.copy(lastIp = row.lastIp, name = row.name,
                            mac = existing.mac.ifBlank { row.mac }, updated = row.updated))
                }
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
