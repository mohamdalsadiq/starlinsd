package com.example.notifications

import android.Manifest
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

    fun notificationsAllowed(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    suspend fun refresh(context: Context) = lock.withLock {
        val app = context.applicationContext
        val repo = SubscriptionRepository(app)
        val now = System.currentTimeMillis()
        val sessions = repo.reconcile(now)
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "مواعيد المشتركين", NotificationManager.IMPORTANCE_HIGH))
        if (notificationsAllowed(app)) sessions.forEach { s ->
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
        val next = sessions.filter { it.state == "ACTIVE" }.flatMap { s ->
            val end = s.resumed + s.duration - s.served
            buildList {
                add(end)
                if (!s.warned) add(end - 10 * Rules.MINUTE)
                if (!s.home && s.recognized == 0L) add(Rules.recognitionAt(s.clock(), s.grace))
            }
        }.filter { it > now }.minOrNull()
        val manager = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        manager.cancel(pending(app))
        if (next != null) {
            try {
                if (exactAllowed(app)) manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending(app))
                else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending(app))
            } catch (_: SecurityException) { manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending(app)) }
        }
    }

    private fun notification(context: Context, s: Session, ending: Boolean): Notification {
        val intent = Intent(context, MainActivity::class.java).putExtra("SESSION_ID", s.id)
            .setData(Uri.Builder().scheme("subscriptions").authority("session").appendPath(s.id).build())
        val open = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(if (ending) "انتهى وقت ${s.client}" else "اقترب انتهاء ${s.client}")
            .setContentText(if (ending) "راجع اتصال المشترك يدويًا؛ التطبيق لا يفصل الإنترنت." else "تبقّت 10 دقائق أو أقل على ${s.plan}.")
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
