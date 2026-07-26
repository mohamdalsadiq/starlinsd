package com.example.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.os.Bundle
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
        if (event?.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            val node = event.source ?: return
            val text = event.text?.joinToString("") ?: return
            
            // Look for matching shortcut when user types space or newline
            if (text.endsWith(" ") || text.endsWith("\n")) {
                val words = text.trimEnd().split(Regex("\\s+"))
                if (words.isNotEmpty()) {
                    val lastWord = words.last()
                    val matched = shortcuts.find { it.keyword == lastWord }
                    
                    if (matched != null) {
                        // Reconstruct text
                        val beforeWord = text.substring(0, text.length - lastWord.length - 1)
                        val expanded = processPhrase(matched.phrase)
                        val suffix = text.substring(text.length - 1)
                        val newText = beforeWord + expanded + suffix
                        
                        val args = Bundle().apply {
                            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
                        }
                        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                        
                        // Try to set cursor to the end
                        val selectionArgs = Bundle().apply {
                            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newText.length)
                            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newText.length)
                        }
                        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectionArgs)
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
