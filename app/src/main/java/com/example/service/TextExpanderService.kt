package com.example.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputType
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.example.data.SubscriptionRepository
import com.example.db.AppDatabase
import com.example.db.Shortcut
import com.example.domain.Money
import com.example.domain.TextRules
import com.example.notifications.SubscriptionAlarms
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

object ExpanderHealth {
    val connected = MutableStateFlow(false)
    fun preferences(context: Context): SharedPreferences = context.getSharedPreferences("expander", Context.MODE_PRIVATE)
    fun allowed(context: Context): Set<String> = preferences(context).getStringSet("apps", emptySet())?.toSet().orEmpty()
}

/** Edits only the focused, non-password field in apps the operator explicitly selects. */
class TextExpanderService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var shortcuts = emptyList<Shortcut>()
    private var allowed = emptySet<String>()
    private var processing = false
    private var lastApplied: Pair<Int, String>? = null
    private var collection: Job? = null
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> updateAllowedApps() }
    private fun updateAllowedApps() {
        allowed = ExpanderHealth.allowed(this)
        serviceInfo?.let { info ->
            info.packageNames = allowed.ifEmpty { setOf(packageName) }.toTypedArray()
            serviceInfo = info
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        ExpanderHealth.connected.value = true
        updateAllowedApps()
        ExpanderHealth.preferences(this).registerOnSharedPreferenceChangeListener(listener)
        collection?.cancel()
        collection = scope.launch {
            AppDatabase.getDatabase(this@TextExpanderService).shortcutDao().getAll().collect { shortcuts = it.filter { s -> s.enabled } }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (processing || event?.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg !in allowed) return
        val node = event.source ?: return
        if (!safe(node)) return
        val text = node.text?.toString() ?: return
        if (lastApplied == (node.windowId to text)) return
        val start = node.textSelectionStart
        val match = TextRules.match(text, start, node.textSelectionEnd, shortcuts.map { it.keyword }.toSet()) ?: return
        val shortcut = shortcuts.first { it.keyword == match.keyword }
        processing = true
        scope.launch {
            val repo = SubscriptionRepository(this@TextExpanderService)
            var reservationId: String? = null
            try {
                val plan = shortcut.planId?.let { repo.expansionPlan(it) }
                val household = plan?.home == true
                val session = plan?.takeUnless { it.home }?.let { repo.prepare(match.client, it.id, shortcut.payment, shortcut.keyword) }
                reservationId = session?.id
                val now = session?.started ?: System.currentTimeMillis()
                val rendered = TextRules.render(if (household) TextRules.householdTemplate(shortcut.phrase) else shortcut.phrase, now, session?.client ?: match.client,
                    end = session?.let { it.started + it.duration } ?: now,
                    price = session?.let { Money.show(it.amount) }.orEmpty(),
                    duration = session?.let { (it.duration / 60000).toString() }.orEmpty(), code = session?.reference.orEmpty())
                val replacement = if (session == null) rendered else TextRules.withReference(rendered, session.reference)
                if (!node.refresh() || !safe(node) || node.text?.toString() != text ||
                    node.textSelectionStart != start || node.textSelectionEnd != start || pkg !in ExpanderHealth.allowed(this@TextExpanderService)) return@launch
                val expanded = text.replaceRange(match.from, match.to, replacement)
                lastApplied = node.windowId to expanded
                val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, expanded)
                })
                if (!success) { lastApplied = null; return@launch }
                val cursor = match.from + replacement.length + (start - match.to)
                node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
                })
                if (session != null) {
                    repo.insert(session)
                    SubscriptionAlarms.refresh(this@TextExpanderService)
                    Toast.makeText(this@TextExpanderService, "تم تسجيل ${session.client}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val message = e.message ?: "تعذّر إكمال الاختصار؛ راجع سجل المشتركين قبل المحاولة مجددًا."
                getSharedPreferences("service_health", 0).edit().putString("error", message).apply()
                Toast.makeText(this@TextExpanderService, message, Toast.LENGTH_LONG).show()
            } finally {
                withContext(NonCancellable) { reservationId?.let { runCatching { repo.release(it) } } }
                processing = false
            }
        }
    }

    private fun safe(node: AccessibilityNodeInfo): Boolean {
        val variation = node.inputType and InputType.TYPE_MASK_VARIATION
        val kind = node.inputType and InputType.TYPE_MASK_CLASS
        val password = kind == InputType.TYPE_CLASS_TEXT && variation in setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD) ||
            kind == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        return node.isEditable && node.isFocused && node.isEnabled && !node.isPassword && !password && node.packageName?.toString() in allowed
    }
    override fun onInterrupt() { lastApplied = null }
    override fun onDestroy() {
        ExpanderHealth.connected.value = false
        ExpanderHealth.preferences(this).unregisterOnSharedPreferenceChangeListener(listener)
        scope.cancel()
        super.onDestroy()
    }
}
