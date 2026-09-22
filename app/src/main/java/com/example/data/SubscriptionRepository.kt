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
        val existing = dao.settings()!!
        if (existing.cycleId.isBlank() || existing.graceMinutes != Rules.RECOGNITION_MINUTES)
            saveSettings(existing.copy(graceMinutes = Rules.RECOGNITION_MINUTES))
        if (dao.plans().isEmpty()) {
            val one = dao.insertPlan(Plan(name = "ساعة", minutes = 60, cash = 50000, bank = 62500))
            val three = dao.insertPlan(Plan(name = "3 ساعات", minutes = 180, cash = 100000, bank = 125000))
            val home = dao.insertPlan(Plan(name = "أهل البيت", minutes = 180, cash = 0, bank = 0, home = true))
            val keywords = db.shortcutDao().list().map { it.keyword }.toSet()
            listOf("س1" to one, "س3" to three, "بيت" to home).forEach { (key, plan) ->
                if (key !in keywords) db.shortcutDao().insert(Shortcut(keyword = key,
                    phrase = if (plan == home) "✅ أهل البيت" else "%client% — الاشتراك %duration% دقيقة، ينتهي %end%، السعر %price% جنيه.", planId = plan))
            }
        }
    }

    suspend fun prepare(client: String, planId: Long, payment: String, source: String): Session = db.withTransaction {
        val plan = requireNotNull(dao.plan(planId)) { "الباقة غير موجودة" }
        require(!plan.home) { "اختصارات أهل البيت نص فقط؛ لا تحتاج تسجيل اشتراك" }
        require(plan.enabled) { "هذه الباقة متوقفة" }
        require(payment in listOf("CASH", "BANK")) { "اختر طريقة الدفع" }
        val settings = dao.settings() ?: BusinessSettings()
        val now = time()
        val active = reconcile(now).filter { it.state in listOf("ACTIVE", "PAUSED") }
        dao.clearExpiredReservations(now)
        val today = com.example.domain.Revenue.day(now)
        val todaySessions = dao.sessions().filter { com.example.domain.Revenue.day(it.started) == today }
        val usedToday = todaySessions.mapNotNull { it.reference.toIntOrNull() }.toSet() + dao.reservations().map { it.number }
        val number = (1..settings.maxSubscribers).firstOrNull { it !in usedToday }
            ?: throw IllegalArgumentException("تم استخدام جميع أرقام اليوم المتاحة ($settings.maxSubscribers)؛ زد الحد من الإعدادات")
        val reference = number.toString()
        val sessionId = UUID.randomUUID().toString()
        dao.reserve(SlotReservation(number, sessionId, now + 60000))
        val amount = if (plan.home) 0 else if (payment == "BANK") plan.bank else plan.cash
        Session(id = sessionId, client = client.trim().take(80).ifBlank { "مشترك $reference" },
            plan = plan.name, started = now, resumed = now, duration = plan.minutes * Rules.MINUTE,
            amount = amount, cashEquivalent = if (payment == "BANK") Money.bankToCash(amount, settings.premiumBps) else amount,
            payment = payment, premiumBps = settings.premiumBps, home = plan.home,
            grace = Rules.RECOGNITION_MINUTES * Rules.MINUTE, source = source, reference = reference)
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
        if (dao.hasSaleBatch("$batchId:%")) return@withTransaction
        val settings = dao.settings() ?: BusinessSettings()
        val now = time()
        require(lines.all { (count, price) -> count in 1..100000 && price in 1..99999999999 }) { "أدخل عددًا وسعرًا موجبين" }
        lines.forEachIndexed { index, (count, price) ->
            val total = Math.multiplyExact(count.toLong(), price)
            dao.insertManualSale(ManualSale("$batchId:$index", now, count, price, total,
                if (payment == "BANK") Money.bankToCash(total, settings.premiumBps) else total, payment, settings.premiumBps))
        }
    }

    suspend fun expansionPlan(id: Long): Plan = requireNotNull(dao.plan(id)).also { require(it.enabled) { "هذه الباقة متوقفة" } }

    private fun advance(s: Session, now: Long): Session {
        if (s.home) return s.copy(state = "CANCELLED", reference = "", amount = 0, cashEquivalent = 0, recognized = 0, warned = true, notified = true)
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
        Math.addExact(Money.bankToCash(Money.bill(s.usdCents, s.bankRate), s.premiumBps), s.expenses)
        val configured = s.cycleStart > 0 && s.cycleEnd > s.cycleStart && s.usdCents > 0 && s.bankRate > 0
        if (configured) {
            val cycles = dao.cycles()
            val old = cycles.find { it.id == s.cycleId }
            val same = old != null && s.cycleStart < old.end && s.cycleEnd > old.start
            val id = if (same) old!!.id else UUID.randomUUID().toString()
            require(cycles.none { it.id != id && s.cycleStart < it.end && s.cycleEnd > it.start }) { "توجد دورة محفوظة تتداخل مع هذه التواريخ" }
            val cost = Math.addExact(Money.bankToCash(Money.bill(s.usdCents, s.bankRate), s.premiumBps), s.expenses)
            dao.cycle(BillingCycle(id, s.cycleStart, s.cycleEnd, cost))
            dao.settings(s.copy(cycleId = id, graceMinutes = Rules.RECOGNITION_MINUTES))
        } else dao.settings(s.copy(graceMinutes = Rules.RECOGNITION_MINUTES))
    }

    suspend fun correctRevenue(source: String, amount: Long, count: Int, voided: Boolean, reason: String) = db.withTransaction {
        require(amount in 0..9999999999900000 && count in 1..100000 && reason.trim().length in 1..200) { "راجع مبلغ التصحيح وعدد الأجهزة وسببه" }
        val ledger = com.example.domain.Finance.ledger(dao.sessions(), dao.manualSales(), emptyList())
        val original = requireNotNull(ledger.find { it.id == source }) { "قيد الإيراد غير موجود أو لم يُثبّت بعد" }
        val value = if (original.bank) Money.bankToCash(amount, original.premiumBps) else amount
        dao.correct(RevenueCorrection(source = source, amount = amount, cashEquivalent = value, count = if (source.startsWith("session:")) 1 else count,
            voided = voided, at = time(), reason = reason.trim()))
    }
    suspend fun saveDebt(debt: Debt) = db.withTransaction {
        require(debt.name.trim().length in 1..80 && debt.total in 1..99999999999 && debt.start > 0 && debt.due >= debt.start) { "راجع اسم الدين وقيمته وفترته" }
        require(debt.total >= dao.debtPayments().filter { it.debtId == debt.id }.sumOf { it.amount }) { "قيمة الدين لا تقل عن المسدّد فعليًا" }
        dao.debt(debt.copy(name = debt.name.trim()))
    }
    suspend fun payDebt(id: String, debtId: String, amount: Long) = db.withTransaction {
        if (dao.debtPayments().any { it.id == id }) return@withTransaction
        val debt = requireNotNull(dao.debts().find { it.id == debtId }) { "الدين غير موجود" }
        val paid = dao.debtPayments().filter { it.debtId == debtId }.sumOf { it.amount }
        val remaining = (debt.total - paid).coerceAtLeast(0)
        require(amount > 0 && amount <= remaining) { "السداد لا يتجاوز المبلغ المتبقي من الدين" }
        dao.payDebt(DebtPayment(id, debtId, time(), amount))
    }

    suspend fun updateBalance(cash: Long, bank: Long, reason: String) = db.withTransaction {
        require(cash in 0..99999999999 && bank in 0..99999999999) { "راجع الرصيد؛ أدخل مبلغًا موجبًا أو صفرًا" }
        require(reason.trim().length in 1..200) { "اكتب سبب تحديث الرصيد" }
        val now = time()
        val config = dao.settings() ?: BusinessSettings()
        val receipts = com.example.domain.BalanceBook.receipts(dao.sessions(), dao.manualSales())
        val totals = com.example.domain.BalanceBook.totals(receipts, now)
        val expected = com.example.domain.BalanceBook.expected(dao.balanceUpdates(), receipts, now, config.cycleStart)
        // Pending paid sessions are already included, so five-minute recognition cannot add them again.
        dao.balanceUpdate(BalanceUpdate(at = now, cash = cash, bank = bank, cashReceived = totals.cash,
            bankReceived = totals.bank, premiumBps = config.premiumBps, reason = reason.trim(),
            expectedCash = expected.cash, expectedBank = expected.bank))
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
        initialize(); reconcile()
        com.example.service.ExpanderHealth.preferences(context).edit().putStringSet("apps", snapshot.apps).commit()
    }

    suspend fun exportJson(): String = withContext(Dispatchers.IO) {
        db.withTransaction {
            val root = org.json.JSONObject().put("version", 6).put("format", "slotra-backup").put("exportedAt", System.currentTimeMillis())
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
            BackupData.tables.forEach { table -> root.put(table, rows("SELECT * FROM $table")) }
            root.put("allowedApps", org.json.JSONArray(com.example.service.ExpanderHealth.allowed(context).toList()))
            root.toString(2)
        }
    }
}
