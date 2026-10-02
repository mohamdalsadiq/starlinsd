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

// Slotra brand: deep green night + mint primary + gold accent (the established
// identity documented in README). One calm scheme, no neon glow.
private val SlotraDarkColors =
  darkColorScheme(
    primary = SlotraMint,
    onPrimary = SlotraOnAccent,
    primaryContainer = Color(0xFF10332A),
    onPrimaryContainer = SlotraMintBright,
    inversePrimary = SlotraMintDeep,
    secondary = SlotraMintBright,
    onSecondary = SlotraOnAccent,
    secondaryContainer = Color(0xFF14302B),
    onSecondaryContainer = SlotraText,
    tertiary = SlotraGold,
    onTertiary = SlotraOnAccent,
    tertiaryContainer = Color(0xFF352A14),
    onTertiaryContainer = SlotraGold,
    background = SlotraNight,
    onBackground = SlotraText,
    surface = SlotraSurface,
    onSurface = SlotraText,
    surfaceVariant = SlotraSurfaceDeep,
    onSurfaceVariant = SlotraTextDim,
    surfaceTint = SlotraMint,
    inverseSurface = SlotraText,
    inverseOnSurface = SlotraNight,
    error = SlotraRed,
    onError = SlotraOnAccent,
    errorContainer = Color(0xFF3A1D22),
    onErrorContainer = Color(0xFFFFD9DE),
    outline = SlotraBorder,
    outlineVariant = SlotraBorderSoft,
    scrim = Color(0xCC000000),
    surfaceBright = SlotraSurfaceRaised,
    surfaceDim = SlotraNightDeep,
    surfaceContainer = SlotraSurfaceDeep,
    surfaceContainerHigh = Color(0xFF14231F),
    surfaceContainerHighest = SlotraSurfaceRaised,
    surfaceContainerLow = Color(0xFF0C1714),
    surfaceContainerLowest = SlotraNightDeep,
  )

// Fallback light scheme keeps the same identity for devices/settings that force light mode.
private val SlotraLightColors =
  lightColorScheme(
    primary = SlotraLightPrimary,
    onPrimary = SlotraLightOnPrimary,
    primaryContainer = Color(0xFFD3F2E4),
    onPrimaryContainer = Color(0xFF04301F),
    secondary = SlotraMintDeep,
    onSecondary = SlotraLightOnPrimary,
    tertiary = Color(0xFF8A6A22),
    onTertiary = SlotraLightOnPrimary,
    background = SlotraLightBg,
    onBackground = Color(0xFF0A1512),
    surface = SlotraLightSurface,
    onSurface = Color(0xFF0A1512),
    surfaceVariant = Color(0xFFE2EFE9),
    onSurfaceVariant = Color(0xFF3D5A4E),
    outline = Color(0xFFB8CFC4),
    outlineVariant = Color(0xFFD6E6DD),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
  )

// Radii: cards 20dp, dialogs 28dp, chips/inputs 12–16dp.
val SlotraShapes =
  Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
  )

/**
 * The single Slotra design system entry point: the dark identity by default,
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
