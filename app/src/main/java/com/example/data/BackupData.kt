package com.example.data

import com.example.domain.Money
import com.example.domain.TextRules
import org.json.JSONObject

/** Strict, bounded schema; never execute table names or SQL from an imported file. */
data class BackupData(val rows: Map<String, List<JSONObject>>, val apps: Set<String>, val exportedAt: Long) {
    val summary: String get() = "${rows.getValue("sessions").size} اشتراك · ${rows.getValue("manual_sales").size} قيد دخل · ${rows.getValue("shortcuts").size} اختصار"
    companion object {
        const val MAX_BYTES = 20 * 1024 * 1024
        val tables = listOf("plans", "sessions", "settings", "shortcuts", "devices", "sequences", "manual_sales")
        private val fields = mapOf(
            "plans" to "id name minutes cash bank home enabled",
            "sessions" to "id client plan started resumed duration served state amount cashEquivalent payment premiumBps home grace recognized warned notified source reference",
            "settings" to "id graceMinutes premiumBps usdCents bankRate cycleStart cycleEnd expenses maxSubscribers",
            "shortcuts" to "id keyword phrase planId payment enabled",
            "devices" to "id ip name endTime isPaused remainingWhenPaused",
            "sequences" to "name next",
            "manual_sales" to "id at count unitPrice amount cashEquivalent payment premiumBps")
        private val strings = setOf("name", "client", "plan", "state", "payment", "source", "reference", "keyword", "phrase", "ip")
        fun parse(text: String): BackupData {
            require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "النسخة أكبر من 20 ميجابايت" }
            val root = JSONObject(text)
            val version = root.getInt("version")
            require(version in 2..4) { "إصدار النسخة الاحتياطية غير مدعوم" }
            val rows = tables.associateWith { table ->
                val array = if (table == "manual_sales" && version < 4 || table == "sequences" && version < 3)
                    org.json.JSONArray() else root.getJSONArray(table)
                require(array.length() <= 100000) { "عدد السجلات يتجاوز الحد" }
                val keys = fields.getValue(table).split(' ').toSet()
                List(array.length()) { index -> array.getJSONObject(index).also { row ->
                    if (table == "settings" && version < 4) row.put("maxSubscribers", 50)
                    if (table == "sessions" && version < 3) row.put("reference", "")
                    require(row.keys().asSequence().toSet() == keys) { "حقول غير صالحة في $table" }
                    keys.forEach { key ->
                        val isString = key in strings || key == "id" && table in listOf("sessions", "manual_sales")
                        if (row.isNull(key)) require(table == "shortcuts" && key == "planId")
                        else if (isString) require(row.get(key) is String && row.getString(key).length <= if (key == "phrase") 10000 else 200) { "نص غير صالح" }
                        else require(row.get(key) is Number && Regex("[0-9]+").matches(row.get(key).toString()) && row.get(key).toString().toLongOrNull() != null) { "رقم غير صالح" }
                    }
                    fun range(key: String, max: Long, min: Long = 0) { require(row.getLong(key) in min..max) { "قيمة $key غير صالحة" } }
                    listOf("home", "enabled", "warned", "notified", "isPaused").filter { it in keys }.forEach { range(it, 1) }
                    if ("payment" in keys) require(row.getString("payment") in listOf("CASH", "BANK"))
                    if ("premiumBps" in keys) range("premiumBps", 100000)
                    listOf("started", "resumed", "recognized", "at", "cycleStart", "cycleEnd", "endTime").filter { it in keys }.forEach { range(it, 32503680000000) }
                    listOf("cash", "bank", "usdCents", "bankRate", "expenses", "unitPrice").filter { it in keys }.forEach { range(it, 99999999999) }
                    listOf("amount", "cashEquivalent").filter { it in keys }.forEach { range(it, 9999999999900000) }
                    when (table) {
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
            val appsArray = root.optJSONArray("allowedApps") ?: org.json.JSONArray()
            require(appsArray.length() <= 1000)
            val apps = List(appsArray.length()) { appsArray.getString(it).also { app -> require(app.length <= 200 && Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+").matches(app)) } }.toSet()
            return BackupData(rows, apps, root.getLong("exportedAt"))
        }
    }
}
