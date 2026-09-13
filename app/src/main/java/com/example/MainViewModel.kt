package com.example

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.SubscriptionRepository
import com.example.data.BackupData
import com.example.db.*
import com.example.notifications.SubscriptionAlarms
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AppDatabase.getDatabase(application)
    private val repo = SubscriptionRepository(application)
    private val sharing = SharingStarted.WhileSubscribed(5000)
    val manualSales = repo.dao.observeManualSales().stateIn(viewModelScope, sharing, emptyList())
    val pendingRestore = MutableStateFlow<BackupData?>(null)
    private var restoreText: String? = null
    private val backupPrefs = application.getSharedPreferences("backup_status", 0)
    val backupStatus = MutableStateFlow(backupPrefs.getString("last", "لم تحفظ نسخة ملف بعد").orEmpty())
    val plans = repo.dao.observePlans().stateIn(viewModelScope, sharing, emptyList())
    val sessions = repo.dao.observeSessions().stateIn(viewModelScope, sharing, emptyList())
    val shortcuts = db.shortcutDao().getAll().stateIn(viewModelScope, sharing, emptyList())
    val settings = repo.dao.observeSettings().stateIn(viewModelScope, sharing, null)
    val devices = db.deviceDao().getAll().stateIn(viewModelScope, sharing, emptyList())
    val message = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    private val commands = Mutex()
    init { work { repo.initialize(); SubscriptionAlarms.refresh(getApplication()) } }
    private fun work(block: suspend () -> Unit) {
        viewModelScope.launch { commands.withLock {
            busy.value = true
            try { withContext(Dispatchers.IO) { block() } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message.value = e.message ?: "تعذّر الحفظ؛ حاول مرة أخرى" }
            finally { busy.value = false }
        } }
    }
    fun refresh() {
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
        SubscriptionAlarms.refresh(getApplication())
        message.value = "تم تسجيل الاشتراك"
    }
    fun addSales(id: String, lines: List<Pair<Int, Long>>, payment: String) = work {
        repo.addSales(id, lines, payment); message.value = "تمت إضافة الدخل إلى حساب اليوم"
    }
    fun change(id: String, action: String) = work {
        repo.changeState(id, action); SubscriptionAlarms.refresh(getApplication())
    }
    fun rename(id: String, name: String) = work { repo.rename(id, name); message.value = "تم تحديث اسم المشترك" }
    fun savePlan(plan: Plan) = work { repo.savePlan(plan); message.value = "تم حفظ الباقة" }
    fun saveShortcut(shortcut: Shortcut) = work { repo.saveShortcut(shortcut); message.value = "تم حفظ الاختصار" }
    fun deleteShortcut(shortcut: Shortcut) = work { db.shortcutDao().delete(shortcut) }
    fun saveSettings(settings: BusinessSettings) = work { repo.saveSettings(settings); message.value = "تم حفظ الإعدادات للاشتراكات الجديدة" }
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
            SubscriptionAlarms.refresh(getApplication())
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
