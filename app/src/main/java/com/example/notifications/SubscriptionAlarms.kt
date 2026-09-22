package com.example.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.data.SubscriptionRepository
import com.example.db.Session
import com.example.domain.Rules
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object SubscriptionAlarms {
    private const val CHANNEL = "subscription_deadlines"
    private val lock = Mutex()
    private fun pending(context: Context): PendingIntent = PendingIntent.getBroadcast(context, 0,
        Intent(context, SubscriptionAlarmReceiver::class.java).setAction("RECONCILE")
            .setData(Uri.parse("subscriptions://deadline/reconcile")), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun exactAllowed(context: Context): Boolean = Build.VERSION.SDK_INT < 31 ||
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()

    fun notificationsAllowed(context: Context): Boolean {
        val runtime = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val channel = if (Build.VERSION.SDK_INT >= 26) (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).getNotificationChannel(CHANNEL) else null
        val channelEnabled = Build.VERSION.SDK_INT < 26 || channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
        return runtime && channelEnabled && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    // Both notification and exact-alarm permissions are checked immediately before use,
    // including a SecurityException fallback if access changes.
    @SuppressLint("MissingPermission")
    suspend fun refresh(context: Context) = lock.withLock {
        val app = context.applicationContext
        val repo = SubscriptionRepository(app)
        val now = System.currentTimeMillis()
        val sessions = repo.reconcile(now)
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "مواعيد المشتركين", NotificationManager.IMPORTANCE_HIGH))
        sessions.filter { it.home }.forEach { nm.cancel(it.id, 1); nm.cancel(it.id, 2) }
        if (notificationsAllowed(app)) sessions.filterNot { it.home }.forEach { s ->
            val ending = s.state == "ENDED" && !s.notified
            val warning = s.state == "ACTIVE" && !s.warned && Rules.remaining(s.clock(), now) <= 10 * Rules.MINUTE
            if (ending || warning) {
                try {
                    nm.notify(s.id, if (ending) 2 else 1, notification(app, s, ending))
                    repo.markNotified(s.id, ending)
                } catch (_: SecurityException) { /* Permission can change while delivering. */ }
            }
            if (s.state != "ACTIVE") nm.cancel(s.id, 1)
        }
        val deadline = sessions.filter { !it.home && it.state == "ACTIVE" }.flatMap { s ->
            val end = s.resumed + s.duration - s.served
            buildList {
                add(end)
                add(end - 10 * Rules.MINUTE)
                if (!s.home && s.recognized == 0L) add(Rules.recognitionAt(s.clock(), s.grace))
            }
        }.filter { it > now }.minOrNull()
        // Midnight refresh keeps day totals correct even when no timer is active.
        val midnight = if (StatusPanel.enabled(app) && StatusPanel.allowed(app)) StatusPanel.nextMidnight(now) else null
        val next = listOfNotNull(deadline, midnight).minOrNull()
        val manager = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        manager.cancel(pending(app))
        if (next != null) {
            try {
                if (next == deadline && exactAllowed(app)) manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending(app))
                else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending(app))
            } catch (_: SecurityException) { manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending(app)) }
        }
        // Panel failure must not prevent the deadline alarm from being scheduled.
        try { StatusPanel.refresh(app, now) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { app.getSharedPreferences("service_health", 0).edit().putString("error", "تعذّر تحديث لوحة المتابعة؛ افتح التطبيق للمحاولة.").apply() }
    }

    private fun notification(context: Context, s: Session, ending: Boolean): Notification {
        val intent = Intent(context, MainActivity::class.java).putExtra("SESSION_ID", s.id)
            .setData(Uri.Builder().scheme("subscriptions").authority("session").appendPath(s.id).build())
        val open = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(if (ending) "انتهى وقت ${s.client}" else "اقترب انتهاء ${s.client}")
            .setContentText("#${s.reference.ifBlank { s.id.take(8) }} · " + if (ending) "راجع اتصال المشترك يدويًا؛ التطبيق لا يفصل الإنترنت." else "تبقّت 10 دقائق أو أقل على ${s.plan}.")
            .setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH).build()
    }
}

class SubscriptionAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { withTimeout(8000) { SubscriptionAlarms.refresh(context.applicationContext) } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { context.getSharedPreferences("service_health", 0).edit().putString("error", "تعذّر تحديث التنبيه؛ افتح التطبيق لإعادة الجدولة.").apply() }
            finally { result.finish() }
        }
    }
}
