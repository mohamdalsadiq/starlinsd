package com.example.data

import android.content.Context
import androidx.room.withTransaction
import com.example.db.*
import com.example.domain.Money
import com.example.domain.Rules
import com.example.domain.TextRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

class SubscriptionRepository(private val context: Context, private val db: AppDatabase = AppDatabase.getDatabase(context),
    private val time: () -> Long = { System.currentTimeMillis() }) {
    val dao = db.businessDao()

    suspend fun initialize() = db.withTransaction {
        if (dao.settings() == null) dao.settings(BusinessSettings())
        if (dao.plans().isEmpty()) {
            val one = dao.insertPlan(Plan(name = "ساعة", minutes = 60, cash = 50000, bank = 62500))
            val three = dao.insertPlan(Plan(name = "3 ساعات", minutes = 180, cash = 100000, bank = 125000))
            val home = dao.insertPlan(Plan(name = "أهل البيت", minutes = 180, cash = 0, bank = 0, home = true))
            val keywords = db.shortcutDao().list().map { it.keyword }.toSet()
            listOf("س1" to one, "س3" to three, "بيت" to home).forEach { (key, plan) ->
                if (key !in keywords) db.shortcutDao().insert(Shortcut(keyword = key,
                    phrase = "%client% — الاشتراك %duration% دقيقة، ينتهي %end%، السعر %price% جنيه.", planId = plan))
            }
        }
    }

    suspend fun prepare(client: String, planId: Long, payment: String, source: String): Session = db.withTransaction {
        val plan = requireNotNull(dao.plan(planId)) { "الباقة غير موجودة" }
        require(plan.enabled) { "هذه الباقة متوقفة" }
        require(payment in listOf("CASH", "BANK")) { "اختر طريقة الدفع" }
        val settings = dao.settings() ?: BusinessSettings()
        val now = time()
        val active = reconcile(now).filter { it.state in listOf("ACTIVE", "PAUSED") }
        dao.clearExpiredReservations(now)
        val occupied = active.mapNotNull { it.reference.toIntOrNull() }.toSet() + dao.reservations().map { it.number }
        val number = (1..settings.maxSubscribers).firstOrNull { it !in occupied }
            ?: throw IllegalArgumentException("كل أرقام المشتركين مشغولة؛ أنهِ اشتراكًا أو زد الحد من الإعدادات")
        val reference = number.toString()
        val sessionId = UUID.randomUUID().toString()
        dao.reserve(SlotReservation(number, sessionId, now + 60000))
        val amount = if (plan.home) 0 else if (payment == "BANK") plan.bank else plan.cash
        Session(id = sessionId, client = client.trim().take(80).ifBlank { "مشترك $reference" },
            plan = plan.name, started = now, resumed = now, duration = plan.minutes * Rules.MINUTE,
            amount = amount, cashEquivalent = if (payment == "BANK") Money.bankToCash(amount, settings.premiumBps) else amount,
            payment = payment, premiumBps = settings.premiumBps, home = plan.home,
            grace = settings.graceMinutes * Rules.MINUTE, source = source, reference = reference)
    }

    suspend fun release(id: String) = dao.release(id)

    suspend fun insert(session: Session): Long = db.withTransaction {
        if (dao.session(session.id) != null) return@withTransaction -1L
        val reservation = dao.reservations().firstOrNull { it.sessionId == session.id }
        require(reservation != null && reservation.expires > time()) { "انتهت مهلة تسجيل الاشتراك؛ أعد المحاولة" }
        val result = dao.insertSession(session)
        dao.release(session.id)
        result
    }

    suspend fun addSales(batchId: String, lines: List<Pair<Int, Long>>, payment: String) = db.withTransaction {
        require(runCatching { UUID.fromString(batchId) }.isSuccess) { "معرّف العملية غير صالح" }
        require(payment in listOf("CASH", "BANK") && lines.size in 1..20) { "راجع بيانات الدخل" }
        val settings = dao.settings() ?: BusinessSettings()
        val now = time()
        require(lines.all { (count, price) -> count in 1..100000 && price in 1..99999999999 }) { "أدخل عددًا وسعرًا موجبين" }
        lines.forEachIndexed { index, (count, price) ->
            val total = Math.multiplyExact(count.toLong(), price)
            dao.insertManualSale(ManualSale("$batchId:$index", now, count, price, total,
                if (payment == "BANK") Money.bankToCash(total, settings.premiumBps) else total, payment, settings.premiumBps))
        }
    }

    private fun advance(s: Session, now: Long): Session {
        if (s.state != "ACTIVE") return s
        val recognized = if (s.recognized == 0L && Rules.qualifies(s.clock(), now, s.grace, s.home))
            Rules.recognitionAt(s.clock(), s.grace) else s.recognized
        return if (Rules.remaining(s.clock(), now) == 0L)
            s.copy(state = "ENDED", served = s.duration, recognized = recognized)
        else s.copy(recognized = recognized)
    }

    suspend fun reconcile(now: Long = time()): List<Session> = db.withTransaction {
        dao.sessions().map { old -> advance(old, now).also { if (it != old) dao.updateSession(it) } }
    }

    suspend fun changeState(id: String, action: String) = db.withTransaction {
        val now = time()
        val s = dao.session(id)?.let { advance(it, now) } ?: return@withTransaction
        val next = when {
            action == "PAUSE" && s.state == "ACTIVE" -> s.copy(state = "PAUSED", served = Rules.served(s.clock(), now))
            action == "RESUME" && s.state == "PAUSED" -> s.copy(state = "ACTIVE", resumed = now)
            action == "CANCEL" && s.state in listOf("ACTIVE", "PAUSED") -> s.copy(state = "CANCELLED", served = Rules.served(s.clock(), now))
            else -> s
        }
        dao.updateSession(next)
    }

    suspend fun rename(id: String, name: String) = db.withTransaction {
        require(name.isNotBlank() && name.trim().length <= 80) { "اكتب اسمًا من 1 إلى 80 حرفًا" }
        dao.session(id)?.let { dao.updateSession(it.copy(client = name.trim())) }
    }

    suspend fun markNotified(id: String, ending: Boolean) = db.withTransaction {
        dao.session(id)?.let { dao.updateSession(if (ending) it.copy(notified = true) else it.copy(warned = true)) }
    }

    suspend fun savePlan(plan: Plan) = db.withTransaction {
        require(plan.name.isNotBlank() && plan.name.length <= 60) { "اكتب اسم الباقة" }
        require(plan.minutes in 1..525600) { "المدة من دقيقة إلى سنة" }
        require(plan.cash in 0..99999999999 && plan.bank in 0..99999999999) { "السعر غير صالح" }
        val safe = if (plan.home) plan.copy(cash = 0, bank = 0) else plan
        if (safe.id == 0L) dao.insertPlan(safe) else dao.updatePlan(safe)
    }

    suspend fun saveShortcut(shortcut: Shortcut) = db.withTransaction {
        require(shortcut.payment in listOf("CASH", "BANK")) { "اختر طريقة الدفع" }
        val key = shortcut.keyword.trim()
        require(TextRules.validKeyword(key)) { "الاختصار دون مسافات أو / وبحد أقصى 40 حرفًا" }
        require(shortcut.phrase.isNotBlank() && shortcut.phrase.length <= 10000) { "اكتب نصًا لا يتجاوز 10000 حرف" }
        require(db.shortcutDao().list().none { it.id != shortcut.id && it.keyword == key }) { "هذا الاختصار موجود؛ عدّل الاختصار الحالي" }
        require(shortcut.planId == null || dao.plan(shortcut.planId) != null) { "اختر باقة موجودة" }
        db.shortcutDao().insert(shortcut.copy(keyword = key))
    }

    suspend fun saveSettings(s: BusinessSettings) = db.withTransaction {
        require(s.maxSubscribers in 1..10000) { "حد الأرقام من 1 إلى 10000" }
        val occupied = reconcile().filter { it.state in listOf("ACTIVE", "PAUSED") }.mapNotNull { it.reference.toIntOrNull() } + dao.reservations().map { it.number }
        val previous = dao.settings()?.maxSubscribers ?: 50
        require(s.maxSubscribers >= previous || occupied.none { it > s.maxSubscribers }) { "يوجد رقم نشط أعلى من الحد الجديد" }
        require(s.graceMinutes in 0..1440 && s.premiumBps in 0..100000) { "راجع مهلة التثبيت ونسبة بنكك" }
        require(s.usdCents >= 0 && s.bankRate >= 0 && s.expenses >= 0) { "المبالغ لا تكون سالبة" }
        require((s.cycleStart == 0L && s.cycleEnd == 0L) || (s.cycleStart > 0 && s.cycleEnd > s.cycleStart)) { "نهاية الدورة يجب أن تكون بعد بدايتها" }
        Money.bill(s.usdCents, s.bankRate)
        dao.settings(s)
    }

    suspend fun restoreJson(json: String) = withContext(Dispatchers.IO) {
        val snapshot = BackupData.parse(json)
        // A failed restore rolls back every table. SQL identifiers come only from our allowlist.
        db.withTransaction {
            val sql = db.openHelper.writableDatabase
            sql.execSQL("DELETE FROM slot_reservations")
            BackupData.tables.reversed().forEach { sql.execSQL("DELETE FROM `$it`") }
            BackupData.tables.forEach { table -> snapshot.rows.getValue(table).forEach { row ->
                val values = android.content.ContentValues()
                row.keys().forEach { key ->
                    if (row.isNull(key)) values.putNull(key)
                    else if (row.get(key) is Number) values.put(key, row.getLong(key))
                    else values.put(key, row.getString(key))
                }
                sql.insert(table, android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT, values)
            } }
        }
        com.example.service.ExpanderHealth.preferences(context).edit().putStringSet("apps", snapshot.apps).commit()
    }

    suspend fun exportJson(): String = withContext(Dispatchers.IO) {
        db.withTransaction {
            val root = org.json.JSONObject().put("version", 4).put("format", "slotra-backup").put("exportedAt", System.currentTimeMillis())
            fun rows(query: String): org.json.JSONArray {
                val result = org.json.JSONArray()
                db.openHelper.readableDatabase.query(query).use { c -> while (c.moveToNext()) {
                    val row = org.json.JSONObject()
                    c.columnNames.forEachIndexed { i, n -> row.put(n, when (c.getType(i)) {
                        android.database.Cursor.FIELD_TYPE_NULL -> org.json.JSONObject.NULL
                        android.database.Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                        else -> c.getString(i)
                    }) }
                    result.put(row)
                } }
                return result
            }
            listOf("plans", "sessions", "settings", "shortcuts", "devices", "sequences", "manual_sales").forEach { table -> root.put(table, rows("SELECT * FROM $table")) }
            root.put("allowedApps", org.json.JSONArray(com.example.service.ExpanderHealth.allowed(context).toList()))
            root.toString(2)
        }
    }
}
