package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.db.*
import com.example.domain.*
import java.text.SimpleDateFormat
import java.util.*

@Composable internal fun HistoryCalendar(report: RevenueReport, sessions: List<Session>, manual: List<ManualSale>, now: Long, initialDay: Long, cycleStart: Long, cycleEnd: Long, ledger: List<LedgerEntry>, budget: BudgetReport, correct: (String, Long, Int, Boolean, String) -> Unit, dismiss: () -> Unit) {
    var editing by remember { mutableStateOf<LedgerEntry?>(null) }
    var removing by remember { mutableStateOf<LedgerEntry?>(null) }
    val today = Revenue.day(now)
    val currentMonth = remember(today) { Calendar.getInstance().apply { timeInMillis = today; set(Calendar.DAY_OF_MONTH, 1) }.timeInMillis }
    var offset by rememberSaveable { mutableIntStateOf(if (initialDay < currentMonth) -1 else 0) }
    var selected by rememberSaveable { mutableLongStateOf(initialDay) }
    val month = remember(currentMonth, offset) { Calendar.getInstance().apply { timeInMillis = currentMonth; add(Calendar.MONTH, offset) } }
    val nextMonth = remember(month) { (month.clone() as Calendar).apply { add(Calendar.MONTH, 1) }.timeInMillis }
    val byDay = remember(report) { report.days.associateBy { it.day } }
    val daily = byDay[selected] ?: DailyIncome(selected, 0, 0, 0, if (report.cost != null && selected in Revenue.day(cycleStart) until cycleEnd) 0 else null, 0)
    val monthRevenue = remember(report, month, nextMonth) { report.days.filter { it.day >= month.timeInMillis && it.day < nextMonth }.sumOf { it.revenue } }
    val dayEnd = remember(selected) { Calendar.getInstance().apply { timeInMillis = selected; add(Calendar.DAY_OF_MONTH, 1) }.timeInMillis }
    val daySessions = remember(sessions, selected) { sessions.filter { !it.home && it.recognized in selected until dayEnd } }
    val dayManual = remember(manual, selected) { manual.filter { it.at in selected until dayEnd } }
    val entries = remember(ledger, selected, dayEnd) { ledger.filter { it.at in selected until dayEnd } }
    var details by rememberSaveable { mutableStateOf(false) }
    fun label(time: Long, pattern: String) = SimpleDateFormat(pattern, Locale.forLanguageTag("ar")).format(Date(time))
    editing?.let { entry -> RevenueEditForm(entry, { editing = null }) { amount, count, reason -> correct(entry.id, amount, count, false, reason); editing = null } }
    removing?.let { entry -> AlertDialog(onDismissRequest = { removing = null }, title = { Text("حذف هذا الإيراد؟") }, text = { Text("سيُستبعد ${amount(entry.amount)} من يومه الأصلي ويُعاد حساب الفائض والديون. يمكنك استعادته لاحقًا من السجل؛ الاشتراك نفسه لا يُلغى.") },
        confirmButton = { Button(onClick = { correct(entry.id, entry.amount, entry.count, true, "حذف قيد أضيف بالخطأ"); removing = null }) { Text("تأكيد الحذف") } }, dismissButton = { TextButton(onClick = { removing = null }) { Text("إلغاء") } }) }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth().fillMaxHeight(.94f).padding(8.dp), shape = MaterialTheme.shapes.extraLarge) {
            LazyColumn(Modifier.testTag("history-calendar"), contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(start = 8.dp)) { Text("سجل الأيام", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text("الشهر الحالي والشهر السابق", style = MaterialTheme.typography.bodySmall) }
                    IconButton(onClick = dismiss) { Icon(Icons.Default.Close, "إغلاق السجل") }
                } }
                item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    IconButton(enabled = offset == 0, onClick = { offset = -1; selected = (month.clone() as Calendar).apply { add(Calendar.MONTH, -1) }.timeInMillis }) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "الشهر السابق") }
                    Text(label(month.timeInMillis, "MMMM yyyy"), fontWeight = FontWeight.Bold)
                    IconButton(enabled = offset == -1, onClick = { offset = 0; selected = today }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "الشهر الحالي") }
                } }
                item { Column {
                    Row { listOf("أحد", "اثن", "ثلا", "أرب", "خمي", "جمع", "سبت").forEach { Text(it, Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.labelSmall) } }
                    val leading = month.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY
                    val count = month.getActualMaximum(Calendar.DAY_OF_MONTH)
                    repeat((leading + count + 6) / 7) { week -> Row {
                        repeat(7) { weekday ->
                            val number = week * 7 + weekday - leading + 1
                            if (number !in 1..count) Spacer(Modifier.weight(1f).height(48.dp))
                            else {
                                val at = (month.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, number) }.timeInMillis
                                TextButton(onClick = { selected = at }, enabled = at <= today,
                                    modifier = Modifier.weight(1f).height(48.dp).testTag("calendar-day-$number"),
                                    contentPadding = PaddingValues(0.dp), shape = CircleShape,
                                    colors = ButtonDefaults.textButtonColors(containerColor = if (at == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                                        contentColor = if (at == selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(number.toString(), fontWeight = if (at == today) FontWeight.Bold else FontWeight.Normal)
                                        if ((byDay[at]?.revenue ?: 0) > 0) Box(Modifier.size(4.dp).background(if (at == selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary, CircleShape))
                                    }
                                }
                            }
                        }
                    } }
                } }
                item { Text("إيراد الشهر · ${amount(monthRevenue)}", Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.labelLarge) }
                item { Panel {
                    Text(label(selected, "EEEE، d MMMM yyyy"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    MoneyLine("إيراد اليوم · قيمة كاش", daily.revenue, "selected-day-revenue", true)
                    TextButton(onClick = { details = !details }) { Text(if (details) "إخفاء توزيع اليوم" else "تفاصيل توزيع اليوم") }
                    if (details) BudgetSummary(budget.day(selected))
                    Text("يمكن تعديل أو حذف قيود هذا اليوم، حتى بعد انتهاء اليوم.", style = MaterialTheme.typography.bodySmall)
                    Text("${daily.sales} اشتراكًا · ${daySessions.size} من المؤقتات · ${dayManual.sumOf { it.count.toLong() }} جهازًا بإدخال يدوي", style = MaterialTheme.typography.bodySmall)
                } }
                items(entries, key = { it.id }) { entry -> LedgerCard(entry, { editing = it }, { removing = it }) }
            }
        }
    }
}
