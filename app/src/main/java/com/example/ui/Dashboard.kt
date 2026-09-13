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

@Composable internal fun Dashboard(sessions: List<Session>, config: BusinessSettings, now: Long, manualSales: List<ManualSale> = emptyList(), addSales: () -> Unit = {}, add: () -> Unit) {
    val today = Revenue.day(now)
    val income = remember(sessions, manualSales) { sessions.filter { it.recognized > 0 && !it.home }.map { Income(it.recognized, it.cashEquivalent, it.amount, it.payment == "BANK") } + manualSales.map { Income(it.at, it.cashEquivalent, it.amount, it.payment == "BANK", it.count) } }
    val billBank = Money.bill(config.usdCents, config.bankRate)
    val billCash = Money.bankToCash(billBank, config.premiumBps)
    val configured = config.cycleStart > 0 && config.cycleEnd > config.cycleStart && config.usdCents > 0 && config.bankRate > 0
    val report = remember(income, config, now) { Revenue.report(income, config.cycleStart, config.cycleEnd, if (configured) billCash + config.expenses else null, now) }
    val daily = report.days.firstOrNull { it.day == today } ?: DailyIncome(today, 0, 0, 0, if (configured && now in config.cycleStart until config.cycleEnd) 0 else null, 0)
    val daysLeft = ((config.cycleEnd - maxOf(now, config.cycleStart)).coerceAtLeast(0) + 86399999) / 86400000
    var calendar by rememberSaveable { mutableStateOf(false) }
    var selectedDay by rememberSaveable { mutableLongStateOf(today) }
    val byDay = remember(report) { report.days.associateBy { it.day } }
    val history = remember(byDay, today) { List(3) { index ->
        val d = Calendar.getInstance().apply { timeInMillis = today; add(Calendar.DAY_OF_MONTH, -index) }.timeInMillis
        byDay[d] ?: DailyIncome(d, 0, 0, 0, null, 0)
    } }
    if (calendar) HistoryCalendar(report, sessions, manualSales, now, selectedDay) { calendar = false }
    LazyColumn(Modifier.fillMaxSize().testTag("dashboard-list"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(com.example.R.drawable.slotra_mark), null, Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)))
            Column { Title("Slotra", dateLabel(now)) }
        } }
        item { Button(onClick = add, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Icon(Icons.Default.PersonAdd, null); Spacer(Modifier.width(8.dp)); Text("تسجيل اشتراك") } }
        item { OutlinedButton(onClick = addSales, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Icon(Icons.Default.AddCard, null); Spacer(Modifier.width(8.dp)); Text("إضافة دخل بعدد الأجهزة") } }
        item { Panel {
            SectionHeading(Icons.Default.AccountBalanceWallet, "إيراد اليوم", "${daily.sales} اشتراكًا مثبتًا · ${dateLabel(today, "d MMMM")}")
            MoneyLine("إجمالي الإيراد بقيمة الكاش", daily.revenue, "today-revenue", strong = true)
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f)) { MoneyLine("الكاش المسجّل", daily.cash, "today-cash") }
                Column(Modifier.weight(1f)) { MoneyLine("بنكك المسجّل", daily.bank, "today-bank") }
            }
            Text("بنكك يُحوّل بالقيمة المحفوظة لكل اشتراك عند جمع الإيراد.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider()
            if (daily.profit != null) MoneyLine("ربح اليوم بعد تغطية تكلفة الدورة", daily.profit, "today-profit", strong = true)
            else Text("اضبط تكلفة وتواريخ الدورة لعرض ربح اليوم.", style = MaterialTheme.typography.bodyMedium)
            Text("يثبّت سعر الباقة كاملًا بعد ${config.graceMinutes} دقيقة استخدام. أهل البيت خارج الحساب.", style = MaterialTheme.typography.bodySmall)
        } }
        item { Panel {
            SectionHeading(Icons.Default.Groups, "المتابعة الآن", "حالات الوقت المسجّل داخل التطبيق")
            DetailLine(Icons.Default.Timer, "اشتراكات نشطة", "${sessions.count { it.state == "ACTIVE" }} مشترك")
            DetailLine(Icons.Default.PauseCircle, "الوقت متوقف مؤقتًا", "${sessions.count { it.state == "PAUSED" }} مشترك")
            DetailLine(Icons.Default.HourglassTop, "في انتظار تثبيت الإيراد", "${sessions.count { it.state == "ACTIVE" && !it.home && it.recognized == 0L }} اشتراك")
            DetailLine(Icons.Default.Home, "أهل البيت · مجاني", "${sessions.count { it.state == "ACTIVE" && it.home }} مشترك نشط")
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
        if (configured) item { Panel {
            SectionHeading(Icons.Default.TrendingUp, "تغطية التكلفة والربح", "حساب تراكمي للدورة المحددة")
            MoneyLine("إجمالي تكلفة الدورة مع المصروفات", report.cost!!)
            MoneyLine("إيراد الدورة بقيمة الكاش", report.cycleRevenue)
            val progress = if (report.cost == 0L) 1f else (report.covered.toDouble() / report.cost!!).toFloat()
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Text("تمت تغطية ${(progress * 100).toInt()}٪ من التكلفة", style = MaterialTheme.typography.labelLarge)
            HorizontalDivider()
            if (report.remainingCost!! > 0) {
                MoneyLine("المتبقي لتغطية التكلفة", report.remainingCost, strong = true)
                if (daysLeft > 0) MoneyLine("المطلوب يوميًا حتى آخر يوم", (report.remainingCost + daysLeft - 1) / daysLeft)
            } else DetailLine(Icons.Default.CheckCircle, "التكلفة مغطاة بالكامل", "كل إيراد إضافي في هذه الدورة يُحسب ربحًا")
            MoneyLine("الربح بعد تغطية التكلفة", report.cycleProfit!!, "cycle-profit", strong = true)
            Text("الربح تقديري حسب التكلفة والمصروفات المدخلة. تغييرهما يعيد حساب التقرير، ولا يغيّر أسعار الاشتراكات المحفوظة.", style = MaterialTheme.typography.bodySmall)
        } }
        item { Panel {
            SectionHeading(Icons.Default.History, "آخر 3 أيام", "اضغط اليوم لعرض الدخل والربح بالتفصيل")
            history.forEachIndexed { index, day ->
                if (index > 0) HorizontalDivider()
                TextButton(onClick = { selectedDay = day.day; calendar = true }, modifier = Modifier.fillMaxWidth().testTag("recent-day-$index"), contentPadding = PaddingValues(vertical = 8.dp)) {
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                        Text(if (day.day == today) "اليوم" else dateLabel(day.day, "EEEE، d MMMM"), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleSmall)
                        Text("${day.sales} اشتراك · ${if (day.profit == null) "الربح غير محدد" else "الربح ${amount(day.profit)}"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(amount(day.revenue), fontWeight = FontWeight.Bold)
                }
            }
            OutlinedButton(onClick = { selectedDay = today; calendar = true }, modifier = Modifier.fillMaxWidth().testTag("open-history")) { Icon(Icons.Default.CalendarMonth, null); Spacer(Modifier.width(8.dp)); Text("تصفّح الشهرين") }
        } }
    }
}
