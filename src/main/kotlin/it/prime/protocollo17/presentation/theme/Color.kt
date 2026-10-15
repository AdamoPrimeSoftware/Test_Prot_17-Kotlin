package it.prime.protocollo17.presentation.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Palette ripresa da LocalDesktopAi (U11: stesso aspetto della shell), costruita a mano per controllo fine e non
// generata da Material Theme Builder: indigo come primario, violet come secondario, sky come accento.

// Primary: Indigo
private val Indigo100 = Color(0xFFE0E7FF)
private val Indigo300 = Color(0xFFA5B4FC)
private val Indigo400 = Color(0xFF818CF8)

/** Primario del brand: è anche il fondo dell'icona delle finestre (AppIcon). */
internal val Indigo600 = Color(0xFF4F46E5)
private val Indigo800 = Color(0xFF3730A3)
private val Indigo950 = Color(0xFF1E1B4B)

// Secondary: Violet
private val Violet100 = Color(0xFFEDE9FE)
private val Violet300 = Color(0xFFC4B5FD)
private val Violet600 = Color(0xFF7C3AED)
private val Violet800 = Color(0xFF5B21B6)
private val Violet950 = Color(0xFF2E1065)

// Tertiary: Sky
private val Sky100 = Color(0xFFE0F2FE)
private val Sky300 = Color(0xFF7DD3FC)
private val Sky500 = Color(0xFF0EA5E9)
private val Sky700 = Color(0xFF075985)
private val Sky900 = Color(0xFF0C4A6E)

// Neutri leggermente tinti verso il viola, per coerenza col brand
private val NeutralBg = Color(0xFFFAFAFC)
private val NeutralSurface = Color(0xFFFFFFFF)
private val NeutralLow1 = Color(0xFFF5F4F9)
private val NeutralLow2 = Color(0xFFEEEDF3)
private val NeutralLow3 = Color(0xFFE7E6ED)
private val NeutralOutline = Color(0xFFC4C3CC)
private val NeutralOutlineVariant = Color(0xFFE0DFE6)
private val NeutralText = Color(0xFF18181B)
private val NeutralTextVariant = Color(0xFF4B4B53)

private val NeutralBgDark = Color(0xFF0A0A0F)
private val NeutralSurfaceDark = Color(0xFF13131A)
private val NeutralDark1 = Color(0xFF1A1A23)
private val NeutralDark2 = Color(0xFF22222B)
private val NeutralDark3 = Color(0xFF2A2A35)
private val NeutralOutlineDark = Color(0xFF565660)
private val NeutralOutlineVariantDark = Color(0xFF36363F)
private val NeutralTextDark = Color(0xFFE5E5EA)
private val NeutralTextVariantDark = Color(0xFFB5B4BD)

// Error
private val Red600 = Color(0xFFDC2626)
private val Red100 = Color(0xFFFEE2E2)
private val Red300 = Color(0xFFFCA5A5)
private val Red900 = Color(0xFF7F1D1D)

val LightColorScheme: ColorScheme = lightColorScheme(
    primary = Indigo600,
    onPrimary = Color.White,
    primaryContainer = Indigo100,
    onPrimaryContainer = Indigo950,
    inversePrimary = Indigo300,

    secondary = Violet600,
    onSecondary = Color.White,
    secondaryContainer = Violet100,
    onSecondaryContainer = Violet950,

    tertiary = Sky500,
    onTertiary = Color.White,
    tertiaryContainer = Sky100,
    onTertiaryContainer = Sky900,

    background = NeutralBg,
    onBackground = NeutralText,

    surface = NeutralSurface,
    onSurface = NeutralText,
    surfaceVariant = NeutralLow1,
    onSurfaceVariant = NeutralTextVariant,
    surfaceTint = Indigo600,

    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFAF9FD),
    surfaceContainer = NeutralLow1,
    surfaceContainerHigh = NeutralLow2,
    surfaceContainerHighest = NeutralLow3,

    inverseSurface = Color(0xFF2A2A35),
    inverseOnSurface = NeutralTextDark,

    outline = NeutralOutline,
    outlineVariant = NeutralOutlineVariant,

    error = Red600,
    onError = Color.White,
    errorContainer = Red100,
    onErrorContainer = Red900,

    scrim = Color.Black,
)

val DarkColorScheme: ColorScheme = darkColorScheme(
    primary = Indigo300,
    onPrimary = Indigo950,
    primaryContainer = Indigo800,
    onPrimaryContainer = Indigo100,
    inversePrimary = Indigo600,

    secondary = Violet300,
    onSecondary = Violet950,
    secondaryContainer = Violet800,
    onSecondaryContainer = Violet100,

    tertiary = Sky300,
    onTertiary = Color(0xFF082F49),
    tertiaryContainer = Sky700,
    onTertiaryContainer = Sky100,

    background = NeutralBgDark,
    onBackground = NeutralTextDark,

    surface = NeutralSurfaceDark,
    onSurface = NeutralTextDark,
    surfaceVariant = NeutralDark2,
    onSurfaceVariant = NeutralTextVariantDark,
    surfaceTint = Indigo400,

    surfaceContainerLowest = Color(0xFF0A0A10),
    surfaceContainerLow = Color(0xFF14141B),
    surfaceContainer = NeutralDark1,
    surfaceContainerHigh = NeutralDark2,
    surfaceContainerHighest = NeutralDark3,

    inverseSurface = NeutralLow3,
    inverseOnSurface = NeutralText,

    outline = NeutralOutlineDark,
    outlineVariant = NeutralOutlineVariantDark,

    error = Red300,
    onError = Red900,
    errorContainer = Red900,
    onErrorContainer = Red100,

    scrim = Color.Black,
)
