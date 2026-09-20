package com.example.data

import com.example.domain.Money
import com.example.domain.TextRules
import org.json.JSONObject

/** Strict, bounded schema; never execute table names or SQL from an imported file. */
data class BackupData(val rows: Map<String, List<JSONObject>>, val apps: Set<String>, val exportedAt: Long) {
    val summary: String get() = "${rows.getValue("sessions").size} اشتراك · ${rows.getValue("manual_sales").size} قيد دخل · ${rows.getValue("shortcuts").size} اختصار"
    companion object {
        const val MAX_BYTES = 20 * 1024 * 1024
        val tables = listOf("plans", "sessions", "settings", "shortcuts", "devices", "sequences", "manual_sales", "revenue_corrections", "billing_cycles", "debts", "debt_payments", "balance_updates")
        private val fields = mapOf(
            "plans" to "id name minutes cash bank home enabled",
            "sessions" to "id client plan started resumed duration served state amount cashEquivalent payment premiumBps home grace recognized warned notified source reference",
            "settings" to "id graceMinutes premiumBps usdCents bankRate cycleStart cycleEnd expenses maxSubscribers cycleId",
            "shortcuts" to "id keyword phrase planId payment enabled",
            "devices" to "id ip name endTime isPaused remainingWhenPaused",
            "sequences" to "name next",
            "manual_sales" to "id at count unitPrice amount cashEquivalent payment premiumBps",
            "revenue_corrections" to "id source amount cashEquivalent count voided at reason",
            "billing_cycles" to "id start end cost",
            "debts" to "id name total start due",
            "debt_payments" to "id debtId at amount",
            "balance_updates" to "id at cash bank cashReceived bankReceived premiumBps reason")
        private val strings = setOf("name", "client", "plan", "state", "payment", "source", "reference", "keyword", "phrase", "ip", "cycleId", "debtId", "reason")
        fun parse(text: String): BackupData {
            require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "النسخة أكبر من 20 ميجابايت" }
            val root = JSONObject(text)
            val version = root.getInt("version")
            require(version in 2..6) { "إصدار النسخة الاحتياطية غير مدعوم" }
            val rows = tables.associateWith { table ->
                val array = if (table == "balance_updates" && version < 6 || table == "manual_sales" && version < 4 || table == "sequences" && version < 3 || table in listOf("revenue_corrections", "billing_cycles", "debts", "debt_payments") && version < 5)
                    org.json.JSONArray() else root.getJSONArray(table)
                require(array.length() <= 100000) { "عدد السجلات يتجاوز الحد" }
                val keys = fields.getValue(table).split(' ').toSet()
                List(array.length()) { index -> array.getJSONObject(index).also { row ->
                    if (table == "settings" && version < 5) row.put("cycleId", "")
                    if (table == "settings" && version < 4) row.put("maxSubscribers", 50)
                    if (table == "sessions" && version < 3) row.put("reference", "")
                    require(row.keys().asSequence().toSet() == keys) { "حقول غير صالحة في $table" }
                    keys.forEach { key ->
                        val isString = key in strings || key == "id" && table in listOf("sessions", "manual_sales", "billing_cycles", "debts", "debt_payments")
                        if (row.isNull(key)) require(table == "shortcuts" && key == "planId")
                        else if (isString) require(row.get(key) is String && row.getString(key).length <= if (key == "phrase") 10000 else 200) { "نص غير صالح" }
                        else require(row.get(key) is Number && Regex("[0-9]+").matches(row.get(key).toString()) && row.get(key).toString().toLongOrNull() != null) { "رقم غير صالح" }
                    }
                    fun range(key: String, max: Long, min: Long = 0) { require(row.getLong(key) in min..max) { "قيمة $key غير صالحة" } }
                    listOf("home", "enabled", "warned", "notified", "isPaused", "voided").filter { it in keys }.forEach { range(it, 1) }
                    if ("payment" in keys) require(row.getString("payment") in listOf("CASH", "BANK"))
                    if ("premiumBps" in keys) range("premiumBps", 100000)
                    listOf("started", "resumed", "recognized", "at", "cycleStart", "cycleEnd", "endTime", "start", "end", "due").filter { it in keys }.forEach { range(it, 32503680000000) }
                    listOf("cash", "bank", "usdCents", "bankRate", "expenses", "unitPrice", "total").filter { it in keys }.forEach { range(it, 99999999999) }
                    listOf("amount", "cashEquivalent").filter { it in keys }.forEach { range(it, 9999999999900000) }
                    when (table) {
                        "balance_updates" -> {
                            range("id", Long.MAX_VALUE, 1); range("at", 32503680000000, 1)
                            range("cashReceived", Long.MAX_VALUE); range("bankReceived", Long.MAX_VALUE)
                            require(row.getString("reason").isNotBlank())
                        }
                        "billing_cycles" -> { range("cost", 9999999999999999); require(row.getLong("start") > 0 && row.getLong("end") > row.getLong("start")) }
                        "debts" -> { require(row.getString("name").isNotBlank()); range("total", 99999999999, 1); require(row.getLong("start") > 0 && row.getLong("due") >= row.getLong("start")) }
                        "debt_payments" -> { range("amount", 99999999999, 1) }
                        "revenue_corrections" -> { range("count", 100000, 1); require(row.getString("reason").isNotBlank()) }

                        "plans" -> { range("id", Long.MAX_VALUE, 1); range("minutes", 525600, 1); require(row.getString("name").isNotBlank()) }
                        "settings" -> {
                            range("id", 1, 1); range("graceMinutes", 1440); range("maxSubscribers", 10000, 1)
                            val start = row.getLong("cycleStart"); val end = row.getLong("cycleEnd")
                            require(start == 0L && end == 0L || start > 0 && end > start)
                            Math.addExact(Money.bankToCash(Money.bill(row.getLong("usdCents"), row.getLong("bankRate")), row.getInt("premiumBps")), row.getLong("expenses"))
                        }
                        "sessions" -> {
                            require(row.getString("id").isNotBlank() && row.getString("client").isNotBlank())
                            require(row.getString("state") in listOf("ACTIVE", "PAUSED", "ENDED", "CANCELLED"))
                            range("duration", 31536000000, 60000); range("served", row.getLong("duration")); range("grace", 86400000)
                            require(row.getInt("home") == 0 || row.getLong("amount") == 0L && row.getLong("cashEquivalent") == 0L)
                        }
                        "shortcuts" -> { require(TextRules.validKeyword(row.getString("keyword"))); require(row.getString("phrase").isNotBlank()) }
                        "manual_sales" -> {
                            range("count", 100000, 1); range("unitPrice", 99999999999, 1)
                            require(row.getLong("amount") == Math.multiplyExact(row.getLong("count"), row.getLong("unitPrice")))
                            require(row.getLong("cashEquivalent") == if (row.getString("payment") == "BANK") Money.bankToCash(row.getLong("amount"), row.getInt("premiumBps")) else row.getLong("amount"))
                        }
                    }
                } }.also { list ->
                    val primary = if (table == "sequences") "name" else "id"
                    require(list.map { it.get(primary).toString() }.distinct().size == list.size) { "سجلات مكررة في النسخة" }
                }
            }
            require(rows.getValue("settings").size == 1) { "إعدادات النسخة ناقصة" }
            val plans = rows.getValue("plans").map { it.getLong("id") }.toSet()
            require(rows.getValue("shortcuts").all { it.isNull("planId") || it.getLong("planId") in plans }) { "باقة الاختصار غير موجودة" }
            require(rows.getValue("shortcuts").map { it.getString("keyword") }.distinct().size == rows.getValue("shortcuts").size)
            val active = rows.getValue("sessions").filter { it.getString("state") in listOf("ACTIVE", "PAUSED") }.mapNotNull { it.getString("reference").toIntOrNull() }
            require(active.distinct().size == active.size) { "أرقام المشتركين النشطة مكررة" }
            (rows.getValue("sessions") + rows.getValue("manual_sales")).fold(0L) { total, row -> Math.addExact(total, row.getLong("amount")) }
            val receipts = rows.getValue("sessions").filter { it.getInt("home") == 0 } + rows.getValue("manual_sales")
            val receiptCash = receipts.filter { it.getString("payment") == "CASH" }.sumOf { it.getLong("amount") }
            val receiptBank = receipts.filter { it.getString("payment") == "BANK" }.sumOf { it.getLong("amount") }
            rows.getValue("balance_updates").forEach {
                require(it.getLong("cashReceived") <= receiptCash && it.getLong("bankReceived") <= receiptBank) { "مرجع الرصيد لا يطابق التحصيلات" }
            }
            val debtIds = rows.getValue("debts").associateBy { it.getString("id") }
            require(rows.getValue("debt_payments").all { it.getString("debtId") in debtIds }) { "دين غير موجود في النسخة" }
            debtIds.forEach { (id, debt) -> require(rows.getValue("debt_payments").filter { it.getString("debtId") == id }.sumOf { it.getLong("amount") } <= debt.getLong("total")) }
            val sources = rows.getValue("sessions").associateBy { "session:${it.getString("id")}" } + rows.getValue("manual_sales").associateBy { "manual:${it.getString("id")}" }
            rows.getValue("revenue_corrections").forEach { correction ->
                val original = requireNotNull(sources[correction.getString("source")]) { "مصدر التصحيح غير موجود" }
                val value = if (original.getString("payment") == "BANK") Money.bankToCash(correction.getLong("amount"), original.getInt("premiumBps")) else correction.getLong("amount")
                require(value == correction.getLong("cashEquivalent"))
            }
            val cycles = rows.getValue("billing_cycles").sortedBy { it.getLong("start") }
            require(cycles.zipWithNext().none { (a, b) -> a.getLong("end") > b.getLong("start") }) { "دورات متداخلة في النسخة" }
            val currentCycle = rows.getValue("settings").single().getString("cycleId")
            require(currentCycle.isBlank() || cycles.any { it.getString("id") == currentCycle }) { "الدورة الحالية غير موجودة في النسخة" }
            val appsArray = root.optJSONArray("allowedApps") ?: org.json.JSONArray()
            require(appsArray.length() <= 1000)
            val apps = List(appsArray.length()) { appsArray.getString(it).also { app -> require(app.length <= 200 && Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+").matches(app)) } }.toSet()
            return BackupData(rows, apps, root.getLong("exportedAt"))
        }
    }
}
