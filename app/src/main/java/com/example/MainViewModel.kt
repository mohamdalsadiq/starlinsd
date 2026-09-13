package com.example

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.SubscriptionRepository
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
    fun refresh() { if (!busy.value) work { SubscriptionAlarms.refresh(getApplication()) } }
    fun create(client: String, plan: Long, payment: String) = work {
        repo.insert(repo.prepare(client, plan, payment, "manual"))
        SubscriptionAlarms.refresh(getApplication())
        message.value = "تم تسجيل الاشتراك"
    }
    fun change(id: String, action: String) = work {
        repo.changeState(id, action); SubscriptionAlarms.refresh(getApplication())
    }
    fun savePlan(plan: Plan) = work { repo.savePlan(plan); message.value = "تم حفظ الباقة" }
    fun saveShortcut(shortcut: Shortcut) = work { repo.saveShortcut(shortcut); message.value = "تم حفظ الاختصار" }
    fun deleteShortcut(shortcut: Shortcut) = work { db.shortcutDao().delete(shortcut) }
    fun saveSettings(settings: BusinessSettings) = work { repo.saveSettings(settings); message.value = "تم حفظ الإعدادات للاشتراكات الجديدة" }
    fun export(uri: Uri) = work {
        val json = repo.exportJson()
        requireNotNull(getApplication<Application>().contentResolver.openOutputStream(uri, "wt")) { "تعذّر فتح الملف" }
            .bufferedWriter(Charsets.UTF_8).use { it.write(json) }
        message.value = "تم تصدير السجل، بما فيه الأجهزة القديمة"
    }
}
