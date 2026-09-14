package com.example.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.db.*
import com.example.domain.*
import java.text.SimpleDateFormat
import java.util.*

@Composable internal fun BudgetSummary(day: BudgetDay) {
    if (day.billTarget == null) { Text("اضبط دورة الفاتورة لحساب المخصص والفائض."); return }
    MoneyLine("نصيب الفاتورة اليومي · ثابت", day.billTarget, "daily-bill-target")
    if (day.shortfall > 0) Text("ناقص عن نصيب اليوم: ${amount(day.shortfall)}", color = MaterialTheme.colorScheme.error)
    MoneyLine("فائض اليوم بعد نصيب الفاتورة", day.surplus ?: 0, "today-surplus", true)
    if (day.debtReserved > 0) MoneyLine("خُصص لسداد الديون", day.debtReserved)
    MoneyLine("المتاح لك بعد تخصيص الديون", day.available ?: 0, "today-profit", true)
    Text("الفائض اليومي يختلف عن صافي الدورة بعد دفع كامل الفاتورة. التخصيص لا يعني أن الفاتورة أو الدين دُفع بالفعل.", style = MaterialTheme.typography.bodySmall)
}

@Composable internal fun LedgerCard(entry: LedgerEntry, edit: (LedgerEntry) -> Unit, remove: (LedgerEntry) -> Unit) {
    Panel {
        Text(entry.label, style = MaterialTheme.typography.titleMedium)
        Text("${stamp(entry.at)} · ${entry.count} جهاز · ${if (entry.bank) "بنكك" else "كاش"}", style = MaterialTheme.typography.bodySmall)
        MoneyLine(if (entry.voided) "محذوف من الحساب · الأصل محفوظ" else "المبلغ المسجّل", entry.amount)
        if (entry.corrected) Text("تم تصحيح هذا القيد؛ لا يتغيّر وقت الاشتراك أو سعر الباقة الأصلي.", style = MaterialTheme.typography.bodySmall)
        Row {
            TextButton(onClick = { edit(entry) }) { Icon(Icons.Default.Edit, null); Text(if (entry.voided) "تعديل واستعادة" else "تعديل الإيراد") }
            if (!entry.voided) TextButton(onClick = { remove(entry) }) { Icon(Icons.Default.DeleteOutline, null); Text("حذف الإيراد") }
        }
    }
}
@Composable internal fun RevenueEditForm(entry: LedgerEntry, dismiss: () -> Unit, save: (Long, Int, String) -> Unit) {
    var value by rememberSaveable { mutableStateOf(Money.show(entry.amount)) }
    var count by rememberSaveable { mutableStateOf(entry.count.toString()) }
    var reason by rememberSaveable { mutableStateOf("") }
    val amount = Money.parse(value); val n = Money.normalize(count).toIntOrNull()
    Form("تصحيح الإيراد", dismiss, { save(amount!!, n!!, reason.trim()) }, amount != null && n != null && n in 1..100000 && reason.trim().length in 1..200) {
        Text("التصحيح يُنسب إلى اليوم الأصلي، ويعيد حساب الشهر والفائض والديون. لا يعيد تشغيل مؤقت الاشتراك.")
        Field("المبلغ الكلي الصحيح · ${if (entry.bank) "بنكك" else "كاش"}", value, { value = it })
        if (entry.id.startsWith("manual:")) Field("عدد الأجهزة الصحيح", count, { count = it })
        Field("سبب التعديل", reason, { reason = it })
    }
}

