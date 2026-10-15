package it.prime.protocollo17.presentation.components

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import it.prime.protocollo17.presentation.theme.PreviewTheme

/**
 * Sezione delle finestre Opzioni e Simulatore (stile della pagina impostazioni di LocalDesktopAi): titolo, azione
 * opzionale a destra, divisore e contenuto. Una card per argomento rende filtrabile per parole chiave la pagina.
 */
@Composable
fun SettingsCard(
    title: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                action?.invoke()
            }
            Spacer(Modifier.height(2.dp))
            HorizontalDivider(modifier = Modifier.padding(bottom = 12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        }
    }
}

@Suppress("unused")
@Preview
@Composable
private fun SettingsCardPreview() {
    PreviewTheme {
        Column(Modifier.padding(16.dp)) {
            SettingsCard(title = "Comportamento", action = { TextButton(onClick = {}) { Text("Ripristina") } }) {
                Text("Contenuto della sezione.", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
