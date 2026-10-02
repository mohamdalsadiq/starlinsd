package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.*
import com.example.ui.ManagerApp
import com.example.ui.theme.SlotraTheme

class MainActivity : ComponentActivity() {
    private val model: MainViewModel by viewModels()
    private var requestedSession by mutableStateOf<String?>(null)
    private var requestedConfirmation by mutableStateOf<String?>(null)
    private var requestedRecovery by mutableStateOf<String?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedSession = intent.getStringExtra("SESSION_ID")
        requestedConfirmation = intent.getStringExtra("DEVICE_CONFIRMATION")
        requestedRecovery = intent.getStringExtra("DEVICE_RECOVERY")
        setContent {
            ManagerTheme { ManagerApp(model, requestedSession, requestedConfirmation, requestedRecovery) }
        }
    }
    override fun onResume() { super.onResume(); model.refresh() }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        requestedSession = intent.getStringExtra("SESSION_ID")
        requestedConfirmation = intent.getStringExtra("DEVICE_CONFIRMATION")
        requestedRecovery = intent.getStringExtra("DEVICE_RECOVERY")
    }
}

/**
 * App theme entry point (kept in this package for the UI tests).
 * Delegates to the single Slotra design system: the established green-night /
 * mint / gold identity, shared type scale and radii, RTL layout.
 */
@Composable
fun ManagerTheme(content: @Composable () -> Unit) = SlotraTheme(content = content)
