package it.prime.protocollo17.presentation.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import it.prime.protocollo17.data.local.settings.SettingsRepository
import it.prime.protocollo17.domain.model.settings.ThemeMode
import org.koin.compose.koinInject

/**
 * Stile delle scrollbar preso dal tema invece che dal default.
 *
 * Il default di Compose Desktop è NERO al 12%: sul tema scuro (`surfaceContainerLow` #14141B) è nero su nero, la
 * scrollbar c'è ma non si vede. Tutte le scrollbar dell'app passano da `AppVerticalScrollbar`/`ScrollableColumn`,
 * che leggono questo CompositionLocal. `minimalHeight` più alta del default: su un log di 5 000 righe il pollice
 * diventerebbe un trattino impossibile da prendere col mouse.
 */
fun appScrollbarStyle(color: Color): ScrollbarStyle = ScrollbarStyle(
    minimalHeight = 24.dp,
    thickness = 8.dp,
    shape = RoundedCornerShape(4.dp),
    hoverDurationMillis = 300,
    unhoverColor = color.copy(alpha = 0.32f),
    hoverColor = color.copy(alpha = 0.62f),
)

/**
 * Tema dell'app: l'unico punto che legge `ui.themeMode` (via SettingsRepository), per ogni finestra.
 *
 * Ogni slot è animato insieme al suo colore "on": animare un contenitore e far saltare il testo al colore
 * d'arrivo rende il testo illeggibile per i 350 ms della transizione (chiaro su chiaro), come accadeva in
 * LocalDesktopAi prima della regola.
 */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val settingsRepository: SettingsRepository = koinInject()
    val settings by settingsRepository.flow.collectAsState()
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (settings.ui.themeMode) {
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
        ThemeMode.System -> systemDark
    }
    val target = if (darkTheme) DarkColorScheme else LightColorScheme
    val spec = tween<Color>(durationMillis = 350)

    @Composable
    fun animate(color: Color, label: String): Color = animateColorAsState(color, spec, label = label).value

    val animated = target.copy(
        primary = animate(target.primary, "primary"),
        onPrimary = animate(target.onPrimary, "onPrimary"),
        primaryContainer = animate(target.primaryContainer, "primaryContainer"),
        onPrimaryContainer = animate(target.onPrimaryContainer, "onPrimaryContainer"),
        inversePrimary = animate(target.inversePrimary, "inversePrimary"),
        secondary = animate(target.secondary, "secondary"),
        onSecondary = animate(target.onSecondary, "onSecondary"),
        secondaryContainer = animate(target.secondaryContainer, "secondaryContainer"),
        onSecondaryContainer = animate(target.onSecondaryContainer, "onSecondaryContainer"),
        tertiary = animate(target.tertiary, "tertiary"),
        onTertiary = animate(target.onTertiary, "onTertiary"),
        tertiaryContainer = animate(target.tertiaryContainer, "tertiaryContainer"),
        onTertiaryContainer = animate(target.onTertiaryContainer, "onTertiaryContainer"),
        background = animate(target.background, "background"),
        onBackground = animate(target.onBackground, "onBackground"),
        surface = animate(target.surface, "surface"),
        onSurface = animate(target.onSurface, "onSurface"),
        surfaceVariant = animate(target.surfaceVariant, "surfaceVariant"),
        onSurfaceVariant = animate(target.onSurfaceVariant, "onSurfaceVariant"),
        surfaceContainer = animate(target.surfaceContainer, "surfaceContainer"),
        surfaceContainerHigh = animate(target.surfaceContainerHigh, "surfaceContainerHigh"),
        surfaceContainerHighest = animate(target.surfaceContainerHighest, "surfaceContainerHighest"),
        surfaceContainerLow = animate(target.surfaceContainerLow, "surfaceContainerLow"),
        surfaceContainerLowest = animate(target.surfaceContainerLowest, "surfaceContainerLowest"),
        surfaceTint = animate(target.surfaceTint, "surfaceTint"),
        inverseSurface = animate(target.inverseSurface, "inverseSurface"),
        inverseOnSurface = animate(target.inverseOnSurface, "inverseOnSurface"),
        outline = animate(target.outline, "outline"),
        outlineVariant = animate(target.outlineVariant, "outlineVariant"),
        error = animate(target.error, "error"),
        onError = animate(target.onError, "onError"),
        errorContainer = animate(target.errorContainer, "errorContainer"),
        onErrorContainer = animate(target.onErrorContainer, "onErrorContainer"),
    )

    MaterialTheme(colorScheme = animated, typography = AppTypography, shapes = AppShapes) {
        CompositionLocalProvider(
            LocalScrollbarStyle provides appScrollbarStyle(animated.onSurfaceVariant),
            content = content,
        )
    }
}

// ─── Preview ──────────────────────────────────────────────────────────────────────

/** Campioni dei colori di stato sui due temi: verifica a vista del contrasto di verde, ambra e rosso. */
@Suppress("unused")
@Preview
@Composable
private fun ThemeSwatchesPreview() {
    Row {
        listOf(false, true).forEach { dark ->
            PreviewTheme(darkTheme = dark) {
                Column(modifier = Modifier.padding(12.dp)) {
                    listOf(
                        "APPROVATO" to SuccessGreen,
                        "ESITO SCONOSCIUTO" to WarningAmber,
                        "RIFIUTATO" to MaterialTheme.colorScheme.error,
                        "Primario" to MaterialTheme.colorScheme.primary,
                    ).forEach { (label, color) ->
                        Text(label, color = color, style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
        }
    }
}
