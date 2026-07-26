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
            
            // Look for matching shortcut when user types space
            val words = text.split(Regex("\\s+"))
            val hasTrailingSpace = text.takeLastWhile { it.isWhitespace() }.isNotEmpty()
            
            if (hasTrailingSpace && words.size > 1) {
                val lastTypedWord = words[words.size - 2] // Because split keeps empty string at end if there's trailing space
                
                val matched = shortcuts.find { it.keyword == lastTypedWord }
                if (matched != null) {
                    val matchResult = Regex("(^|\\s)(${Regex.escape(matched.keyword)})(\\s)$").find(text)
                    if (matchResult != null) {
                        val prefix = text.substring(0, matchResult.range.first + matchResult.groups[1]!!.value.length)
                        val expanded = processPhrase(matched.phrase)
                        val suffix = matchResult.groups[3]!!.value
                        val newText = prefix + expanded + suffix
                        
                        val args = Bundle().apply {
                            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
                        }
                        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                        
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
