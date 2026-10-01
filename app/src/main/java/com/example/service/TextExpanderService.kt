package com.example.service

import android.accessibilityservice.AccessibilityService
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
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
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow

object ExpanderHealth {
    val connected = MutableStateFlow(false)
    fun preferences(context: Context): SharedPreferences = context.getSharedPreferences("expander", Context.MODE_PRIVATE)
    fun allowed(context: Context): Set<String> = preferences(context).getStringSet("apps", emptySet())?.toSet().orEmpty()

    /** Phase 4: the recovery shortcut keyword; empty default falls back to MainViewModel's default. */
    fun recoveryKeyword(context: Context): String =
        preferences(context).getString("recovery_keyword", null)?.takeIf { it.isNotBlank() }
            ?: com.example.MainViewModel.DEFAULT_RECOVERY_KEYWORD

    /** Cold Flow of the recovery keyword (current value, then changes) for the settings UI. */
    fun recoveryKeywordFlow(preferences: SharedPreferences): kotlinx.coroutines.flow.Flow<String> =
        kotlinx.coroutines.flow.callbackFlow {
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == "recovery_keyword") trySend(preferences.getString("recovery_keyword", null).orEmpty())
            }
            preferences.registerOnSharedPreferenceChangeListener(listener)
            trySend(preferences.getString("recovery_keyword", null).orEmpty())
            awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
        }
}

/**
 * Recovery keyword handling for the Phase 4 password-change flow: when the typed
 * keyword matches the configured recovery keyword (default "استعادة"), the service
 * opens the recovery screen instead of creating a subscription — Recovery never
 * registers subscriptions, so it must never ride the subscription path.
 */
internal fun isRecoveryKeyword(keyword: String, context: Context): Boolean = keyword == ExpanderHealth.recoveryKeyword(context)

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
        // Recovery keyword: open the recovery screen instead of any subscription flow.
        if (isRecoveryKeyword(match.keyword, this@TextExpanderService)) {
            processing = true
            val open = PendingIntent.getActivity(this@TextExpanderService, 2103,
                Intent(this@TextExpanderService, com.example.MainActivity::class.java).putExtra("DEVICE_RECOVERY", "1")
                    .setData(Uri.parse("slotra://recovery")), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            try { open.send() } catch (_: PendingIntent.CanceledException) { }
            Toast.makeText(this@TextExpanderService, "افتح شاشة استعادة الاشتراكات", Toast.LENGTH_SHORT).show()
            lastApplied = node.windowId to text
            processing = false
            return
        }
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
                    // The shortcut IS the subscription workflow: bind the new session to the
                    // device being sold to (best-effort, never throws). Binding is
                    // DETERMINISTIC: the session's subscriber stamp "[N]" (saved by the
                    // operator as the router device name right after this expansion)
                    // is matched against live device names — never guessed.
                    // The rename is saved after the expansion, so the first attempt
                    // usually misses; a bounded retry catches it, and the periodic
                    // sweep in SubscriptionAlarms keeps trying afterwards.
                    SubscriptionAlarms.refresh(this@TextExpanderService)
                    Toast.makeText(this@TextExpanderService, "تم تسجيل ${session.client}", Toast.LENGTH_SHORT).show()
                    val service = this@TextExpanderService
                    val boundSession = session
                    scope.launch {
                        val bound = runCatching {
                            com.example.data.bindShortcutWithRetry(service, boundSession.id, boundSession.reference, boundSession.started)
                        }.getOrNull()
                        if (bound != null) {
                            Toast.makeText(service, "تم ربط ${boundSession.client} بالجهاز", Toast.LENGTH_SHORT).show()
                            SubscriptionAlarms.refresh(service)
                        }
                    }
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