@Composable internal fun DebtsDialog(debts: List<Debt>, payments: List<DebtPayment>, report: BudgetReport, now: Long, busy: Boolean,
    save: (Debt) -> Unit, pay: (String, String, Long) -> Unit, dismiss: () -> Unit) {
    var editing by remember { mutableStateOf<Debt?>(null) }
    var paying by remember { mutableStateOf<DebtBalance?>(null) }
    Dialog(dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth().fillMaxHeight(.94f).padding(8.dp), shape = MaterialTheme.shapes.extraLarge) {
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item { Row { Text("الديون وخطة السداد", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge); IconButton(onClick = dismiss) { Icon(Icons.Default.Close, "إغلاق") } } }
                item { Text("يُخصص الفائض للديون الأقرب موعدًا أولًا، دون خصمه مرة ثانية عند تسجيل السداد. اضغط سداد فقط بعد دفع المبلغ فعليًا.", style = MaterialTheme.typography.bodySmall) }
                item { Button(enabled = !busy, onClick = { editing = Debt(UUID.randomUUID().toString(), "", 0, Revenue.day(now), Revenue.day(now) + 30 * 86400000L) }) { Icon(Icons.Default.Add, null); Text("إضافة دين") } }
                if (debts.isEmpty()) item { Text("لا توجد ديون مسجلة. فائضك يبقى متاحًا لك.") }
                items(report.debts, key = { it.debt.id }) { balance -> Panel {
                    val debt = balance.debt
                    SectionHeading(Icons.Default.AccountBalance, debt.name)
                    Text("من ${stamp(debt.start).substringBefore('·')} إلى ${stamp(debt.due).substringBefore('·')}")
                    if (Revenue.day(now) > Revenue.day(debt.due) && balance.remaining > 0) Text("تجاوز موعد السداد", color = MaterialTheme.colorScheme.error)
                    MoneyLine("قيمة الدين · كاش", debt.total)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) { MoneyLine("المسدّد فعليًا", balance.paid) }
                        Column(Modifier.weight(1f)) { MoneyLine("المتبقي", balance.remaining) }
                    }
                    MoneyLine("مخصص جاهز للسداد", balance.reserved, strong = true)
                    val deadline = Calendar.getInstance().apply { timeInMillis = Revenue.day(debt.due); add(Calendar.DAY_OF_MONTH, 1) }.timeInMillis
                    val days = Finance.days(maxOf(debt.start, Revenue.day(now)), maxOf(deadline, now + 1))
                    MoneyLine("المطلوب يوميًا حتى الموعد", (balance.remaining + days - 1) / days)
                    if (balance.fundingGap > 0) Text("عجز تغطية ${amount(balance.fundingGap)} بعد تعديل الدخل. لا يتغير السداد الذي سجلته.", color = MaterialTheme.colorScheme.error)
                    Row {
                        TextButton(enabled = !busy, onClick = { editing = debt }) { Text("تعديل الخطة") }
                        Button(enabled = !busy && balance.reserved > 0 && balance.remaining > 0, onClick = { paying = balance }) { Text("تسجيل سداد") }
                    }
                    payments.filter { it.debtId == debt.id }.forEach { Text("سداد ${amount(it.amount)} · ${stamp(it.at)}", style = MaterialTheme.typography.bodySmall) }
                } }
            }
        }
    }
    editing?.let { debt -> DebtForm(debt, { editing = null }) { save(it); editing = null } }
    paying?.let { balance ->
        val id = rememberSaveable(balance.debt.id) { UUID.randomUUID().toString() }
        var text by rememberSaveable(balance.debt.id) { mutableStateOf(Money.show(minOf(balance.reserved, balance.remaining))) }
        val value = Money.parse(text)
        Form("تأكيد سداد فعلي", { paying = null }, { pay(id, balance.debt.id, value!!); paying = null }, !busy && value != null && value > 0 && value <= balance.reserved && value <= balance.remaining) {
            Text("${balance.debt.name} · سجّل فقط مبلغًا دفعته فعليًا. لن يُخصم مرتين من الفائض.")
            Field("المبلغ المدفوع بقيمة الكاش", text, { text = it })
        }
    }
}
@Composable private fun DebtForm(debt: Debt, dismiss: () -> Unit, save: (Debt) -> Unit) {
    val f = remember { SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { isLenient = false } }
    var name by rememberSaveable { mutableStateOf(debt.name) }
    var total by rememberSaveable { mutableStateOf(Money.show(debt.total)) }
    var start by rememberSaveable { mutableStateOf(f.format(Date(debt.start))) }
    var due by rememberSaveable { mutableStateOf(f.format(Date(debt.due))) }
    fun date(text: String) = runCatching { val normalized = Money.normalize(text); require(Regex("\\d{4}-\\d{2}-\\d{2}").matches(normalized)); f.parse(normalized)!!.time }.getOrNull()
    val amount = Money.parse(total); val a = date(start); val b = date(due)
    Form("خطة الدين", dismiss, { save(debt.copy(name = name.trim(), total = amount!!, start = a!!, due = b!!)) }, name.trim().length in 1..80 && amount != null && amount > 0 && a != null && b != null && b >= a) {
        Field("اسم الدين أو صاحبه", name, { name = it })
        Field("إجمالي الدين بقيمة الكاش · ج.س", total, { total = it })
        Field("بداية التخصيص · yyyy-MM-dd", start, { start = it })
        Field("آخر يوم للسداد · yyyy-MM-dd", due, { due = it })
        Text("تغيير المبلغ أو الفترة يعيد توزيع الفائض من تاريخ البداية. السداد الفعلي السابق يبقى محفوظًا.", style = MaterialTheme.typography.bodySmall)
    }
}
