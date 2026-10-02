package com.example.data

/**
 * Device identity reconciliation (task phase: device-identity-reconciliation-v1).
 *
 * The problem this engine fixes: DHCP rotates the router-assigned IP, so the old
 * IP-keyed HOME/WATCH lists reclassified a family device as UNKNOWN ("غير مصنف")
 * and the devices screen showed the same physical device twice with a different
 * IP. IP is a mutable NETWORK ADDRESS, not an identity.
 *
 * Identity order (task spec, المطلوب 2/3/10):
 *  1. PRIMARY   — the router's stable clientId (StarlinkProtocol.Client.id).
 *  2. SECONDARY — MAC, only when the stored record actually carries one.
 *  3. LEGACY FALLBACK — a one-shot IP match against the owner's manually saved
 *     home_ips/watch_ips rows. On a match the identity record is UPGRADED to the
 *     stronger clientId, so future recognitions never need the IP again.
 * Name is display-only (never an identity), and matching is never fuzzy.
 *
 * Pure rules only: Room and UI live in IpListStore / DevicesScreen. A failed
 * CLIENTS read (null snapshot) reconciles NOTHING — discovery failure must never
 * look like a disappearance or re-classification (المطلوب 5).
 */
object DeviceIdentityEngine {

    /** A reconciled view of one device: identity resolution decided, never guessed. */
    data class Resolved(
        val clientId: Long,
        val name: String,
        val ip: String,
        val mac: String,
        val category: IpLists.Category,
        /** True when this snapshot upgraded a legacy IP-only record to this clientId. */
        val promoted: Boolean,
        /**
         * The old identity row's clientId when this device was recognized by its
         * stored MAC under a CHURNED clientId. The storage layer re-keys that one
         * row instead of inserting a second — one physical device = one HOME/WATCH
         * entry, so a clientId change can never duplicate the registry.
         */
        val rekeyFrom: Long? = null,
    )

    /**
     * Reconciles one successful snapshot against the stored identity records plus
     * the owner's legacy IP lists. Promotions happen one-way (IP record → stronger
     * clientId identity) and never demote or delete existing records.
     *
     * @param identities currently stored identity-keyed records (schema v8).
     * @param legacyHome/legacyWatch the owner's manual IP rows (home_ips/watch_ips).
     */
    fun reconcile(
        snapshot: List<TrackedDevice>,
        identities: List<com.example.db.DeviceIdentity>,
        legacyHome: Set<String>,
        legacyWatch: Set<String>,
    ): List<Resolved> {
        val byId = identities.associateBy { it.deviceId }
        // MAC fallback uses ONLY real (unmasked) MACs: the firmware masks the
        // low three octets ("60:74:f4:XX:XX:XX"), so a raw MAC match would
        // classify every same-vendor device as HOME/WATCH (measured 2026-08-15).
        val macToId = identities.mapNotNull { identity ->
            DeviceAlerts.usableMac(identity.mac)?.let { it to identity }
        }.toMap()
        return snapshot.map { device ->
            val storedById = byId[device.clientId]
            // MAC fallback only when the clientId itself is unknown; a stored MAC
            // under a different clientId means the id churned (Wi-Fi password
            // change, reconnect) — re-key, never duplicate.
            val storedByMac = if (storedById == null) DeviceAlerts.usableMac(device.mac)?.let { macToId[it] } else null
            val stored = storedById ?: storedByMac
            val rekeyFrom = storedByMac?.deviceId?.takeIf { it != device.clientId }
            var promoted = false
            val category = when {
                stored != null && stored.list == "HOME" -> IpLists.Category.HOME
                stored != null && stored.list == "WATCH" -> IpLists.Category.WATCH
                // Legacy fallback: the IP the device currently holds is in the owner's
                // manual list. One-shot upgrade; the identity store takes over after.
                device.ip in legacyHome -> { promoted = true; IpLists.Category.HOME }
                device.ip in legacyWatch -> { promoted = true; IpLists.Category.WATCH }
                else -> IpLists.Category.UNKNOWN
            }
            Resolved(device.clientId, device.name, device.ip, device.mac, category, promoted, rekeyFrom)
        }
    }

    /**
     * The identity rows to persist after a successful reconciliation: new records
     * for unknown devices that showed up in an owner list (promotion), and
     * lastIp/name/MAC refreshes for known ones. Never returns a delete.
     */
    fun planUpserts(resolved: List<Resolved>, existing: List<com.example.db.DeviceIdentity>, now: Long): List<com.example.db.DeviceIdentity> {
        val byId = existing.associateBy { it.deviceId }
        return resolved.filter { it.category != IpLists.Category.UNKNOWN || byId[it.clientId] != null }
            .map { device ->
                // A re-keyed row inherits `added` from the old row it replaces, so
                // the registry keeps the original registration moment.
                val previous = byId[device.clientId]
                    ?: device.rekeyFrom?.let { old -> existing.firstOrNull { it.deviceId == old } }
                com.example.db.DeviceIdentity(
                    deviceId = device.clientId,
                    list = when (device.category) {
                        IpLists.Category.HOME -> "HOME"
                        IpLists.Category.WATCH -> "WATCH"
                        IpLists.Category.UNKNOWN -> previous?.list ?: "UNKNOWN"
                    },
                    mac = device.mac.ifBlank { previous?.mac ?: "" },
                    name = device.name.ifBlank { previous?.name ?: "" },
                    lastIp = device.ip,
                    added = previous?.added ?: now,
                    updated = now,
                )
            }
    }

    /**
     * Legacy promotion: converts one manual IP row into an identity record the
     * moment the device at that IP is seen with a stable clientId. Called by the
     * storage layer after [reconcile]; the legacy row itself is kept untouched.
     */
    fun promoteLegacy(deviceId: Long, list: String, device: TrackedDevice, now: Long): com.example.db.DeviceIdentity =
        com.example.db.DeviceIdentity(
            deviceId = deviceId, list = list, mac = device.mac, name = device.name,
            lastIp = device.ip, added = now, updated = now)
}
