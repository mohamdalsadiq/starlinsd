package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Slotra brand: deep navy night + electric blue (approved 2026-10-02).
private val DarkColorScheme =
  darkColorScheme(
    primary = SlotraBlue,
    onPrimary = Color.White,
    primaryContainer = SlotraSurface,
    onPrimaryContainer = SlotraBlueBright,
    secondary = SlotraBlueBright,
    onSecondary = SlotraNavy,
    tertiary = SlotraGreen,
    onTertiary = SlotraNavy,
    background = SlotraNavy,
    onBackground = SlotraText,
    surface = SlotraSurface,
    onSurface = SlotraText,
    surfaceVariant = SlotraSurfaceDeep,
    onSurfaceVariant = SlotraMuted,
    outline = SlotraBorder,
    error = Color(0xFFFFB4AB),
  )

private val LightColorScheme =
  lightColorScheme(
    primary = SlotraLightPrimary,
    onPrimary = SlotraLightOnPrimary,
    primaryContainer = SlotraLightSurface,
    onPrimaryContainer = SlotraLightPrimary,
    secondary = SlotraBlue,
    tertiary = Color(0xFF2E7D32),
    background = SlotraLightBg,
    onBackground = Color(0xFF0A0E1A),
    surface = SlotraLightSurface,
    onSurface = Color(0xFF0A0E1A),
    surfaceVariant = Color(0xFFE3ECF5),
    onSurfaceVariant = Color(0xFF3D5470),
    outline = Color(0xFFB9C9DD),
  )

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  // Dynamic color is available on Android 12+
  dynamicColor: Boolean = true,
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }

      darkTheme -> DarkColorScheme
      else -> LightColorScheme
    }

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
