package com.example.service

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.db.AppDatabase
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class TextExpanderService : AccessibilityService() {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private var shortcuts = listOf<com.example.db.Shortcut>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        val db = AppDatabase.getDatabase(this)
        scope.launch {
            db.shortcutDao().getAll().collect {
                shortcuts = it
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        
        // Find focused input node across window or fallback to event source
        val activeNode = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: event.source

        if (activeNode == null) return

        val text = activeNode.text?.toString() 
            ?: event.text?.joinToString("") 
            ?: return

        if (shortcuts.isEmpty()) return

        // Expand when space or newline typed, or check last word
        if (text.endsWith(" ") || text.endsWith("\n") || text.isNotEmpty()) {
            val isEndingSpaceOrNewline = text.endsWith(" ") || text.endsWith("\n")
            val rawText = if (isEndingSpaceOrNewline) text.dropLast(1) else text
            val words = rawText.split(Regex("\\s+"))
            
            if (words.isNotEmpty()) {
                val lastWord = words.last()
                val matched = shortcuts.find { it.keyword == lastWord }
                
                if (matched != null && (isEndingSpaceOrNewline || lastWord == matched.keyword)) {
                    val prefix = rawText.substring(0, rawText.length - lastWord.length)
                    val expandedPhrase = processPhrase(matched.phrase)
                    val suffix = if (isEndingSpaceOrNewline) text.takeLast(1) else " "
                    val newText = prefix + expandedPhrase + suffix

                    val args = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
                    }
                    val setSuccess = activeNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

                    if (setSuccess) {
                        val selectionArgs = Bundle().apply {
                            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newText.length)
                            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newText.length)
                        }
                        activeNode.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectionArgs)
                    }
                }
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }

    private fun processPhrase(phrase: String): String {
        var result = phrase
        val cal = Calendar.getInstance()
        
        // Process %date%
        val dateFormat = SimpleDateFormat("yyyy/MM/dd", Locale("ar"))
        result = result.replace("%date%", dateFormat.format(cal.time))
        
        // Process %day%
        val dayFormat = SimpleDateFormat("EEEE", Locale("ar"))
        result = result.replace("%day%", dayFormat.format(cal.time))

        // Process %time% and %time+Xh%
        val regex = Regex("%time(?:\\+(\\d+)h)?%")
        return regex.replace(result) { match ->
            val hoursToAdd = match.groups[1]?.value?.toIntOrNull() ?: 0
            val timeCal = Calendar.getInstance()
            timeCal.add(Calendar.HOUR_OF_DAY, hoursToAdd)
            val sdf = SimpleDateFormat("hh:mm a", Locale("ar"))
            sdf.format(timeCal.time)
        }
    }
}
