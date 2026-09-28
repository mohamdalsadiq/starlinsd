package com.example.notifications

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.data.DeviceAlerts
import com.example.data.DeviceAlertsClock
import com.example.data.DeviceTracker
import com.example.data.TrackedDevice
import org.json.JSONArray
import org.json.JSONObject

/**
 * Phase 3 device alerts: storage, channels, notifications, and schedule
 * integration. All timing decisions stay in the pure DeviceAlerts rules; this
 * object only persists state, builds notifications, and reports wakeup times to
 * SubscriptionAlarms.refresh() - the single scheduler (no parallel scheduler).
 *
 * Storage is SharedPreferences JSON (same pattern as the daily-close prefs), so
 * no Room migration is needed: this is one day of lightweight display history,
 * not queried business data.
 */
object DeviceAlertsCoordinator {
    private const val PREFS = "device_alerts"
    private const val CHANNEL_DEVICE_ALERTS = "device_alerts"
    private const val CHANNEL_DAILY_SUMMARY = "daily_device_summary"
    private const val SUMMARY_NOTIFICATION_ID = 4102
    private const val MAX_HISTORY = 64

    // ---- Settings (spec 36: same prefs architecture as the daily close time) ----

    /** Unknown-device alert delay in minutes; 0 means not yet set. Only 1/3/5 are offered (spec 7). */
    fun delayMinutes(context: Context): Int = context.getSharedPreferences(PREFS, 0).getInt("delay_minutes", 0)
    fun setDelayMinutes(context: Context, minutes: Int) {
        require(minutes in listOf(1, 3, 5)) { "المدة دقيقة أو 3 أو 5 دقائق" }
        context.getSharedPreferences(PREFS, 0).edit().putInt("delay_minutes", minutes).apply()
    }

    /** Daily summary minute-of-day; null when not yet set. */
    fun summaryMinute(context: Context): Int? = context.getSharedPreferences(PREFS, 0).getInt("summary_minute", -1).takeIf { it >= 0 }
    fun setSummaryMinute(context: Context, minute: Int?) {
        require(minute == null || minute in 0..1439) { "الوقت من 00:00 إلى 23:59، أو ألغِه" }
        context.getSharedPreferences(PREFS, 0).edit().putInt("summary_minute", minute ?: -1).apply()
    }

    fun knownDelayMinutes(context: Context): Int = delayMinutes(context).takeIf { it > 0 } ?: DEFAULT_DELAY_MINUTES
    fun knownSummaryMinute(context: Context): Int = summaryMinute(context) ?: DEFAULT_SUMMARY_MINUTE

    /** App-facing defaults (spec 7/15: 3 minutes, 22:00). */
    const val DEFAULT_DELAY_MINUTES = 3
    const val DEFAULT_SUMMARY_MINUTE = 22 * 60

