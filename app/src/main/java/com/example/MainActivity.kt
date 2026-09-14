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
            ManagerTheme { ManagerApp(model, requestedSession) }
        }
    }
    override fun onResume() { super.onResume(); model.refresh() }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); requestedSession = intent.getStringExtra("SESSION_ID") }
}


@Composable
fun ManagerTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) darkColorScheme(primary = Color(0xFF76D9C3), onPrimary = Color(0xFF00382D),
        surfaceContainer = Color(0xFF17241F), secondaryContainer = Color(0xFF34443E), onSecondaryContainer = Color(0xFFB0F1DF),
        surfaceContainerHighest = Color(0xFF26322E), surfaceVariant = Color(0xFF34443E), onSurfaceVariant = Color(0xFFBFCBC5),
        primaryContainer = Color(0xFF005140), onPrimaryContainer = Color(0xFFB0F1DF), secondary = Color(0xFFE9B66D))
        else lightColorScheme(primary = Color(0xFF006B58), onPrimary = Color.White,
            primaryContainer = Color(0xFFD8F1E9), onPrimaryContainer = Color(0xFF00382D), secondary = Color(0xFF8A561B),
            surfaceContainer = Color(0xFFEEF2EA), secondaryContainer = Color(0xFFD8F1E9), onSecondaryContainer = Color(0xFF00382D),
            outline = Color(0xFF72837A), outlineVariant = Color(0xFFD0DAD2), background = Color(0xFFF7F8F3), surface = Color(0xFFF7F8F3),
            surfaceContainerHighest = Color(0xFFE9EEE7), surfaceVariant = Color(0xFFE1E8E0), onSurfaceVariant = Color(0xFF4F6259))
    MaterialTheme(colorScheme = colors) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl, content = content)
    }
}
