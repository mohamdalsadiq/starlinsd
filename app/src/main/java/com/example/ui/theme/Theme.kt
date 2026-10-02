package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

// Slotra brand: deep navy night + electric blue (Muse UI v2, approved 2026-10-02).
private val SlotraDarkColors =
  darkColorScheme(
    primary = SlotraBlue,
    onPrimary = SlotraOnAccent,
    primaryContainer = Color(0xFF102A3F),
    onPrimaryContainer = SlotraBlueBright,
    inversePrimary = SlotraBlueDeep,
    secondary = SlotraBlueBright,
    onSecondary = SlotraOnAccent,
    secondaryContainer = Color(0xFF14243C),
    onSecondaryContainer = SlotraText,
    tertiary = SlotraGreen,
    onTertiary = SlotraOnAccent,
    tertiaryContainer = Color(0xFF12352A),
    onTertiaryContainer = SlotraGreen,
    background = SlotraNavy,
    onBackground = SlotraText,
    surface = SlotraSurface,
    onSurface = SlotraText,
    surfaceVariant = SlotraSurfaceDeep,
    onSurfaceVariant = SlotraTextDim,
    surfaceTint = SlotraBlue,
    inverseSurface = SlotraText,
    inverseOnSurface = SlotraNavy,
    error = SlotraRed,
    onError = SlotraOnAccent,
    errorContainer = Color(0xFF3A1D27),
    onErrorContainer = Color(0xFFFFD9DF),
    outline = SlotraBorder,
    outlineVariant = SlotraBorderSoft,
    scrim = Color(0xCC000000),
    surfaceBright = SlotraSurfaceRaised,
    surfaceDim = SlotraNavyDeep,
    surfaceContainer = SlotraSurfaceDeep,
    surfaceContainerHigh = Color(0xFF131E34),
    surfaceContainerHighest = SlotraSurfaceRaised,
    surfaceContainerLow = Color(0xFF0C1322),
    surfaceContainerLowest = SlotraNavyDeep,
  )

// Fallback light scheme keeps the same identity for devices/settings that force light mode.
private val SlotraLightColors =
  lightColorScheme(
    primary = SlotraLightPrimary,
    onPrimary = SlotraLightOnPrimary,
    primaryContainer = Color(0xFFD9EFFF),
    onPrimaryContainer = Color(0xFF04283C),
    secondary = SlotraBlue,
    onSecondary = SlotraLightOnPrimary,
    tertiary = Color(0xFF1B7C51),
    onTertiary = SlotraLightOnPrimary,
    background = SlotraLightBg,
    onBackground = Color(0xFF0A0E1A),
    surface = SlotraLightSurface,
    onSurface = Color(0xFF0A0E1A),
    surfaceVariant = Color(0xFFE3ECF5),
    onSurfaceVariant = Color(0xFF3D5470),
    outline = Color(0xFFB9C9DD),
    outlineVariant = Color(0xFFD7E3EF),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
  )

// Muse v2 radii: cards 20dp, dialogs 28dp, chips/inputs 12–16dp.
val SlotraShapes =
  Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
  )

/**
 * The single Slotra design system entry point: v2 dark identity by default,
 * shared shapes/typography, and RTL layout for every screen.
 */
@Composable
fun SlotraTheme(darkTheme: Boolean = true, content: @Composable () -> Unit) {
  MaterialTheme(
    colorScheme = if (darkTheme) SlotraDarkColors else SlotraLightColors,
    typography = Typography,
    shapes = SlotraShapes,
  ) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl, content = content)
  }
}
