package it.prime.protocollo17.presentation.components

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.prime.protocollo17.presentation.theme.PreviewTheme

/**
 * Intestazione di gruppo in maiuscoletto (PAGAMENTI, PREAUTORIZZAZIONI, …), come le sotto-voci della barra di
 * LocalDesktopAi: un solo stile per tutte le intestazioni di gruppo dell'app.
 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.6.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@Suppress("unused")
@Preview
@Composable
private fun SectionLabelPreview() {
    PreviewTheme {
        Column(Modifier.padding(12.dp)) {
            SectionLabel("Pagamenti")
            SectionLabel("Preautorizzazioni")
        }
    }
}
