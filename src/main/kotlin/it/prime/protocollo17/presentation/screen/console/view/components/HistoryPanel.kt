package it.prime.protocollo17.presentation.screen.console.view.components

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import it.prime.protocollo17.presentation.components.AppVerticalScrollbar
import it.prime.protocollo17.presentation.components.CommandLetterChip
import it.prime.protocollo17.presentation.components.CopyIconButton
import it.prime.protocollo17.presentation.components.copyText
import it.prime.protocollo17.presentation.components.toneColor
import it.prime.protocollo17.presentation.model.HistoryRowView
import it.prime.protocollo17.presentation.model.ResultTone
import it.prime.protocollo17.presentation.screen.console.viewModel.ConsoleAction
import it.prime.protocollo17.presentation.theme.PreviewTheme
import kotlinx.coroutines.launch

/**
 * Scheda Cronologia (§6.7, R25): il log decodificato di tutte le operazioni della sessione, come il riquadro "Log"
 * dell'app WPF, che il solo ultimo esito per comando non sostituisce. Più recenti in alto; un clic mostra l'esito
 * completo nella scheda Esito; menu contestuale per copiare una riga.
 */
@Composable
fun HistoryPanel(rows: List<HistoryRowView>, historyText: String, onAction: (ConsoleAction) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${rows.size} operazioni", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f).padding(start = 8.dp))
            CopyIconButton(text = historyText, contentDescription = "Copia la cronologia")
            TextButton(onClick = { onAction(ConsoleAction.ClearHistory) }, enabled = rows.isNotEmpty()) {
                Icon(Icons.Filled.DeleteSweep, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Svuota cronologia")
            }
        }
        HorizontalDivider()
        if (rows.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("Nessuna operazione in questa sessione.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Column
        }
        val listState = rememberLazyListState()
        val clipboard = LocalClipboard.current
        val scope = rememberCoroutineScope()
        Box(Modifier.fillMaxSize()) {
            LazyColumn(state = listState, contentPadding = PaddingValues(end = 12.dp)) {
                items(rows, key = { it.id }) { row ->
                    ContextMenuArea(items = {
                        listOf(
                            ContextMenuItem("Copia riga") { scope.launch { clipboard.copyText(row.asText()) } },
                        )
                    }) {
                        HistoryRow(row, onClick = { onAction(ConsoleAction.ShowHistoryItem(row.id)) })
                    }
                }
            }
            AppVerticalScrollbar(listState)
        }
    }
}

@Composable
private fun HistoryRow(row: HistoryRowView, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(row.time, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.width(10.dp))
        CommandLetterChip(row.letter, danger = row.danger, size = 22.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            text = row.headline,
            color = toneColor(row.tone),
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        row.amount?.let { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
    }
}

private fun HistoryRowView.asText(): String = "$time $letter $headline${amount?.let { " $it" }.orEmpty()}"

@Suppress("unused")
@Preview
@Composable
private fun HistoryPanelPreview() {
    PreviewTheme {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HistoryPanel(
                rows = listOf(
                    HistoryRowView(3, "09:41:02", 'G', false, "APPROVATO", ResultTone.SUCCESS, null),
                    HistoryRowView(2, "09:37:10", 'P', true, "ESITO SCONOSCIUTO", ResultTone.WARNING, "0,10 €"),
                    HistoryRowView(1, "09:35:14", 's', false, "STATO: Operativo (2)", ResultTone.INFO, null),
                ),
                historyText = "", onAction = {}, modifier = Modifier.height(220.dp).width(420.dp),
            )
            HistoryPanel(rows = emptyList(), historyText = "", onAction = {}, modifier = Modifier.height(120.dp).width(420.dp))
        }
    }
}
