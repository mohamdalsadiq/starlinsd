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
        Text(amount(value), Modifier.testTag(tag), style = (if (strong) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge).copy(textDirection = androidx.compose.ui.text.style.TextDirection.ContentOrLtr),
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


@Composable internal fun Dashboard(snapshot: FinancialSnapshot, addSales: () -> Unit = {},
    openReports: () -> Unit = {}, correct: (String, Long, Int, Boolean, String) -> Unit = { _, _, _, _, _ -> },
    live: @Composable () -> Unit = {}, add: () -> Unit) {
    val config = snapshot.data.config
    val report = snapshot.revenue
    val daily = snapshot.today
    var calendar by rememberSaveable { mutableStateOf(false) }
    var selectedDay by rememberSaveable { mutableLongStateOf(snapshot.day) }
    val history = remember(report, snapshot.day) {
        val byDay = report.days.associateBy { it.day }
        List(3) { index ->
            val day = Calendar.getInstance().apply { timeInMillis = snapshot.day; add(Calendar.DAY_OF_MONTH, -index) }.timeInMillis
            byDay[day] ?: DailyIncome(day, 0, 0, 0, null, 0)
        }
    }
    if (calendar) SnapshotHistory(snapshot, selectedDay, correct) { calendar = false }
    LazyColumn(Modifier.fillMaxSize().testTag("dashboard-list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "brand") { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(com.example.R.drawable.slotra_mark), null, Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)))
            Column { Text("Slotra", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text(dateLabel(snapshot.day), style = MaterialTheme.typography.bodySmall) }
        } }
        item(key = "income") { Panel {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { MoneyLine("إيراد اليوم", daily.revenue, "today-revenue", true); Text("${daily.sales} اشتراكًا مثبتًا", style = MaterialTheme.typography.bodySmall) }
                IconButton(onClick = { selectedDay = snapshot.day; calendar = true }) { Icon(Icons.Default.EditNote, "مراجعة وتعديل دخل اليوم") }
            }
        } }
        item(key = "profit") { Panel {
            Text("تغطية التكلفة والربح", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (report.cost != null) {
                MoneyLine("صافي الدورة بعد كامل الفاتورة · قبل الديون", report.cycleProfit!!, "cycle-profit", true)
                Text("هذا ربحك الفعلي بعد كامل فاتورة الدورة. الفائض اليومي في الأسفل رقم توزيع مؤقت فقط وليس ربحًا.",
                    Modifier.testTag("cycle-profit-explanation"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) { MoneyLine("دخل الدورة", report.cycleRevenue) }
                    Column(Modifier.weight(1f)) { MoneyLine("متبقي تغطية التكلفة", report.remainingCost!!, "cycle-remaining") }
                }
                LinearProgressIndicator(progress = { if (report.cost == 0L) 1f else (report.covered.toDouble() / report.cost).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text("التكلفة ${amount(report.cost)} · ${dateLabel(config.cycleStart, "d MMM")} – ${dateLabel(config.cycleEnd - 1, "d MMM")}", style = MaterialTheme.typography.bodySmall)
            } else Text("حدد تكلفة الدورة وفترتها من المزيد ← الإعدادات لحساب ربحك.")
            TextButton(onClick = openReports, modifier = Modifier.testTag("open-reports")) { Icon(Icons.Default.BarChart, null); Spacer(Modifier.width(8.dp)); Text("التقارير وتفاصيل التوزيع") }
        } }
        item(key = "actions") { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = add, modifier = Modifier.weight(1f)) { Icon(Icons.Default.PersonAdd, null, Modifier.size(20.dp)); Spacer(Modifier.width(6.dp)); Text("اشتراك جديد") }
            OutlinedButton(onClick = addSales, modifier = Modifier.weight(1f)) { Icon(Icons.Default.AddCard, null, Modifier.size(20.dp)); Spacer(Modifier.width(6.dp)); Text("إضافة دخل") }
        } }
        item(key = "live") { live() }
        item(key = "history") { Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("آخر 3 أيام", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                TextButton(onClick = { selectedDay = snapshot.day; calendar = true }, modifier = Modifier.testTag("open-history")) { Text("سجل الشهرين"); Icon(Icons.Default.CalendarMonth, null, Modifier.size(20.dp)) }
            }
            history.forEachIndexed { index, day ->
                if (index > 0) HorizontalDivider()
                TextButton(onClick = { selectedDay = day.day; calendar = true }, modifier = Modifier.fillMaxWidth().testTag("recent-day-$index")) {
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                        Text(if (index == 0) "اليوم" else dateLabel(day.day, "EEEE، d MMM"), color = MaterialTheme.colorScheme.onSurface)
                        Text("${day.sales} اشتراك", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(amount(day.revenue), fontWeight = FontWeight.Bold)
                }
            }
        } }
    }
}

@Composable internal fun SnapshotHistory(snapshot: FinancialSnapshot, selected: Long,
    correct: (String, Long, Int, Boolean, String) -> Unit, dismiss: () -> Unit) {
    HistoryCalendar(snapshot.revenue, snapshot.data.sessions, snapshot.data.sales, snapshot.day, selected,
        snapshot.data.config.cycleStart, snapshot.data.config.cycleEnd, snapshot.ledger, snapshot.budget, correct, dismiss)
}

@Composable internal fun ReportsScreen(snapshot: FinancialSnapshot, correct: (String, Long, Int, Boolean, String) -> Unit) {
    var calendar by rememberSaveable { mutableStateOf(false) }
    val config = snapshot.data.config
    val report = snapshot.revenue
    val monthStart = remember(snapshot.day) { Calendar.getInstance().apply { timeInMillis = snapshot.day; set(Calendar.DAY_OF_MONTH, 1) }.timeInMillis }
    val monthIncome = remember(report, monthStart) { report.days.filter { it.day >= monthStart }.sumOf { it.revenue } }
    val reserved = remember(snapshot) { report.cost?.let { cost -> minOf(cost, snapshot.budget.days.values.filter { it.day >= Revenue.day(config.cycleStart) && it.day < config.cycleEnd }.sumOf { it.billReserved }) } }
    if (calendar) SnapshotHistory(snapshot, snapshot.day, correct) { calendar = false }
    LazyColumn(Modifier.fillMaxSize().testTag("reports-list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Panel {
            SectionHeading(Icons.Default.CalendarMonth, "إيراد الشهر", dateLabel(snapshot.day, "MMMM yyyy"))
            MoneyLine("إجمالي الدخل", monthIncome, "month-income", true)
            Button(onClick = { calendar = true }) { Text("مراجعة الأيام وتعديل الإيرادات") }
        } }
        item { Panel {
            SectionHeading(Icons.Default.TrendingUp, "حساب الدورة", "الربح بعد التكلفة كاملة · قبل الديون")
            MoneyLine("دخل الدورة", report.cycleRevenue)
            report.cost?.let { MoneyLine("التكلفة مع المصروفات", it); MoneyLine("الربح الفعلي", report.cycleProfit!!, strong = true); MoneyLine("المتبقي للتغطية", report.remainingCost!!) }
            if (config.cycleEnd > config.cycleStart && config.cycleStart > 0) {
                Text("${dateLabel(config.cycleStart, "d MMM yyyy")} — ${dateLabel(config.cycleEnd - 1, "d MMM yyyy")}")
                val left = if (snapshot.day >= config.cycleEnd) 0 else Finance.days(maxOf(snapshot.day, config.cycleStart), config.cycleEnd)
                Text("$left يومًا متبقيًا", style = MaterialTheme.typography.titleMedium)
            }
        } }
        item { Panel {
            SectionHeading(Icons.Default.PieChart, "توزيع اليوم", "مخصصات تقديرية · ليست ربح الدورة")
            BudgetSummary(snapshot.budget.day(snapshot.day))
        } }
        if (reserved != null) item { Panel {
            SectionHeading(Icons.Default.ReceiptLong, "تخصيص الفاتورة", "خطة ادخار يومية · لا تعني سداد الفاتورة")
            MoneyLine("المحجوز حسب الخطة", reserved)
            MoneyLine("المتبقي للتخصيص حسب الخطة", (report.cost!! - reserved).coerceAtLeast(0))
            Text("متبقي التغطية في الرئيسية يطرح كامل دخل الدورة من التكلفة. هنا نعرض ما خصصته الخطة اليومية فقط.", style = MaterialTheme.typography.bodySmall)
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
