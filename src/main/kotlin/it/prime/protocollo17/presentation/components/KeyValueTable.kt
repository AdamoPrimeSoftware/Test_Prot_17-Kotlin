package it.prime.protocollo17.presentation.components

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import it.prime.protocollo17.presentation.theme.PreviewTheme

/** Riga della tabella: etichetta, valore (monospace), coda opzionale (posizione "13/19"), evidenza. */
data class KeyValueRow(val label: String, val value: String, val trailing: String? = null, val emphasis: Boolean = false)

/**
 * Tabella etichetta / valore monospace / posizione, dentro un `SelectionContainer`: chi confronta un esito con lo
 * scontrino o con il log della DLL deve poter selezionare e copiare un valore (STAN, codice autorizzazione) senza
 * riscriverlo. La posizione accanto al valore è ciò che rende quest'app uno strumento di test e non una cassa.
 */
@Composable
fun KeyValueTable(rows: List<KeyValueRow>, modifier: Modifier = Modifier) {
    SelectionContainer(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            rows.forEach { row ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Text(
                        text = row.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(170.dp).padding(end = 8.dp),
                    )
                    Text(
                        text = row.value,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = if (row.emphasis) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.weight(1f),
                    )
                    if (row.trailing != null) {
                        Text(
                            text = row.trailing,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Suppress("unused")
@Preview
@Composable
private fun KeyValueTablePreview() {
    PreviewTheme {
        KeyValueTable(
            rows = listOf(
                KeyValueRow("PAN", "***************4768", "13/19", emphasis = true),
                KeyValueRow("Modalità lettura", "Contactless chip (CLI)", "32/3"),
                KeyValueRow("Codice autorizzazione", "R48432", "35/6"),
                KeyValueRow("Opzioni", "stx/WIRE rx=TOLERANT/ANY ack=IMPLICIT_GO_AHEAD conn=PER_COMMAND to=2000/180000/5000 nak=3"),
            ),
            modifier = Modifier.padding(12.dp).width(420.dp),
        )
    }
}
