package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.example.ui.ManagerApp

class MainActivity : ComponentActivity() {
    private val model: MainViewModel by viewModels()
    private var requestedSession by mutableStateOf<String?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedSession = intent.getStringExtra("SESSION_ID")
        setContent {
            val colors = if (isSystemInDarkTheme()) darkColorScheme(primary = Color(0xFF76D9C3), secondary = Color(0xFFE9B66D))
                else lightColorScheme(primary = Color(0xFF006B58), onPrimary = Color.White,
                    primaryContainer = Color(0xFFD8F1E9), secondary = Color(0xFF8A561B),
                    background = Color(0xFFF7F8F3), surface = Color(0xFFF7F8F3))
            MaterialTheme(colorScheme = colors) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    ManagerApp(model, requestedSession)
                }
            }
        }
    }
    override fun onResume() { super.onResume(); model.refresh() }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); requestedSession = intent.getStringExtra("SESSION_ID") }
}
