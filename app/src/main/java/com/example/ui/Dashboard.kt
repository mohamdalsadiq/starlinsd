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
        item(key = "income") {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("إيراد اليوم المعتمد", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(amount(daily.revenue), Modifier.testTag("today-revenue"),
                        style = MaterialTheme.typography.headlineLarge.copy(textDirection = androidx.compose.ui.text.style.TextDirection.ContentOrLtr),
                        fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("${daily.sales} اشتراك · القيمة المحتسبة بالكاش", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { selectedDay = snapshot.day; calendar = true }, modifier = Modifier.testTag("review-today-income")) {
                        Icon(Icons.Default.EditNote, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("مراجعة وتعديل دخل اليوم")
                    }
                }
            }
        }
        item(key = "daily-target") {
            val budget = snapshot.budget.day(snapshot.day)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DailyTargetCards(budget, config.premiumBps)
                Text(if (snapshot.balance == null) "الكاش وبنكك قيمتان بديلتان لنفس المطلوب حسب نسبة التحويل."
                    else "الكاش وبنكك بديلان لنفس المطلوب. تقدم الهدف من التحصيلات بعد آخر تحديث للرصيد أو بداية اليوم.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item(key = "profit") { Panel {
            SectionHeading(Icons.Default.ReceiptLong, "تغطية الفاتورة", "حساب الدورة كاملة · قبل الديون")
            if (report.cost != null) {
                MoneyLine("المتبقي من تكلفة الدورة كاملة", snapshot.remainingForBill!!, "cycle-remaining", true)
                Text("بنكك المكافئ: ${amount(Money.cashToBank(snapshot.remainingForBill!!, config.premiumBps))}", Modifier.testTag("cycle-remaining-bank"), style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(progress = { if (report.cost == 0L) 1f else (snapshot.coveredForBill.toDouble() / report.cost).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text(if (snapshot.balance == null) "دخل الدورة ${amount(report.cycleRevenue)} من تكلفة ${amount(report.cost)}"
                    else "الخطة من الرصيد الموجود · آخر تحديث ${stamp(snapshot.balance.update.at)}", style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
                MoneyLine("ربح الدورة بعد كامل التكلفة", report.cycleProfit!!, "cycle-profit")
                Text("الربح بعد تغطية الفاتورة والمصروفات كاملة. التغطية المحسوبة لا تعني سداد الفاتورة.",
                    Modifier.testTag("cycle-profit-explanation"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else Text("حدد تكلفة الدورة وفترتها من الإعدادات لحساب التغطية والربح.")
            TextButton(onClick = openReports, modifier = Modifier.testTag("open-reports")) { Icon(Icons.Default.BarChart, null); Spacer(Modifier.width(8.dp)); Text("التقارير وتفاصيل الدورة") }
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

@Composable internal fun DailyTargetCards(day: BudgetDay, premiumBps: Int = 2500) {
    if (day.billTarget == null) {
        Panel { Text("اضبط تواريخ الدورة وتكلفتها لإظهار المطلوب اليوم.") }
        return
    }
    val largeText = androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.2f
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 320.dp || largeText) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TargetTile("المطلوب اليوم للفاتورة · كاش", day.billTarget, "daily-bill-target", "يتجدد مع بداية كل يوم", premiumBps, Modifier.fillMaxWidth())
                TargetTile("الناقص من هدف اليوم · كاش", day.shortfall, "daily-shortfall", if (day.shortfall == 0L) "تحقق هدف اليوم" else "ينقص مع التحصيل", premiumBps, Modifier.fillMaxWidth())
            }
        } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TargetTile("المطلوب اليوم للفاتورة · كاش", day.billTarget, "daily-bill-target", "يتجدد مع بداية كل يوم", premiumBps, Modifier.weight(1f))
            TargetTile("الناقص من هدف اليوم · كاش", day.shortfall, "daily-shortfall", if (day.shortfall == 0L) "تحقق هدف اليوم" else "ينقص مع التحصيل", premiumBps, Modifier.weight(1f))
        }
    }
}

@Composable private fun TargetTile(label: String, value: Long, tag: String, hint: String, premiumBps: Int, modifier: Modifier) {
    OutlinedCard(modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(amount(value), Modifier.testTag(tag), style = MaterialTheme.typography.titleLarge.copy(textDirection = androidx.compose.ui.text.style.TextDirection.ContentOrLtr), fontWeight = FontWeight.Bold)
            Text("بنكك: ${amount(Money.cashToBank(value, premiumBps))}", Modifier.testTag("$tag-bank"), style = MaterialTheme.typography.bodySmall)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
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
        item { Panel {
            SectionHeading(Icons.Default.Info, "كيف يُحسب هدف اليوم؟")
            Text("المتبقي من تكلفة الدورة قبل بداية اليوم ÷ الأيام الباقية، مع احتساب اليوم. يبقى الهدف ثابتًا أثناء تسجيل دخل اليوم.")
            Text("تصحيح إيراد سابق أو تعديل تكلفة الدورة يعيد حساب الخطة. الحساب تقديري، ولا يثبت رصيد الكاش أو سداد الفاتورة.", style = MaterialTheme.typography.bodySmall)
        } }
    }
}

@Composable internal fun DebtPaymentIndicator(balance: DebtBalance) {
    val status = when {
        balance.remaining == 0L -> "مسدد بالكامل · لا يلزم سداد"
        Revenue.day(balance.debt.due) <= Revenue.day(System.currentTimeMillis()) -> "مطلوب السداد · حلّ الموعد"
        else -> "دين قائم · لم يحل الموعد"
    }
    val icon = when {
        balance.remaining == 0L -> Icons.Default.CheckCircle
        Revenue.day(balance.debt.due) <= Revenue.day(System.currentTimeMillis()) -> Icons.Default.Payments
        else -> Icons.Default.Schedule
    }
    Column(Modifier.fillMaxWidth().testTag("debt-status-${balance.debt.id}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(balance.debt.name, style = MaterialTheme.typography.titleMedium)
        DetailLine(icon, status, "المتبقي: ${amount(balance.remaining)}")
        if (balance.remaining > 0L)
            Text("السداد يدوي؛ لا يُخصم من فائض اليوم تلقائيًا.", style = MaterialTheme.typography.bodySmall)
    }
}
