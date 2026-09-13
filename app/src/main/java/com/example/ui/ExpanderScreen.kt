package com.example.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.example.db.Plan
import com.example.db.Shortcut
import com.example.domain.TextRules

@Composable fun ShortcutsScreen(shortcuts: List<Shortcut>, plans: List<Plan>, busy: Boolean,
    save: (Shortcut) -> Unit, delete: (Shortcut) -> Unit) {
    var editing by remember { mutableStateOf<Shortcut?>(null) }
    var deleting by remember { mutableStateOf<Shortcut?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Title("الاختصارات", "قوالب نصية وتسجيل اشتراكات بكلمة ومسافة") }
        item { Panel {
            Text("اكتب الاختصار ثم مسافة فقط، مثل س3. إن كان مرتبطًا بباقة يُسجّل مشتركًا برقم تلقائي. إضافة الاسم اختيارية: س3/محمد أو س3/محمد_أحمد.")
            Text("اختر التطبيقات المسموحة وفعّل الخدمة من الإعدادات. الاختصار يسجّل بداية الوقت عند استبدال النص، وليس عند اتصال الجهاز أو إرسال الرسالة.")
        } }
        item { Button(enabled = !busy, onClick = { editing = Shortcut(keyword = "", phrase = "") }) { Text("إضافة اختصار") } }
        item { Field("بحث في الاختصارات", search, { search = it }) }
        val rows = shortcuts.filter { it.keyword.contains(search, true) || it.phrase.contains(search, true) }
        if (rows.isEmpty()) item { Text("لا توجد اختصارات مطابقة. أضف اختصارًا جديدًا.") }
        items(rows, key = { it.id }) { s -> Panel {
            Text(s.keyword, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            Text(s.phrase)
            Text(if (s.planId == null) "نص فقط · لا يسجّل إيرادًا" else "باقة: ${plans.find { it.id == s.planId }?.name ?: "غير متاحة"} · ${if (s.payment == "BANK") "بنكك" else "كاش"}")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(s.enabled, { save(s.copy(enabled = it)) }, enabled = !busy, modifier = Modifier.semantics { contentDescription = "تفعيل الاختصار ${s.keyword}" })
                Text(if (s.enabled) "مفعّل" else "متوقف")
                TextButton(enabled = !busy, onClick = { editing = s }) { Text("تعديل") }
                TextButton(enabled = !busy, onClick = { deleting = s }) { Text("حذف") }
            }
        } }
    }
    editing?.let { s -> ShortcutForm(s, plans, shortcuts, { editing = null }) { save(it); editing = null } }
    deleting?.let { s -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("حذف ${s.keyword}؟") }, text = { Text("سيُحذف الاختصار، وتبقى الاشتراكات التي سجّلها محفوظة.") }, confirmButton = {
        Button(onClick = { delete(s); deleting = null }) { Text("حذف") }
    }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("رجوع") } }) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun ShortcutForm(shortcut: Shortcut, plans: List<Plan>, existing: List<Shortcut>, dismiss: () -> Unit, save: (Shortcut) -> Unit) {
    var keyword by rememberSaveable { mutableStateOf(shortcut.keyword) }
    var phrase by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(shortcut.phrase)) }
    var futureTime by remember { mutableStateOf(false) }
    fun insert(tag: String) {
        val from = phrase.selection.min; val to = phrase.selection.max
        phrase = TextFieldValue(phrase.text.replaceRange(from, to, tag), TextRange(from + tag.length))
    }
    var planId by rememberSaveable { mutableStateOf(shortcut.planId) }
    var payment by rememberSaveable { mutableStateOf(shortcut.payment) }
    val duplicate = existing.any { it.id != shortcut.id && it.keyword == keyword.trim() }
    val valid = TextRules.validKeyword(keyword.trim()) && phrase.text.isNotBlank() && phrase.text.length <= 10000 && !duplicate
    Form(if (shortcut.id == 0) "إضافة اختصار جديد" else "تعديل الاختصار", dismiss, {
        save(shortcut.copy(keyword = keyword.trim(), phrase = phrase.text, planId = planId, payment = payment))
    }, valid) {
        Field("الكلمة المفتاحية (مثل mn)", keyword, { keyword = it })
        if (duplicate) Text("هذه الكلمة مستخدمة؛ اختر كلمة أخرى.", color = MaterialTheme.colorScheme.error)
        OutlinedTextField(phrase, { phrase = it }, modifier = Modifier.fillMaxWidth(), label = { Text("النص الممتد") }, minLines = 3)
        Text("إضافة جاهزة إلى النص", style = MaterialTheme.typography.titleSmall)
        Text("ضع المؤشر في النص ثم اختر العنصر. تُستبدل الرموز بالقيم عند استخدام الاختصار.", style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Triple("الاسم", "%client%", Icons.Default.Person), Triple("رقم الاشتراك", "%code%", Icons.Default.Tag),
                Triple("وقت النهاية", "%end%", Icons.Default.Alarm), Triple("المدة", "%duration%", Icons.Default.HourglassTop),
                Triple("السعر", "%price%", Icons.Default.Payments), Triple("الوقت الآن", "%time%", Icons.Default.Schedule),
                Triple("التاريخ", "%date%", Icons.Default.Event), Triple("اليوم", "%day%", Icons.Default.Today)).forEach { (label, tag, icon) ->
                AssistChip(onClick = { insert(tag) }, label = { Text(label) }, leadingIcon = { Icon(icon, null, Modifier.size(18.dp)) })
            }
            AssistChip(onClick = { futureTime = true }, label = { Text("وقت بعد مدة…") }, leadingIcon = { Icon(Icons.Default.MoreTime, null, Modifier.size(18.dp)) })
        }
        Text("الاسم التلقائي ورقم الاشتراك والمدة والسعر والنهاية تخص الاختصارات المرتبطة بباقة.", style = MaterialTheme.typography.bodySmall)
        Text("نوع الاختصار", style = MaterialTheme.typography.titleSmall)
        Choice("نص فقط", planId == null) { planId = null }
        plans.filter { it.enabled || it.id == planId }.forEach { p -> Choice("${if (p.home) "✅ " else ""}${p.name}", planId == p.id) { planId = p.id } }
        if (planId != null) Payment(payment) { payment = it }
        Text("معاينة النص", style = MaterialTheme.typography.labelLarge)
        val plan = plans.find { it.id == planId }
        Text(TextRules.render(phrase.text, System.currentTimeMillis(), "محمد", System.currentTimeMillis() + (plan?.minutes ?: 0) * 60000L,
            plan?.let { com.example.domain.Money.show(if (payment == "BANK") it.bank else it.cash) }.orEmpty(), plan?.minutes?.toString().orEmpty(), code = if (planId != null) "001" else ""))
        if (planId != null) Text("الاسم اختياري عند الكتابة. مثال: اكتب ${keyword.ifBlank { "mm" }} ثم مسافة؛ سيظهر الاسم التلقائي ورقم الاشتراك في سجل المشتركين والتنبيه.", style = MaterialTheme.typography.bodySmall)
    }
    if (futureTime) FutureTimeForm({ futureTime = false }) { insert(it); futureTime = false }
}

/** Text-only entry point also covered by the UI regression tests. */
@Composable fun AddShortcutDialog(onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    ShortcutForm(Shortcut(keyword = "", phrase = ""), emptyList(), emptyList(), onDismiss) { onSave(it.keyword, it.phrase) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun FutureTimeForm(dismiss: () -> Unit, insert: (String) -> Unit) {
    var hours by rememberSaveable { mutableStateOf("1") }
    val parsed = com.example.domain.Money.parse(hours)
    Form("إضافة وقت بعد مدة", dismiss, { insert("%time+${com.example.domain.Money.show(parsed!!)}h%") }, parsed != null && parsed in 1..876000) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("ربع ساعة" to "0.25", "نصف ساعة" to "0.5", "ساعة" to "1", "ساعتان" to "2", "3 ساعات" to "3").forEach { (label, value) ->
                Choice(label, hours == value) { hours = value }
            }
        }
        Field("الساعات · مثال 1.5 لساعة ونصف", hours, { hours = it })
        Text("يُحسب هذا الوقت من لحظة استخدام الاختصار. لتوقيت نهاية الباقة الحقيقي اختر «وقت النهاية».")
    }
}
