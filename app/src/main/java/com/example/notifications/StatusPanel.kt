package com.example.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import com.example.MainActivity
import com.example.R
import com.example.db.*
import com.example.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.util.*

/** Reuses the dashboard ledger; no separate financial totals are stored. */
data class PanelSnapshot(val active: Int, val soon: Int, val ended: Int, val paused: Int,
    val income: Long, val remainingBill: Long?, val available: Long?, val cycleProfit: Long?, val coveredPercent: Int?,
    val dailyTarget: Long?, val dailyShortfall: Long?) {
    companion object {
        fun calculate(sessions: List<Session>, sales: List<ManualSale>, corrections: List<RevenueCorrection>,
            config: BusinessSettings, cycles: List<BillingCycle>, debts: List<Debt>, payments: List<DebtPayment>, now: Long): PanelSnapshot {
            return from(FinancialReportCache().get(FinancialData(sessions, sales, corrections, config, cycles, debts, payments), now), now)
        }
        fun from(snapshot: FinancialSnapshot, now: Long): PanelSnapshot {
            val paid = snapshot.data.sessions.filterNot { it.home }
            val active = paid.filter { it.state == "ACTIVE" && Rules.remaining(it.clock(), now) > 0 }
            val cycle = snapshot.revenue
            return PanelSnapshot(active.size, active.count { Rules.remaining(it.clock(), now) <= 10 * Rules.MINUTE },
                paid.count { it.state == "ENDED" || it.state == "ACTIVE" && Rules.remaining(it.clock(), now) == 0L },
                paid.count { it.state == "PAUSED" }, snapshot.today.revenue, cycle.remainingCost,
                snapshot.budget.day(now).available, cycle.cycleProfit,
                cycle.cost?.let { if (it == 0L) 100 else ((cycle.covered.toDouble() / it) * 100).toInt().coerceIn(0, 100) },
                snapshot.budget.day(now).billTarget, snapshot.budget.day(now).let { if (it.billTarget == null) null else it.shortfall })
        }
    }
}

object StatusPanel {
    const val CHANNEL = "slotra_status_panel"
    const val ID = 3201
    private const val PREFS = "status_panel"
    private val reportCache = FinancialReportCache()
    fun enabled(context: Context) = context.getSharedPreferences(PREFS, 0).getBoolean("enabled", true)
    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, 0).edit().putBoolean("enabled", enabled).apply()
        if (!enabled) (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(ID)
    }
    fun allowed(context: Context): Boolean {
        val runtime = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val channel = if (Build.VERSION.SDK_INT >= 26) (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).getNotificationChannel(CHANNEL) else null
        return runtime && NotificationManagerCompat.from(context).areNotificationsEnabled() && (Build.VERSION.SDK_INT < 26 || channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE)
    }
    fun nextMidnight(now: Long): Long = Calendar.getInstance().apply { timeInMillis = Revenue.day(now); add(Calendar.DAY_OF_MONTH, 1) }.timeInMillis

    @SuppressLint("MissingPermission")
    suspend fun refresh(context: Context, now: Long) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!enabled(context)) { manager.cancel(ID); return }
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(CHANNEL, "لوحة متابعة Slotra", NotificationManager.IMPORTANCE_LOW).apply {
            description = "لوحة صامتة للمشتركين والإيراد؛ منفصلة عن تنبيهات انتهاء الاشتراك"
            setShowBadge(false); enableVibration(false); setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        })
        if (!allowed(context)) return
        val db = AppDatabase.getDatabase(context)
        val data = db.withTransaction {
            val dao = db.businessDao()
            FinancialData(dao.sessions(), dao.manualSales(), dao.corrections(), dao.settings() ?: BusinessSettings(), dao.cycles(), dao.debts(), dao.debtPayments())
        }
        // Release the Room transaction before aggregating the financial history.
        val snapshot = withContext(Dispatchers.Default) { PanelSnapshot.from(reportCache.get(data, now), now) }
        try { manager.notify(ID, build(context, snapshot, now)) } catch (_: SecurityException) { /* Permission changed during the database read. */ }
    }

    internal fun build(context: Context, data: PanelSnapshot, now: Long): Notification {
        val open = PendingIntent.getActivity(context, ID, Intent(context, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val refresh = PendingIntent.getBroadcast(context, ID, Intent(context, SubscriptionAlarmReceiver::class.java)
            .setAction("REFRESH_PANEL").setData(Uri.parse("slotra://panel/refresh")), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val formatter = NumberFormat.getNumberInstance(Locale.US).apply { maximumFractionDigits = 2 }
        fun money(value: Long?) = value?.let { "\u2066${formatter.format(java.math.BigDecimal.valueOf(it, 2))}\u2069 ج.س" } ?: "اضبط دورة الفاتورة"
        val summary = "دخل اليوم ${money(data.income)} · ناقص الهدف ${money(data.dailyShortfall)}"
        val lines = listOf(
            "دخل اليوم: ${money(data.income)}",
            "المطلوب اليوم: ${money(data.dailyTarget)}",
            "الناقص من هدف اليوم: ${money(data.dailyShortfall)}",
            "متبقي تكلفة الدورة: ${money(data.remainingBill)}",
            "ربح الدورة بعد كامل التكلفة: ${money(data.cycleProfit)}",
            "نشط ${data.active} · قارب الانتهاء ${data.soon} · متوقف ${data.paused}")
        val public = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_status_panel)
            .setContentTitle("Slotra · لوحة المتابعة").setContentText("افتح قفل الهاتف لعرض التفاصيل").build()
        return NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_status_panel)
            .setColor(Color.rgb(0, 107, 88)).setContentTitle("Slotra · ${data.active} مشترك نشط")
            .setContentText(summary).setSubText("لوحة المتابعة").setWhen(now).setShowWhen(true)
            .setStyle(NotificationCompat.InboxStyle().also { style -> lines.forEach { style.addLine(it) }; style.setSummaryText(data.coveredPercent?.let { "غُطي $it٪ من التكلفة · المبالغ بقيمة الكاش" } ?: "المبالغ بقيمة الكاش") })
            .setContentIntent(open).addAction(R.drawable.ic_status_panel, "التفاصيل", open)
            .addAction(android.R.drawable.ic_popup_sync, "تحديث", refresh)
            .setOngoing(true).setAutoCancel(false).setOnlyAlertOnce(true).setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW).setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public).build()
    }
}
