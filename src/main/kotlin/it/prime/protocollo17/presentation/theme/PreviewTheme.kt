package it.prime.protocollo17.presentation.theme

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * Tema per le `@Preview` dell'IDE: NON dipende da Koin (a differenza di [AppTheme], che inietta
 * SettingsRepository per il tema scelto). Il renderer delle anteprime non ha un container Koin: è anche il
 * motivo per cui ogni `XScreen` prende `(state, onAction)` e mai un ViewModel.
 */
@Composable
fun PreviewTheme(darkTheme: Boolean = false, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = AppTypography,
        shapes = AppShapes,
    ) {
        CompositionLocalProvider(
            LocalScrollbarStyle provides appScrollbarStyle(MaterialTheme.colorScheme.onSurfaceVariant),
        ) {
            Surface(color = MaterialTheme.colorScheme.background) {
                content()
            }
        }
    }
}

@Suppress("unused")
@Preview
@Composable
private fun PreviewThemePreview() {
    PreviewTheme(darkTheme = true) {
        Text("Anteprima con il tema scuro, senza Koin")
    }
}