    /** Idempotent: writes each default only until the first user change. */
    fun seedDefaults(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, 0)
        if (!prefs.getBoolean("seeded", false)) {
            prefs.edit()
                .putInt("delay_minutes", DEFAULT_DELAY_MINUTES)
                .putInt("summary_minute", DEFAULT_SUMMARY_MINUTE)
                .putBoolean("seeded", true)
                .apply()
        }
    }

    // ---- Persisted state (all device-local; nothing leaves the phone, spec 6) ----

    private const val KEY_PENDING = "pending"
    private const val KEY_NOTIFIED = "notified"
    private const val KEY_SUMMARY_SENT = "daily_summary_last_sent"

    internal fun pending(context: Context): List<DeviceAlerts.PendingAlert> {
        val raw = context.getSharedPreferences(PREFS, 0).getString(KEY_PENDING, null) ?: return emptyList()
        val array = JSONArray(raw)
        return (0 until array.length()).map { i ->
            val item = array.getJSONObject(i)
            DeviceAlerts.PendingAlert(item.getLong("clientId"), item.getLong("firstSeen"), item.getLong("deadline"),
                com.example.data.IpLists.Category.valueOf(item.getString("category")))
        }
    }

    internal fun savePending(context: Context, alerts: List<DeviceAlerts.PendingAlert>) {
        val array = JSONArray()
        alerts.forEach { alert ->
            array.put(JSONObject().put("clientId", alert.clientId).put("firstSeen", alert.firstSeen)
                .put("deadline", alert.deadline).put("category", alert.category.name))
        }
        context.getSharedPreferences(PREFS, 0).edit().putString(KEY_PENDING, array.toString()).apply()
    }

    internal fun notifiedToday(context: Context, now: Long): Set<Long> {
        val prefs = context.getSharedPreferences(PREFS, 0)
        val raw = prefs.getString(KEY_NOTIFIED, null) ?: return emptySet()
        val object_ = JSONObject(raw)
        val today = DeviceAlerts.dayKey(now)
        return object_.optJSONArray(today)?.let { array -> (0 until array.length()).map { array.getLong(it) }.toSet() } ?: emptySet()
    }

    internal fun markNotified(context: Context, now: Long, clientId: Long) {
        val prefs = context.getSharedPreferences(PREFS, 0)
        val raw = prefs.getString(KEY_NOTIFIED, null) ?: "{}"
        val object_ = JSONObject(raw)
        val today = DeviceAlerts.dayKey(now)
        val ids = object_.optJSONArray(today) ?: JSONArray()
        ids.put(clientId)
        object_.put(today, ids)
        // Midnight cleanup (spec 30): keep only today's entry; older days are dead keys.
        val cleaned = JSONObject().put(today, ids)
        prefs.edit().putString(KEY_NOTIFIED, cleaned.toString()).apply()
    }

    internal fun summarySentFor(context: Context, now: Long): String? {
        val prefs = context.getSharedPreferences(PREFS, 0)
        val sent = prefs.getString(KEY_SUMMARY_SENT, null) ?: return null
        val today = DeviceAlerts.dayKey(now)
        if (sent != today) { prefs.edit().remove(KEY_SUMMARY_SENT).apply() } // stale key cleanup
        return sent
    }

    private fun markSummarySent(context: Context, now: Long) {
        context.getSharedPreferences(PREFS, 0).edit().putString(KEY_SUMMARY_SENT, DeviceAlerts.dayKey(now)).apply()
    }

    // ---- Daily history (spec 17: lightweight per-day JSON, no Room migration) ----

    private fun historyKey(now: Long) = "history_${DeviceAlerts.dayKey(now)}"

    internal fun history(context: Context, now: Long): List<DeviceAlerts.DayDevice> {
        val raw = context.getSharedPreferences(PREFS, 0).getString(historyKey(now), null) ?: return emptyList()
        val array = JSONArray(raw)
        return (0 until array.length()).map { i ->
            val item = array.getJSONObject(i)
            DeviceAlerts.DayDevice(item.getLong("clientId"), item.optString("name"), item.getString("ip"), item.optString("mac"),
                com.example.data.IpLists.Category.valueOf(item.getString("category")), item.getLong("firstSeen"), item.getLong("lastSeen"))
        }
    }

    private fun saveHistory(context: Context, now: Long, devices: List<DeviceAlerts.DayDevice>) {
        val array = JSONArray()
        devices.take(MAX_HISTORY).forEach { device ->
            array.put(JSONObject().put("clientId", device.clientId).put("name", device.name).put("ip", device.ip)
                .put("mac", device.mac).put("category", device.category.name)
                .put("firstSeen", device.firstSeen).put("lastSeen", device.lastSeen))
        }
        val prefs = context.getSharedPreferences(PREFS, 0)
        val editor = prefs.edit()
        editor.putString(historyKey(now), array.toString())
        // Midnight cleanup (spec 30): only the newest few day keys are ever kept.
        val stale = prefs.all.keys.filter { it.startsWith("history_") && it != historyKey(now) }
        stale.forEach { editor.remove(it) }
        editor.apply()
    }

    // ---- Notification channels ----

    private fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(CHANNEL_DEVICE_ALERTS, "تنبيهات الأجهزة", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "تنبيه عند اتصال جهاز غير معروف أو في قائمة المراقبة"
        })
        manager.createNotificationChannel(NotificationChannel(CHANNEL_DAILY_SUMMARY, "تأكيد الأجهزة اليومية", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "ملخص يومي للأجهزة التي اتصلت بالشبكة"
        })
    }

    // ---- Notifications ----

    private fun alertTitle(category: com.example.data.IpLists.Category): String = when (category) {
        com.example.data.IpLists.Category.WATCH -> "جهاز في قائمة المراقبة"
        else -> "جهاز غير معروف متصل"
    }

    private fun alertText(category: com.example.data.IpLists.Category): String = when (category) {
        com.example.data.IpLists.Category.WATCH -> "تم اكتشاف جهاز موجود في قائمة المراقبة."
        else -> "تم اكتشاف جهاز غير موجود في قائمة أهل البيت أو الاشتراكات."
    }

    @SuppressLint("MissingPermission")
    internal fun notifyAlert(context: Context, alert: DeviceAlerts.PendingAlert, ip: String): Boolean = try {
        ensureChannels(context)
        val open = PendingIntent.getActivity(context, 2101, Intent(context, MainActivity::class.java)
            .setData(Uri.parse("slotra://devices")), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL_DEVICE_ALERTS)
            .setSmallIcon(android.R.drawable.ic_dialog_alert).setContentTitle(alertTitle(alert.category))
            .setContentText(alertText(alert.category)).setStyle(NotificationCompat.BigTextStyle().bigText(
                "${alertText(alert.category)} (IP ${truncate(ip)})"))
            .setContentIntent(open).setAutoCancel(true)
            .setPriority(if (alert.category == com.example.data.IpLists.Category.WATCH) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .build()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(2100 + (alert.clientId % 100).toInt(), notification) // stable per-clientId id (spec 42)
        true
    } catch (e: SecurityException) { false }

    private fun truncate(value: String): String = value.take(32)

    @SuppressLint("MissingPermission")
    internal fun notifySummary(context: Context, summary: DeviceAlerts.Summary, now: Long): Boolean = try {
        ensureChannels(context)
        val open = PendingIntent.getActivity(context, 2102, Intent(context, MainActivity::class.java)
            .putExtra("DEVICE_CONFIRMATION", "1"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(context, CHANNEL_DAILY_SUMMARY)
            .setSmallIcon(android.R.drawable.ic_menu_today).setContentTitle("تأكيد الأجهزة اليومية")
            .setContentIntent(open).setAutoCancel(true).setStyle(inbox(summary, now))
        when {
            summary.empty -> builder.setContentText("لم تُرصد أجهزة خارج أهل البيت اليوم.")
            summary.unconfirmed.isEmpty() -> builder.setContentText("كل الأجهزة المرصودة مرتبطة باشتراكات مسجلة.")
            else -> builder.setContentText("أجهزة بدون اشتراك مسجل: ${summary.unconfirmed.size}. راجع القائمة.")
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(SUMMARY_NOTIFICATION_ID, builder.build())
        true
    } catch (e: SecurityException) { false }

    private fun inbox(summary: DeviceAlerts.Summary, now: Long): NotificationCompat.InboxStyle {
        val style = NotificationCompat.InboxStyle()
        if (!summary.subscribed.isEmpty()) {
            style.addLine("أجهزة مرتبطة باشتراك:")
            summary.subscribed.take(4).forEach { style.addLine("· ${display(it, now)}") }
            if (summary.subscribed.size > 4) style.addLine("· ${summary.subscribed.size - 4} أخرى")
        }
        if (!summary.unconfirmed.isEmpty()) {
            style.addLine("أجهزة بدون اشتراك:")
            summary.unconfirmed.take(4).forEach { style.addLine("· ${display(it, now)}") }
            if (summary.unconfirmed.size > 4) style.addLine("· ${summary.unconfirmed.size - 4} أخرى")
        }
        return style
    }

    private fun display(device: DeviceAlerts.DayDevice, now: Long): String {
        val label = device.name.ifBlank { "جهاز ${device.clientId}" }
        return "$label (${device.ip})"
    }

    // ---- Integration points ----

    /**
     * History bookkeeping after a successful snapshot (spec 32: only successful
     * reads touch history). Called by MainViewModel right after tracker.poll().
     */
    fun recordSnapshot(context: Context, now: Long, tracked: List<TrackedDevice>, homeIps: Set<String>) {
        val updated = DeviceAlerts.mergeSnapshot(now, tracked, history(context, now), homeIps)
        saveHistory(context, now, updated)
    }

    /** Clears the day's history (used by tests to reset state). */
    internal fun resetForTest(context: Context) {
        context.getSharedPreferences(PREFS, 0).edit().clear().apply()
    }

    /** Read-only views for the confirmation placeholder screen. */
    fun historyFor(context: Context, now: Long): List<DeviceAlerts.DayDevice> = history(context, now)

    fun activeBindingsFor(context: Context): Map<Long, String> = try {
        kotlinx.coroutines.runBlocking {
            com.example.db.AppDatabase.getDatabase(context).businessDao().sessions()
                .filter { !it.home && it.state in listOf("ACTIVE", "PAUSED") && it.deviceClientId != null }
                .associate { it.deviceClientId!! to it.id }
        }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { emptyMap() }

    /**
     * One integration pass, called from SubscriptionAlarms.refresh() (spec 5/39/40).
     * [tracked] is the last successful tracking snapshot (null when none yet);
     * returns future wakeup times to merge into the single AlarmManager schedule.
     */
    fun onRefresh(
        context: Context,
        now: Long,
        tracked: List<TrackedDevice>?,
        activeBindings: Map<Long, String>,
        clock: DeviceAlertsClock = DeviceAlertsClock.SYSTEM,
    ): List<Long> {
        val delay = knownDelayMinutes(context)
        val currentPending = pending(context)
        val result = DeviceAlerts.evaluate(
            DeviceAlerts.EvalInput(now, delay, currentPending, tracked, activeBindings, notifiedToday(context, now)))
        savePending(context, result.pending)
        if (result.due.isNotEmpty() && SubscriptionAlarms.notificationsAllowed(context)) {
            val historyById = history(context, now).associateBy { it.clientId }
            result.due.forEach { alert ->
                if (notifyAlert(context, alert, historyById[alert.clientId]?.ip ?: ""))
                    markNotified(context, now, alert.clientId) // only on success (spec 42)
            }
        }
        val summaryMinute = knownSummaryMinute(context)
        val dueSummaryAt = DeviceAlerts.todayAt(now, summaryMinute)
        val summarySent = summarySentFor(context, now) == DeviceAlerts.dayKey(now)
        if (!summarySent && dueSummaryAt != null && now >= dueSummaryAt) {
            if (SubscriptionAlarms.notificationsAllowed(context)) {
                val summary = DeviceAlerts.summary(history(context, now), activeBindings)
                if (notifySummary(context, summary, now)) markSummarySent(context, now) // one per day (spec 43)
            }
        }
        // Wakeup list: persisted pending deadlines (already-frozen when discovery failed).
        val futureDeadlines = result.pending.map { it.deadline }.filter { it > now }
        return (futureDeadlines + listOfNotNull(DeviceAlerts.nextDaily(now, summaryMinute).takeIf { !summarySent })).distinct()
    }
}
