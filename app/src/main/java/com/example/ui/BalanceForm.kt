package com.example.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.example.domain.*

@Composable internal fun BalanceForm(snapshot: FinancialSnapshot, dismiss: () -> Unit, save: (Long, Long, String) -> Unit) {
    var cash by rememberSaveable { mutableStateOf(snapshot.balance?.funds?.cash?.let(Money::show).orEmpty()) }
    var bank by rememberSaveable { mutableStateOf(snapshot.balance?.funds?.bank?.let(Money::show).orEmpty()) }
    var reason by rememberSaveable { mutableStateOf("تحديث الرصيد الموجود") }
    val expected = remember(snapshot) {
        BalanceBook.expected(snapshot.data.balanceUpdates, BalanceBook.receipts(snapshot.data.sessions, snapshot.data.sales),
            System.currentTimeMillis(), snapshot.data.config.cycleStart)
    }
    val cashAmount = Money.parse(cash)
    val bankAmount = Money.parse(bank)
    Form("تحديث الرصيد الموجود", dismiss, { save(cashAmount!!, bankAmount!!, reason.trim()) },
        cashAmount != null && bankAmount != null && reason.trim().length in 1..200) {
        Text("اكتب إجمالي القروش المتوفرة الآن من حصيلة الشهر. هذا الرصيد يحل محل الرصيد المحسوب، ولا يضاف إلى الإيرادات.")
        Field("الكاش الموجود الآن · ج.س", cash, { cash = it }, numeric = true)
        Field("بنكك الموجود الآن · ج.س", bank, { bank = it }, numeric = true)
        Field("سبب التحديث · سحب أو مصروف أو تصحيح", reason, { reason = it })
        if (cashAmount != null && bankAmount != null) {
            val funds = CashBank(cashAmount, bankAmount).equivalent(snapshot.data.config.premiumBps)
            Text("إجمالي الموجود بمكافئ الكاش: ${amount(funds)}")
            Text(balanceDifference("الكاش", cashAmount - expected.cash))
            Text(balanceDifference("بنكك", bankAmount - expected.bank))
            snapshot.revenue.cost?.let { cost ->
                val remaining = (cost - funds).coerceAtLeast(0)
                Text("المتبقي لتغطية الدورة: ${amount(remaining)} كاش")
                Text("بنكك المكافئ: ${amount(Money.cashToBank(remaining, snapshot.data.config.premiumBps))}")
            }
        }
        Text("الحفظ يعيد حساب هدف اليوم من الرصيد الجديد. سجل المبيعات والربح المحاسبي محفوظان. التحويل تقديري حسب نسبة بنكك في الإعدادات.", style = MaterialTheme.typography.bodySmall)
    }
}

internal fun balanceDifference(label: String, difference: Long): String = when {
    difference < 0 -> "$label: نقص ${amount(-difference)} عن المحسوب"
    difference > 0 -> "$label: زيادة ${amount(difference)} عن المحسوب"
    else -> "$label: مطابق للمحسوب"
}
