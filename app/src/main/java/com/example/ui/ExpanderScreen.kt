package com.example.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
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
        item { Title("اكتب أقل، أنجز أكثر", "اختصارات نصية أو اختصارات تسجّل الاشتراك تلقائيًا") }
        item { Panel {
            Text("اكتب س3/محمد ثم مسافة لتسجيل محمد في باقة 3 ساعات. للأسماء المركبة استخدم محمد_أحمد.")
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

@Composable internal fun ShortcutForm(shortcut: Shortcut, plans: List<Plan>, existing: List<Shortcut>, dismiss: () -> Unit, save: (Shortcut) -> Unit) {
    var keyword by rememberSaveable { mutableStateOf(shortcut.keyword) }
    var phrase by rememberSaveable { mutableStateOf(shortcut.phrase) }
    var planId by rememberSaveable { mutableStateOf(shortcut.planId) }
    var payment by rememberSaveable { mutableStateOf(shortcut.payment) }
    val duplicate = existing.any { it.id != shortcut.id && it.keyword == keyword.trim() }
    val valid = TextRules.validKeyword(keyword.trim()) && phrase.isNotBlank() && phrase.length <= 10000 && !duplicate
    Form(if (shortcut.id == 0) "إضافة اختصار جديد" else "تعديل الاختصار", dismiss, {
        save(shortcut.copy(keyword = keyword.trim(), phrase = phrase, planId = planId, payment = payment))
    }, valid) {
        Field("الكلمة المفتاحية (مثل mn)", keyword, { keyword = it })
        if (duplicate) Text("هذه الكلمة مستخدمة؛ اختر كلمة أخرى.", color = MaterialTheme.colorScheme.error)
        Field("النص الممتد", phrase, { phrase = it }, single = false)
        Text("يمكنك إدراج: %client% الاسم، %end% النهاية، %price% السعر، %duration% الدقائق، %date% التاريخ، %day% اليوم، %time% الوقت، %time+1.5h% الوقت بعد ساعة ونصف.")
        Choice("نص فقط", planId == null) { planId = null }
        plans.filter { it.enabled || it.id == planId }.forEach { p -> Choice("${if (p.home) "✅ " else ""}${p.name}", planId == p.id) { planId = p.id } }
        if (planId != null) Payment(payment) { payment = it }
        Text("معاينة النص", style = MaterialTheme.typography.labelLarge)
        val plan = plans.find { it.id == planId }
        Text(TextRules.render(phrase, System.currentTimeMillis(), "محمد", System.currentTimeMillis() + (plan?.minutes ?: 0) * 60000L,
            plan?.let { com.example.domain.Money.show(if (payment == "BANK") it.bank else it.cash) }.orEmpty(), plan?.minutes?.toString().orEmpty()))
    }
}

/** Text-only entry point also covered by the UI regression tests. */
@Composable fun AddShortcutDialog(onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    ShortcutForm(Shortcut(keyword = "", phrase = ""), emptyList(), emptyList(), onDismiss) { onSave(it.keyword, it.phrase) }
}
