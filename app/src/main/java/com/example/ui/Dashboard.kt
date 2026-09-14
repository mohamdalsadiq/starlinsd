package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.db.*
import com.example.domain.*
import java.text.SimpleDateFormat
import java.util.*

@Composable internal fun SectionHeading(icon: ImageVector, title: String, subtitle: String = "") {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable internal fun MoneyLine(label: String, value: Long, tag: String = "", strong: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(amount(value), Modifier.testTag(tag), style = if (strong) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold, color = if (strong) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
    }
}
@Composable internal fun DetailLine(icon: ImageVector, label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
private fun dateLabel(at: Long, pattern: String = "EEEE، d MMMM yyyy") = SimpleDateFormat(pattern, Locale.forLanguageTag("ar")).format(Date(at))

@Composable internal fun Dashboard(sessions: List<Session>, config: BusinessSettings, now: Long, manualSales: List<ManualSale> = emptyList(), addSales: () -> Unit = {}, corrections: List<RevenueCorrection> = emptyList(), cycles: List<BillingCycle> = emptyList(), debts: List<Debt> = emptyList(), debtPayments: List<DebtPayment> = emptyList(),
    correct: (String, Long, Int, Boolean, String) -> Unit = { _, _, _, _, _ -> }, openDebts: () -> Unit = {}, add: () -> Unit) {
    val today = Revenue.day(now)
    val ledger = remember(sessions, manualSales, corrections) { Finance.ledger(sessions, manualSales, corrections) }
    val income = remember(ledger) { ledger.filterNot { it.voided }.map { it.income() } }
    val billBank = Money.bill(config.usdCents, config.bankRate)
    val billCash = Money.bankToCash(billBank, config.premiumBps)
    val configured = config.cycleStart > 0 && config.cycleEnd > config.cycleStart && config.usdCents > 0 && config.bankRate > 0
    val savedCycles = remember(cycles, config, configured) { if (cycles.isEmpty() && configured) listOf(BillingCycle("current", config.cycleStart, config.cycleEnd, billCash + config.expenses)) else cycles }
    val budget = remember(ledger, savedCycles, debts, debtPayments, now) { Finance.report(ledger, savedCycles, debts, debtPayments, now) }
    val todayBudget = budget.days.getValue(today)
    val billReserved = minOf(billCash + config.expenses, budget.days.values.filter { it.day >= Revenue.day(config.cycleStart) && it.day < config.cycleEnd }.sumOf { it.billReserved })
    val billRemaining = (billCash + config.expenses - billReserved).coerceAtLeast(0)
    val report = remember(income, config, now) { Revenue.report(income, config.cycleStart, config.cycleEnd, if (configured) billCash + config.expenses else null, now) }
    val daily = report.days.firstOrNull { it.day == today } ?: DailyIncome(today, 0, 0, 0, if (configured && now in config.cycleStart until config.cycleEnd) 0 else null, 0)
    val daysLeft = ((config.cycleEnd - maxOf(now, config.cycleStart)).coerceAtLeast(0) + 86399999) / 86400000
    var showCoverageDetails by rememberSaveable { mutableStateOf(false) }
    var calendar by rememberSaveable { mutableStateOf(false) }
    var selectedDay by rememberSaveable { mutableLongStateOf(today) }
    val byDay = remember(report) { report.days.associateBy { it.day } }
    val history = remember(byDay, today, configured, config.cycleStart, config.cycleEnd) { List(3) { index ->
        val d = Calendar.getInstance().apply { timeInMillis = today; add(Calendar.DAY_OF_MONTH, -index) }.timeInMillis
        byDay[d] ?: DailyIncome(d, 0, 0, 0, if (configured && d in Revenue.day(config.cycleStart) until config.cycleEnd) 0 else null, 0)
    } }
    if (calendar) HistoryCalendar(report, sessions, manualSales, now, selectedDay, config.cycleStart, config.cycleEnd, ledger, budget, correct) { calendar = false }
    LazyColumn(Modifier.fillMaxSize().testTag("dashboard-list"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(com.example.R.drawable.slotra_mark), null, Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)))
            Column { Title("Slotra", dateLabel(now)) }
        } }
        item { Panel {
            SectionHeading(Icons.Default.AccountBalanceWallet, "إيراد اليوم", "${daily.sales} اشتراكًا مثبتًا · ${dateLabel(today, "d MMMM")}")
            MoneyLine("إجمالي الإيراد بقيمة الكاش", daily.revenue, "today-revenue", strong = true)
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f)) { MoneyLine("الكاش المسجّل", daily.cash, "today-cash") }
                Column(Modifier.weight(1f)) { MoneyLine("بنكك المسجّل", daily.bank, "today-bank") }
            }
            HorizontalDivider()
            TextButton(onClick = { selectedDay = today; calendar = true }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.EditNote, null); Text("مراجعة وتعديل دخل اليوم") }
        } }
        if (configured) item { Panel {
            SectionHeading(Icons.Default.TrendingUp, "تغطية التكلفة والربح", "حساب تراكمي للدورة المحددة")
            MoneyLine("صافي الدورة بعد كامل الفاتورة · قبل الديون", report.cycleProfit!!, "cycle-profit", strong = true)
            Text("هذا ربحك الفعلي بعد كامل فاتورة الدورة. الفائض اليومي في الأسفل رقم توزيع مؤقت فقط وليس ربحًا.", Modifier.testTag("cycle-profit-explanation"), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { showCoverageDetails = !showCoverageDetails }) { Text(if (showCoverageDetails) "إخفاء تفاصيل التغطية" else "تفاصيل تكلفة الدورة وتغطيتها") }
            if (showCoverageDetails) {
            MoneyLine("إجمالي تكلفة الدورة مع المصروفات", report.cost!!)
            MoneyLine("إيراد الدورة بقيمة الكاش", report.cycleRevenue)
            MoneyLine("المحجوز من الإيراد للفاتورة", billReserved)
            val progress = if (report.cost == 0L) 1f else (billReserved.toDouble() / report.cost!!).toFloat()
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Text("خُصص ${(progress * 100).toInt()}٪ من قيمة الفاتورة", style = MaterialTheme.typography.labelLarge)
            HorizontalDivider()
            if (billRemaining > 0) {
                MoneyLine("المتبقي لتخصيص كامل الفاتورة", billRemaining, strong = true)
                MoneyLine("المخصص اليومي الثابت", Finance.dailyTarget(BillingCycle("current", config.cycleStart, config.cycleEnd, report.cost!!)))
            } else DetailLine(Icons.Default.CheckCircle, "اكتمل تخصيص الفاتورة", "هذه مقارنة بكامل الفاتورة؛ الفائض اليومي معروض منفصلًا")
            if (now >= config.cycleStart) {
                val tomorrow = Calendar.getInstance().apply { timeInMillis = today; add(Calendar.DAY_OF_MONTH, 1) }.timeInMillis
                val target = Finance.dailyTarget(BillingCycle("current", config.cycleStart, config.cycleEnd, report.cost!!))
                val expected = minOf(report.cost!!, target * Finance.days(config.cycleStart, minOf(config.cycleEnd, tomorrow)))
                if (expected > billReserved) MoneyLine("نقص التخصيص المتراكم حتى اليوم", expected - billReserved)
            }
            Text("الربح تقديري حسب التكلفة والمصروفات المدخلة. تغييرهما يعيد حساب التقرير، ولا يغيّر أسعار الاشتراكات المحفوظة.", style = MaterialTheme.typography.bodySmall)
            }
        } }
        item { Button(onClick = add, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Icon(Icons.Default.PersonAdd, null); Spacer(Modifier.width(8.dp)); Text("تسجيل اشتراك") } }
        item { OutlinedButton(onClick = addSales, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Icon(Icons.Default.AddCard, null); Spacer(Modifier.width(8.dp)); Text("إضافة دخل بعدد الأجهزة") } }
        item { Panel {
            SectionHeading(Icons.Default.Calculate, "توزيع دخل اليوم", "مخصص ثابت للفاتورة ثم الديون ثم الفائض المتاح")
            BudgetSummary(todayBudget)
        } }
        item { Panel {
            SectionHeading(Icons.Default.AccountBalance, "الديون والفائض", "التخصيص يسبق السداد الفعلي")
            MoneyLine("المتبقي من الديون", budget.debts.sumOf { it.remaining })
            MoneyLine("مخصص متاح للسداد", budget.debts.sumOf { it.reserved })
            if (budget.debts.sumOf { it.fundingGap } > 0) Text("يوجد عجز ${amount(budget.debts.sumOf { it.fundingGap })} بعد تصحيح الإيراد؛ السداد السابق محفوظ.", color = MaterialTheme.colorScheme.error)
            if (budget.debts.isEmpty()) Text("لا توجد ديون مسجلة", style = MaterialTheme.typography.bodyMedium)
            budget.debts.forEach { balance ->
                HorizontalDivider()
                DebtPaymentIndicator(balance)
            }
            OutlinedButton(onClick = openDebts, modifier = Modifier.fillMaxWidth()) { Text("إدارة الديون وتسجيل السداد") }
        } }
        item { Panel {
            val monthStart = Calendar.getInstance().apply { timeInMillis = today; set(Calendar.DAY_OF_MONTH, 1) }.timeInMillis
            val monthDays = budget.days.values.filter { it.day >= monthStart && it.day <= today }
            SectionHeading(Icons.Default.CalendarMonth, "حساب الشهر", "${dateLabel(today, "MMMM yyyy")} · حتى اليوم")
            MoneyLine("إجمالي إيراد الشهر", monthDays.sumOf { it.revenue }, strong = true)
            MoneyLine("المخصص للفاتورة", monthDays.sumOf { it.billReserved })
            MoneyLine("المخصص للديون", monthDays.sumOf { it.debtReserved })
            MoneyLine("الفائض المتاح بعد التخصيص", monthDays.sumOf { it.available ?: 0 })
            if (monthDays.any { it.billTarget == null && it.revenue > 0 }) Text("توجد أيام دون دورة محفوظة؛ فائضها غير محسوب.", style = MaterialTheme.typography.bodySmall)
        } }
        item { Panel {
            SectionHeading(Icons.Default.Groups, "المتابعة الآن", "حالات الوقت المسجّل داخل التطبيق")
            DetailLine(Icons.Default.Timer, "اشتراكات نشطة", "${sessions.count { !it.home && it.state == "ACTIVE" }} مشترك")
            DetailLine(Icons.Default.PauseCircle, "الوقت متوقف مؤقتًا", "${sessions.count { !it.home && it.state == "PAUSED" }} مشترك")
            DetailLine(Icons.Default.HourglassTop, "في انتظار تثبيت الإيراد", "${sessions.count { it.state == "ACTIVE" && !it.home && it.recognized == 0L }} اشتراك")
            DetailLine(Icons.Default.Home, "أهل البيت", "اختصارات نصية فقط · بلا تسجيل أو تنبيهات")
            Text("هذه حالات المؤقتات، وليست كشفًا بالأجهزة المتصلة. تحكّم في اتصال الإنترنت من الراوتر.", style = MaterialTheme.typography.bodySmall)
        } }
        item { Panel {
            SectionHeading(Icons.Default.SatelliteAlt, "دورة Starlink", "التكلفة والتواريخ والمتبقّي")
            if (!configured) Text("أدخل بداية الدورة ونهايتها وقيمة الاشتراك بالدولار وسعر الدولار ببنكك من الإعدادات.")
            else {
                DetailLine(Icons.Default.EventAvailable, "أول يوم", dateLabel(config.cycleStart))
                DetailLine(Icons.Default.EventBusy, "آخر يوم شاملًا", dateLabel(config.cycleEnd - 1))
                DetailLine(Icons.Default.CalendarMonth, "المدة المتبقية", when {
                    now < config.cycleStart -> "الدورة لم تبدأ · مدتها $daysLeft يومًا"
                    daysLeft == 0L -> "انتهت الدورة"
                    else -> "$daysLeft يومًا"
                })
                HorizontalDivider()
                DetailLine(Icons.Default.AttachMoney, "فاتورة Starlink", "${Money.show(config.usdCents)} دولار")
                DetailLine(Icons.Default.CurrencyExchange, "سعر الدولار ببنكك", amount(config.bankRate))
                DetailLine(Icons.Default.AccountBalance, "الفاتورة بالجنيه · بنكك", amount(billBank))
                DetailLine(Icons.Default.Payments, "الفاتورة بالقيمة المكافئة للكاش", amount(billCash))
                DetailLine(Icons.Default.ReceiptLong, "مصروفات الدورة · قيمة كاش", amount(config.expenses))
            }
        } }
        item { Panel {
            SectionHeading(Icons.Default.History, "آخر 3 أيام", "اضغط اليوم لعرض الدخل والربح بالتفصيل")
            history.forEachIndexed { index, day ->
                if (index > 0) HorizontalDivider()
                TextButton(onClick = { selectedDay = day.day; calendar = true }, modifier = Modifier.fillMaxWidth().testTag("recent-day-$index"), contentPadding = PaddingValues(vertical = 8.dp)) {
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                        Text(if (day.day == today) "اليوم" else dateLabel(day.day, "EEEE، d MMMM"), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleSmall)
                        Text("${day.sales} اشتراك · ${budget.day(day.day).available?.let { "المتاح ${amount(it)}" } ?: "لا يوجد فائض محسوب"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(amount(day.revenue), fontWeight = FontWeight.Bold)
                }
            }
            OutlinedButton(onClick = { selectedDay = today; calendar = true }, modifier = Modifier.fillMaxWidth().testTag("open-history")) { Icon(Icons.Default.CalendarMonth, null); Spacer(Modifier.width(8.dp)); Text("تصفّح الشهرين") }
        } }
    }
}

@Composable internal fun DebtPaymentIndicator(balance: DebtBalance) {
    val status = when {
        balance.remaining == 0L -> "مسدد بالكامل · لا يلزم سداد"
        balance.reserved > 0L -> "سداد جاهز اليوم: ${amount(minOf(balance.reserved, balance.remaining))}"
        else -> "لم يتوفر مخصص للسداد اليوم"
    }
    val icon = when {
        balance.remaining == 0L -> Icons.Default.CheckCircle
        balance.reserved > 0L -> Icons.Default.Payments
        else -> Icons.Default.Schedule
    }
    Column(Modifier.fillMaxWidth().testTag("debt-status-${balance.debt.id}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(balance.debt.name, style = MaterialTheme.typography.titleMedium)
        DetailLine(icon, status, "المتبقي: ${amount(balance.remaining)}")
        if (balance.remaining > 0L && balance.reserved == 0L)
            Text("الدين ما زال قائمًا؛ عدم وجود مخصص لا يعني أنه مسدد.", style = MaterialTheme.typography.bodySmall)
    }
}
