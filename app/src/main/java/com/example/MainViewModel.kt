package com.example

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.SubscriptionRepository
import com.example.data.BackupData
import com.example.data.ClientTracker
import com.example.data.DailyReconciliation
import com.example.data.DeviceRecovery
import com.example.data.DeviceSelection
import com.example.data.IpListStore
import com.example.data.IpLists
import com.example.data.TrackedDevice
import com.example.network.StarlinkProbe
import com.example.db.*
import com.example.domain.*
import com.example.notifications.DeviceAlertsCoordinator
import com.example.notifications.DeviceTrackerBridge
import com.example.notifications.SubscriptionAlarms
import com.example.service.ExpanderHealth
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock



class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AppDatabase.getDatabase(application)
    private val repo = SubscriptionRepository(application)
    private val sharing = SharingStarted.WhileSubscribed(5000)

    companion object {
        /** Default recovery shortcut keyword (configurable in Settings, never hard-coded in the service). */
        const val DEFAULT_RECOVERY_KEYWORD = "استعادة"
    }
    val corrections = repo.dao.observeCorrections().stateIn(viewModelScope, sharing, emptyList())
    val cycles = repo.dao.observeCycles().stateIn(viewModelScope, sharing, emptyList())
    val debts = repo.dao.observeDebts().stateIn(viewModelScope, sharing, emptyList())
    val debtPayments = repo.dao.observeDebtPayments().stateIn(viewModelScope, sharing, emptyList())
    fun correctRevenue(source: String, amount: Long, count: Int, voided: Boolean, reason: String) = work {
        repo.correctRevenue(source, amount, count, voided, reason); message.value = "تم تصحيح الإيراد وإعادة حساب أهداف الأيام"
    }
    fun saveDebt(debt: Debt) = work { repo.saveDebt(debt); message.value = "تم حفظ خطة الدين" }
    fun payDebt(id: String, debtId: String, amount: Long) = work { repo.payDebt(id, debtId, amount); message.value = "تم تسجيل السداد الفعلي" }
    val manualSales = repo.dao.observeManualSales().stateIn(viewModelScope, sharing, emptyList())
    val pendingRestore = MutableStateFlow<BackupData?>(null)
    private var restoreText: String? = null
    private val backupPrefs = application.getSharedPreferences("backup_status", 0)
    val backupStatus = MutableStateFlow(backupPrefs.getString("last", "لم تحفظ نسخة ملف بعد").orEmpty())
    val plans = repo.dao.observePlans().stateIn(viewModelScope, sharing, emptyList())
    val sessions = repo.dao.observeSessions().stateIn(viewModelScope, sharing, emptyList())
    val shortcuts = db.shortcutDao().getAll().stateIn(viewModelScope, sharing, emptyList())
    val settings = repo.dao.observeSettings().stateIn(viewModelScope, sharing, null)
    val clock = MutableStateFlow(System.currentTimeMillis())
    private val reportCache = FinancialReportCache()
    private val accountingBase = combine(
        repo.dao.observeSessions(), repo.dao.observeManualSales(), repo.dao.observeCorrections(),
        repo.dao.observeSettings(), repo.dao.observeCycles()
    ) { sessions, sales, corrections, settings, cycles ->
        FinancialData(sessions, sales, corrections, settings ?: BusinessSettings(), cycles, emptyList(), emptyList())
    }
    val financial = combine(accountingBase, repo.dao.observeDebts(), repo.dao.observeDebtPayments(), repo.dao.observeBalanceUpdates(), clock) { base, debts, payments, balances, _ ->
        // Fresh writes must be included immediately, not at the next fifteen-second tick.
        reportCache.get(base.copy(debts = debts, payments = payments, balanceUpdates = balances), System.currentTimeMillis())
    }.flowOn(Dispatchers.Default).distinctUntilChanged()
        .stateIn(viewModelScope, sharing, null)

    val devices = db.deviceDao().getAll().stateIn(viewModelScope, sharing, emptyList())
    val homeIps = repo.dao.observeHomeIps().stateIn(viewModelScope, sharing, emptyList())
    val watchIps = repo.dao.observeWatchIps().stateIn(viewModelScope, sharing, emptyList())

    // Device tracking: one local CLIENTS read per refresh, bounded and cloud-free.
    private val lists = IpListStore(application)
    private val probe = StarlinkProbe(application)
    private val tracker = ClientTracker(application, repo, lists) { network -> probe.clients(network) }
    data class DeviceScan(val devices: List<TrackedDevice>, val at: Long, val failed: Boolean)
    private val deviceScan = MutableStateFlow<DeviceScan?>(null)

    /**
     * One monitoring cycle: refresh live devices, then apply pause/resume for bound
     * sessions. Sharing one code path with the devices screen keeps UI and tracking
     * decisions identical. A failed read shows as failure and pauses nothing.
     * On success, the snapshot also feeds the Phase 3 daily device history.
     */
    fun refreshDevices() {
        viewModelScope.launch {
            if (!commands.tryLock()) return@launch
            busy.value = true
            try { withContext(Dispatchers.IO) {
                tracker.poll()
                // lastSnapshot is null only when the local read failed; an empty snapshot
                // is a successful router answer and shows as "no devices".
                deviceScan.value = DeviceScan(tracker.lastSnapshot.orEmpty(), System.currentTimeMillis(), failed = tracker.lastSnapshot == null)
                // Discovery-failure protection (spec 32): history only advances on success.
                if (tracker.lastSnapshot != null) {
                    DeviceAlertsCoordinator.recordSnapshot(getApplication(), System.currentTimeMillis(), tracker.lastSnapshot.orEmpty())
                    DeviceTrackerBridge.updateSnapshot(tracker.lastSnapshot)
                }
                SubscriptionAlarms.refresh(getApplication())
            } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message.value = e.message ?: "تعذّر تحديث الأجهزة؛ حاول مرة أخرى" }
            finally { clock.value = System.currentTimeMillis(); busy.value = false; commands.unlock() }
        }
    }
    val deviceScanState = deviceScan.asStateFlow()

    fun addHomeIp(ip: String, label: String = "") = work { lists.addHome(ip, label); message.value = "تمت إضافة الجهاز لأهل البيت" }
    fun removeHomeIp(ip: String) = work { lists.removeHome(ip) }
    fun addWatchIp(ip: String, label: String = "") = work { lists.addWatch(ip, label); message.value = "تمت إضافة الجهاز لقائمة المراقبة" }
    fun removeWatchIp(ip: String) = work { lists.removeWatch(ip) }

    // Identity-based list management (schema v8): the stable clientId is saved with the
    // MAC and last-known IP as display data, so DHCP IP changes cannot reclassify.
    fun addHomeDevice(device: TrackedDevice) = work {
        lists.setHomeIdentity(device.clientId, device.name, device.mac, device.ip)
        message.value = "تمت إضافة الجهاز لأهل البيت بمعرّفه الثابت"
    }
    fun addWatchDevice(device: TrackedDevice) = work {
        lists.setWatchIdentity(device.clientId, device.name, device.mac, device.ip)
        message.value = "تمت إضافة الجهاز لقائمة المراقبة بمعرّفه الثابت"
    }
    fun removeHomeDevice(deviceId: Long) = work { lists.removeIdentity(deviceId) }
    fun removeWatchDevice(deviceId: Long) = work { lists.removeIdentity(deviceId) }
    val identities = repo.dao.observeIdentities().stateIn(viewModelScope, sharing, emptyList())

    /** Live candidates for the binding flow; null when discovery is currently unavailable. */
    suspend fun bindingChoices(): DeviceSelection.Result? {
        val snapshot = tracker.snapshotBlocking() ?: return null
        val taken = repo.dao.sessions().filter { it.state in listOf("ACTIVE", "PAUSED") && it.deviceClientId != null }.mapNotNull { it.deviceClientId }.toSet()
        return DeviceSelection.choose(snapshot, taken)
    }

    val message = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    private val commands = Mutex()
    init { work { repo.initialize() } }
    private fun work(block: suspend () -> Unit) {
        viewModelScope.launch { commands.withLock {
            busy.value = true
            try { withContext(Dispatchers.IO) { block(); SubscriptionAlarms.refresh(getApplication()) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message.value = e.message ?: "تعذّر الحفظ؛ حاول مرة أخرى" }
            finally { clock.value = System.currentTimeMillis(); busy.value = false }
        } }
    }
    fun refresh() {
        clock.value = System.currentTimeMillis()
        viewModelScope.launch {
            if (!commands.tryLock()) return@launch
            try { withContext(Dispatchers.IO) { SubscriptionAlarms.refresh(getApplication()) } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { message.value = "تعذّر تحديث المواعيد؛ أعد فتح التطبيق للمحاولة." }
            finally { commands.unlock() }
        }
    }
    fun create(client: String, plan: Long, payment: String) = work {
        repo.insert(repo.prepare(client, plan, payment, "manual"))
        message.value = "تم تسجيل الاشتراك"
    }

    /**
     * Creates the session, then attaches the user-confirmed device. Creation itself never
     * fails because of binding: a failed attach (device vanished between choice and save,
     * or expired reservation) still leaves the session alive with no binding.
     */
    fun createWithDevice(client: String, plan: Long, payment: String, device: TrackedDevice?) = work {
        val session = repo.prepare(client, plan, payment, "manual")
        repo.insert(session)
        if (device != null) runCatching { repo.bindDevice(session.id, device) }
            .onFailure { message.value = "تم تسجيل الاشتراك دون ربط الجهاز" }
        message.value = if (device != null && message.value == null) "تم تسجيل الاشتراك وربط الجهاز" else message.value ?: "تم تسجيل الاشتراك"
    }
    fun addSales(id: String, lines: List<Pair<Int, Long>>, payment: String) = work {
        repo.addSales(id, lines, payment); message.value = "تمت إضافة الدخل إلى حساب اليوم"
    }

    /**
     * Phase 4 daily device confirmation, per device: upserts each device's
     * (dayKey, deviceId) confirmation row and rebuilds the day's single ledger row.
     * Idempotent by identity — reopening, editing, and re-confirming never duplicate.
     * work{} refreshes alarms/panel/finance flows after.
     */
    fun confirmDailyDevices(dayKey: String, devices: List<SubscriptionRepository.DailyDeviceAmount>, payment: String) = work {
        repo.confirmDailyDevices(dayKey, devices, payment)
        message.value = "تم تأكيد إيراد $dayKey وإضافته إلى المالية"
    }
    fun change(id: String, action: String) = work {
        repo.changeState(id, action)
    }
    fun rename(id: String, name: String) = work { repo.rename(id, name); message.value = "تم تحديث اسم المشترك" }
    fun savePlan(plan: Plan) = work { repo.savePlan(plan); message.value = "تم حفظ الباقة" }
    fun saveShortcut(shortcut: Shortcut) = work { repo.saveShortcut(shortcut); message.value = "تم حفظ الاختصار" }
    fun deleteShortcut(shortcut: Shortcut) = work { db.shortcutDao().delete(shortcut) }
    fun saveSettings(settings: BusinessSettings) = work { repo.saveSettings(settings); message.value = "تم حفظ الإعدادات وإعادة حساب خطة الفاتورة" }
    fun updateBalance(cash: Long, bank: Long, reason: String) = work {
        repo.updateBalance(cash, bank, reason)
        message.value = "تم تحديث الرصيد وإعادة حساب المطلوب للفاتورة"
    }
    /**
     * [minute] is minutes since local midnight (0..1439), or -1 to disable. `work{}` already
     * calls SubscriptionAlarms.refresh() afterward, which reschedules the next wake-up to include
     * (or drop) this time immediately - no separate rescheduling call needed here.
     */
    fun setDailyClose(minute: Int) = work {
        SubscriptionAlarms.setDailyCloseMinute(getApplication(), minute)
        message.value = if (minute < 0) "أُلغي إغلاق الشبكة اليومي"
            else "سيُنهي التطبيق كل الاشتراكات النشطة تلقائيًا الساعة ${String.format("%02d:%02d", minute / 60, minute % 60)}"
    }
    /** Unknown-device alert delay (1/3/5 minutes); work{} re-schedules via refresh(). */
    fun setDeviceAlertDelay(minutes: Int) = work {
        DeviceAlertsCoordinator.setDelayMinutes(getApplication(), minutes)
        message.value = "سيتم تنبيهك بعد $minutes دقيقة من ظهور جهاز غير مرتبط"
    }
    /** Daily device confirmation time; work{} re-schedules via refresh() (spec 44). */
    fun setDeviceSummaryTime(minute: Int) = work {
        DeviceAlertsCoordinator.setSummaryMinute(getApplication(), minute)
        message.value = "سيصلك ملخص تأكيد الأجهزة يوميًا الساعة ${String.format("%02d:%02d", minute / 60, minute % 60)}"
    }
    /**
     * Phase 4 password-change recovery: reads live devices (one poll), builds the
     * recovery view-model via the pure DeviceRecovery engine, and re-links the
     * user-confirmed session to the user-confirmed device. No new subscription,
     * no added time, no revenue — only the binding moves (and an immediate RESUME
     * when the device is live). Throws on read failure so the UI can explain it.
     */
    suspend fun recoveryBoard(): DeviceRecovery.Board {
        commands.lock()
        busy.value = true
        return try { withContext(Dispatchers.IO) {
            tracker.poll()
            val ok = tracker.lastSnapshot != null
            if (ok) {
                DeviceAlertsCoordinator.recordSnapshot(getApplication(), System.currentTimeMillis(), tracker.lastSnapshot.orEmpty())
                DeviceTrackerBridge.updateSnapshot(tracker.lastSnapshot)
            }
            val sessions = repo.dao.sessions()
            val liveIds = tracker.lastSnapshot.orEmpty().map { it.clientId }.toSet()
            val bound = sessions.filter { !it.home && it.state in listOf("ACTIVE", "PAUSED") && it.deviceClientId != null }.mapNotNull { it.deviceClientId }.toSet()
            val homeIds = lists.identitySnapshot()
            return@withContext DeviceRecovery.Board(
                snapshotOk = ok,
                candidates = DeviceRecovery.candidates(sessions, System.currentTimeMillis(), liveIds, ok),
                options = DeviceRecovery.options(tracker.lastSnapshot, homeIds.homeClientIds, homeIds.legacyHomeIps, bound,
                    IpLists.homeMacs(homeIds.identities))
            )
        } }
        catch (e: CancellationException) { throw e }
        finally { busy.value = false; commands.unlock() }
    }

    /** Re-links an existing subscription (see SubscriptionRepository.relinkDevice). */
    fun relinkDevice(sessionId: String, device: TrackedDevice, deviceIsLive: Boolean) = work {
        repo.relinkDevice(sessionId, device, deviceIsLive)
        message.value = "تم إعادة ربط الاشتراك بنفس الوقت المتبقي دون إنشاء اشتراك جديد"
    }

    /** Recovery shortcut keyword, configurable in Settings (never a hard-coded letter). */
    private val recoveryPrefs = getApplication<Application>().getSharedPreferences("expander", Context.MODE_PRIVATE)
    val recoveryKeyword: StateFlow<String> = ExpanderHealth.recoveryKeywordFlow(recoveryPrefs)
        .stateIn(viewModelScope, sharing, DEFAULT_RECOVERY_KEYWORD)

    /** Sets the recovery keyword; validated and de-conflicted exactly like a shortcut. */
    fun setRecoveryKeyword(keyword: String) = work {
        val key = keyword.trim()
        require(TextRules.validKeyword(key)) { "الاختصار دون مسافات أو / وبحد أقصى 40 حرفًا" }
        require(db.shortcutDao().list().none { it.keyword == key }) { "هذا الاختصار مستخدم لاختصار اشتراك؛ اختر غيره" }
        recoveryPrefs.edit().putString("recovery_keyword", key).apply()
        message.value = "اكتب $key ثم مسافة في أي تطبيق مسموح لفتح شاشة الاستعادة"
    }

    /**
     * Phase 4 signing transparency: the installed APK's signing certificate
     * SHA-256, so the owner can verify update-over-install compatibility from
     * Settings. Null when the platform does not expose it (pre-P) or on failure.
     */
    fun debugSigningInfo(): String? = try {
        val pm = getApplication<Application>().packageManager
        val signatures = if (android.os.Build.VERSION.SDK_INT >= 28) {
            pm.getPackageInfo(getApplication<Application>().packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners ?: return null
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(getApplication<Application>().packageName, android.content.pm.PackageManager.GET_SIGNATURES).signatures
                ?: return null
        }
        signatures.firstOrNull()?.let { sig ->
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(sig.toByteArray())
            digest.joinToString("") { "%02x".format(it) }
        }
    } catch (e: Exception) { null }

    fun dismissRestore() { pendingRestore.value = null; restoreText = null }
    fun previewRestore(uri: Uri) = work {
        dismissRestore()
        val text = requireNotNull(getApplication<Application>().contentResolver.openInputStream(uri)) { "تعذّر فتح النسخة" }.use { input ->
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            while (true) {
                val n = input.read(chunk); if (n == -1) break
                require(buffer.size() + n <= BackupData.MAX_BYTES) { "النسخة أكبر من 20 ميجابايت" }
                buffer.write(chunk, 0, n)
            }
            buffer.toString("UTF-8")
        }
        val parsed = BackupData.parse(text)
        restoreText = text; pendingRestore.value = parsed
    }
    fun confirmRestore() {
        val text = restoreText ?: return
        dismissRestore()
        work {
            // Preserve the pre-restore snapshot even if the process is interrupted afterward.
            val recovery = android.util.AtomicFile(java.io.File(getApplication<Application>().filesDir, "before-restore.json"))
            val stream = recovery.startWrite()
            try { stream.write(repo.exportJson().toByteArray(Charsets.UTF_8)); recovery.finishWrite(stream) }
            catch (e: Exception) { recovery.failWrite(stream); throw e }
            repo.restoreJson(text)
            androidx.core.app.NotificationManagerCompat.from(getApplication()).cancelAll()
            message.value = "اكتملت الاستعادة؛ راجع صلاحيات التنبيهات وإمكانية الوصول على هذا الهاتف"
        }
    }
    fun export(uri: Uri) = work {
        val json = repo.exportJson()
        requireNotNull(getApplication<Application>().contentResolver.openOutputStream(uri, "wt")) { "تعذّر فتح الملف" }
            .bufferedWriter(Charsets.UTF_8).use { it.write(json) }
        val status = "آخر ملف محفوظ: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.ROOT).format(java.util.Date())}"
        backupPrefs.edit().putString("last", status).apply()
        backupStatus.value = status
        message.value = "تم حفظ النسخة كاملة؛ يمكنك استعادتها من الإعدادات"
    }
}
