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
import java.text.NumberFormat
import java.util.*

/** Reuses the dashboard ledger; no separate financial totals are stored. */
data class PanelSnapshot(val active: Int, val soon: Int, val ended: Int, val paused: Int,
    val income: Long, val remainingBill: Long?, val available: Long?, val cycleProfit: Long?, val coveredPercent: Int?) {
    companion object {
        fun calculate(sessions: List<Session>, sales: List<ManualSale>, corrections: List<RevenueCorrection>,
            config: BusinessSettings, cycles: List<BillingCycle>, debts: List<Debt>, payments: List<DebtPayment>, now: Long): PanelSnapshot {
            val paid = sessions.filterNot { it.home }
            val active = paid.filter { it.state == "ACTIVE" && Rules.remaining(it.clock(), now) > 0 }
            val ledger = Finance.ledger(sessions, sales, corrections)
            val configured = config.cycleStart > 0 && config.cycleEnd > config.cycleStart && config.usdCents > 0 && config.bankRate > 0
            val cost = if (configured) Money.bankToCash(Money.bill(config.usdCents, config.bankRate), config.premiumBps) + config.expenses else null
            val savedCycles = if (cycles.isEmpty() && cost != null) listOf(BillingCycle("current", config.cycleStart, config.cycleEnd, cost)) else cycles
            val budget = Finance.report(ledger, savedCycles, debts, payments, now)
            val today = budget.day(now)
            val reserved = cost?.let { minOf(it, budget.days.values.filter { d -> d.day >= Revenue.day(config.cycleStart) && d.day < config.cycleEnd }.sumOf { d -> d.billReserved }) }
            val cycle = Revenue.report(ledger.filterNot { it.voided }.map { it.income() }, config.cycleStart, config.cycleEnd, cost, now)
            return PanelSnapshot(active.size, active.count { Rules.remaining(it.clock(), now) <= 10 * Rules.MINUTE },
                paid.count { it.state == "ENDED" || it.state == "ACTIVE" && Rules.remaining(it.clock(), now) == 0L },
                paid.count { it.state == "PAUSED" }, today.revenue, cost?.let { it - reserved!! }, today.available, cycle.cycleProfit,
                cost?.let { if (it == 0L) 100 else ((reserved!!.toDouble() / it) * 100).toInt().coerceIn(0, 100) })
        }
    }
}

object StatusPanel {
    const val CHANNEL = "slotra_status_panel"
    const val ID = 3201
    private const val PREFS = "status_panel"
    fun enabled(context: Context) = context.getSharedPreferences(PREFS, 0).getBoolean("enabled", true)
    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, 0).edit().putBoolean("enabled", enabled).apply()
        if (!enabled) (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(ID)
    }
    fun allowed(context: Context): Boolean {
        val runtime = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val channel = if (Build.VERSION.SDK_INT >= 26) (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).getNotificationChannel(CHANNEL) else null
        return runtime && NotificationManagerCompat.from(context).areNotificationsEnabled() && (channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE)
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
        val snapshot = db.withTransaction {
            val dao = db.businessDao()
            PanelSnapshot.calculate(dao.sessions(), dao.manualSales(), dao.corrections(), dao.settings() ?: BusinessSettings(), dao.cycles(), dao.debts(), dao.debtPayments(), now)
        }
        try { manager.notify(ID, build(context, snapshot, now)) } catch (_: SecurityException) { /* Permission changed during the database read. */ }
    }

    internal fun build(context: Context, data: PanelSnapshot, now: Long): Notification {
        val open = PendingIntent.getActivity(context, ID, Intent(context, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val refresh = PendingIntent.getBroadcast(context, ID, Intent(context, SubscriptionAlarmReceiver::class.java)
            .setAction("REFRESH_PANEL").setData(Uri.parse("slotra://panel/refresh")), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val formatter = NumberFormat.getNumberInstance(Locale.US).apply { maximumFractionDigits = 2 }
        fun money(value: Long?) = value?.let { "\u2066${formatter.format(java.math.BigDecimal.valueOf(it, 2))}\u2069 ج.س" } ?: "اضبط دورة الفاتورة"
        val summary = "قارب الانتهاء ${data.soon} · منتهون بالسجل ${data.ended}"
        val lines = listOf(
            "نشط ${data.active}  ·  خلال 10 دقائق ${data.soon}  ·  متوقف ${data.paused}",
            "منتهون بالسجل ${data.ended} · أهل البيت خارج العدّ",
            "دخل اليوم: ${money(data.income)}",
            "ربح الدورة قبل الديون: ${money(data.cycleProfit)}",
            "متبقي حجز الفاتورة: ${money(data.remainingBill)}",
            "فائض توزيع اليوم بعد الديون: ${money(data.available)}")
        val public = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_status_panel)
            .setContentTitle("Slotra · لوحة المتابعة").setContentText("افتح قفل الهاتف لعرض التفاصيل").build()
        return NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_status_panel)
            .setColor(Color.rgb(0, 107, 88)).setContentTitle("Slotra · ${data.active} مشترك نشط")
            .setContentText(summary).setSubText("لوحة المتابعة").setWhen(now).setShowWhen(true)
            .setStyle(NotificationCompat.InboxStyle().also { style -> lines.forEach { style.addLine(it) }; style.setSummaryText(data.coveredPercent?.let { "حُجز $it٪ للفاتورة · المبالغ بقيمة الكاش" } ?: "المبالغ بقيمة الكاش") })
            .setContentIntent(open).addAction(R.drawable.ic_status_panel, "التفاصيل", open)
            .addAction(android.R.drawable.ic_popup_sync, "تحديث", refresh)
            .setOngoing(true).setAutoCancel(false).setOnlyAlertOnce(true).setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW).setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public).build()
    }
}
