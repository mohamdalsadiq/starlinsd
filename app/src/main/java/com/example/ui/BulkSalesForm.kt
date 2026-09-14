package com.example.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.domain.Money
import java.util.UUID

@Composable internal fun BulkSalesForm(dismiss: () -> Unit, save: (String, List<Pair<Int, Long>>, String) -> Unit) {
    val id = rememberSaveable { UUID.randomUUID().toString() }
    var counts by rememberSaveable { mutableStateOf(listOf("", "")) }
    var prices by rememberSaveable { mutableStateOf(listOf("500", "1000")) }
    val parsed = counts.indices.map { i -> (if (counts[i].isBlank()) 0 else Money.normalize(counts[i]).toIntOrNull()) to Money.parse(prices[i]) }
    val valid = parsed.all { (n, price) -> n != null && n in 0..100000 && price != null && price > 0 } && parsed.any { (n, _) -> n != null && n > 0 }
    val total = if (valid) parsed.sumOf { (n, price) -> n!!.toLong() * price!! } else 0L
    Form("دخل بعدد الأجهزة", dismiss, {
        save(id, parsed.filter { it.first!! > 0 }.map { it.first!! to it.second!! }, "CASH")
    }, valid) {
        Text("اكتب عدد الأجهزة عند كل سعر. يُضاف المبلغ فورًا إلى دخل اليوم مع دخل الاختصارات، دون إنشاء مؤقتات.")
        counts.indices.forEach { i ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) { Field("العدد ${i + 1}", counts[i], { value -> counts = counts.toMutableList().also { it[i] = value } }, numeric = true) }
                Column(Modifier.weight(1f)) { Field("سعر الجهاز · ج.س", prices[i], { value -> prices = prices.toMutableList().also { it[i] = value } }, numeric = true) }
            }
            if ((parsed[i].first ?: 0) > 0 && parsed[i].second != null) Text("${counts[i]} × ${amount(parsed[i].second!!)} = ${amount(parsed[i].first!!.toLong() * parsed[i].second!!)}")
        }
        if (counts.size < 20) TextButton(onClick = { counts = counts + ""; prices = prices + "500" }) { Icon(Icons.Default.Add, null); Text("إضافة سعر آخر") }
        HorizontalDivider()
        MoneyLine("إجمالي الدخل الذي سيُضاف", total, "bulk-total", strong = true)
        Text("تأكد من العدد والسعر قبل الحفظ؛ العملية تُثبت كإيراد مباشرة.", style = MaterialTheme.typography.bodySmall)
    }
}
