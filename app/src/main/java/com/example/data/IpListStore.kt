package com.example.data

import android.content.Context
import com.example.db.AppDatabase
import com.example.db.HomeIp
import com.example.db.WatchIp
import kotlinx.coroutines.flow.Flow

/**
 * Storage for the home and watch IP lists.
 *
 * Two different identity systems coexist by design:
 * - clientId (StarlinkProtocol.Client.id) is the only identity binding a subscription to a device.
 * - IP is the management key for these lists, per the owner's request for simple management.
 * An IP classification then decides whether the client currently at that IP is tracked at all.
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

    suspend fun snapshot(): Lists {
        val home = dao.homeIps().map { it.ip }.toSet()
        val watch = dao.watchIps().map { it.ip }.toSet()
        return Lists(home, watch)
    }

    /** Immutable lists at one instant; classification must use one consistent pair. */
    data class Lists(val home: Set<String>, val watch: Set<String>) {
        fun classify(ip: String): IpLists.Category = IpLists.classify(ip, home, watch)
    }
}
