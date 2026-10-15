package it.prime.protocollo17.presentation.screen.journal.view

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.SdCardAlert
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import it.prime.protocollo17.presentation.components.AppVerticalScrollbar
import it.prime.protocollo17.presentation.components.CommandLetterChip
import it.prime.protocollo17.presentation.components.CopyIconButton
import it.prime.protocollo17.presentation.components.KeyValueRow
import it.prime.protocollo17.presentation.components.KeyValueTable
import it.prime.protocollo17.presentation.components.ResolveAttemptDialog
import it.prime.protocollo17.presentation.components.ScrollableColumn
import it.prime.protocollo17.presentation.components.SectionLabel
import it.prime.protocollo17.presentation.components.TooltipIconButton
import it.prime.protocollo17.presentation.components.WithTooltip
import it.prime.protocollo17.presentation.components.toneColor
import it.prime.protocollo17.presentation.model.FieldRow
import it.prime.protocollo17.presentation.model.ResultTone
import it.prime.protocollo17.presentation.screen.journal.viewModel.JournalAction
import it.prime.protocollo17.presentation.screen.journal.viewModel.JournalDetailView
import it.prime.protocollo17.presentation.screen.journal.viewModel.JournalDialog
import it.prime.protocollo17.presentation.screen.journal.viewModel.JournalFilter
import it.prime.protocollo17.presentation.screen.journal.viewModel.JournalRowView
import it.prime.protocollo17.presentation.screen.journal.viewModel.JournalUiState
import it.prime.protocollo17.presentation.theme.PreviewTheme
import it.prime.protocollo17.presentation.theme.WarningAmber

/**
 * Finestra Journal (§6.13): lista dei tentativi | dettaglio. Il colore della fase dice subito cosa conta
 * (approvato, rifiutato, da verificare, sconosciuto); un tentativo non scritto su disco ha la sua icona perché
 * resta bloccante solo in memoria. Sul dettaglio di un tentativo da risolvere: Ultimo esito (G) e Chiudi tentativo.
 */
@Composable
fun JournalScreen(
    state: JournalUiState,
    onAction: (JournalAction) -> Unit,
    onOpenFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        Header(state, onAction, onOpenFolder)
        HorizontalDivider()
        Row(Modifier.weight(1f).fillMaxWidth()) {
            AttemptList(state, onAction, Modifier.weight(0.42f).fillMaxHeight())
            VerticalDivider()
            Box(Modifier.weight(0.58f).fillMaxHeight()) {
                val detail = state.detail
                if (detail == null) {
                    Text(
                        "Seleziona un tentativo per vederne il dettaglio.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                    Detail(detail, state.canRunG, onAction)
                }
            }
        }
        SelectionContainer {
            Text(
                "Journal: ${state.filePath}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
    when (val dialog = state.dialog) {
        null -> Unit
        is JournalDialog.Resolve -> ResolveAttemptDialog(
            model = dialog.model,
            onResolve = { kind, note -> onAction(JournalAction.Resolve(dialog.model.attemptId, kind, note)) },
            onDismiss = { onAction(JournalAction.DismissDialog) },
        )
    }
}

@Composable
private fun Header(state: JournalUiState, onAction: (JournalAction) -> Unit, onOpenFolder: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = when (state.unresolvedCount) {
                0 -> "Nessun tentativo da risolvere"
                1 -> "1 tentativo da risolvere"
                else -> "${state.unresolvedCount} tentativi da risolvere"
            },
            style = MaterialTheme.typography.titleMedium,
            color = if (state.unresolvedCount > 0) WarningAmber else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        listOf(JournalFilter.ALL to "Tutti", JournalFilter.UNRESOLVED to "Da risolvere", JournalFilter.TODAY to "Oggi").forEach { (filter, label) ->
            FilterChip(
                selected = state.filter == filter,
                onClick = { onAction(JournalAction.SetFilter(filter)) },
                label = { Text(label) },
                modifier = Modifier.padding(end = 6.dp),
            )
        }
        TooltipIconButton(icon = Icons.Filled.FolderOpen, tooltip = "Apri la cartella del journal", onClick = onOpenFolder)
    }
}

@Composable
private fun AttemptList(state: JournalUiState, onAction: (JournalAction) -> Unit, modifier: Modifier) {
    Box(modifier) {
        if (state.rows.isEmpty()) {
            Text("Nessun tentativo.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.Center))
            return@Box
        }
        val listState = rememberLazyListState()
        LazyColumn(state = listState, contentPadding = PaddingValues(start = 6.dp, end = 14.dp, top = 4.dp, bottom = 4.dp)) {
            items(state.rows, key = { it.attemptId }) { row ->
                AttemptRow(row, selected = row.attemptId == state.selectedId, onClick = { onAction(JournalAction.Select(row.attemptId)) })
            }
        }
        AppVerticalScrollbar(listState)
    }
}

@Composable
private fun AttemptRow(row: JournalRowView, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
            .clip(RoundedCornerShape(8.dp))
            .then(if (selected) Modifier.background(MaterialTheme.colorScheme.secondaryContainer) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(row.time, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.width(8.dp))
        CommandLetterChip(row.letter ?: '?', danger = row.letter == null || row.tone == ResultTone.DANGER, size = 22.dp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(row.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                row.phaseLabel + if (row.sends > 1) " · ${row.sends} invii" else "",
                style = MaterialTheme.typography.labelMedium,
                color = toneColor(row.tone),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (row.resolved) {
            WithTooltip("Risolto dall'operatore") { Icon(Icons.Filled.CheckCircle, contentDescription = "Risolto", modifier = Modifier.size(18.dp)) }
        }
        if (!row.persisted) {
            WithTooltip("Ultimo record non scritto su disco: bloccante in memoria") {
                Icon(Icons.Filled.SdCardAlert, contentDescription = "Non scritto su disco", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun Detail(detail: JournalDetailView, canRunG: Boolean, onAction: (JournalAction) -> Unit) {
    ScrollableColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 20.dp, top = 12.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(detail.title, style = MaterialTheme.typography.titleMedium)
        if (!detail.persisted) {
            Text("Non scritto su disco: il blocco vale finché non risolvi il tentativo.", color = MaterialTheme.colorScheme.error)
        }
        if (detail.isUnresolved) {
            Surface(color = WarningAmber.copy(alpha = 0.15f), shape = MaterialTheme.shapes.small) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        when {
                            detail.needsReview -> "Esito da verificare: possibile doppio addebito o esito prima dell'ACK."
                            detail.blocksPayments -> "Esito sconosciuto: i pagamenti sono bloccati."
                            else -> "Esito sconosciuto: bloccati i comandi con la stessa risposta."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onAction(JournalAction.RunLastResult) }, enabled = canRunG) { Text("Ultimo esito (G)") }
                        OutlinedButton(onClick = { onAction(JournalAction.OpenResolve(detail.attemptId)) }) { Text("Chiudi tentativo…") }
                    }
                }
            }
        }
        KeyValueTable(rows = detail.summary.toRows())
        SectionLabel("Cronologia del tentativo")
        KeyValueTable(rows = detail.timeline.toRows())
        detail.rawResult?.let { raw ->
            SectionLabel("Esito grezzo (PAN mascherato)")
            SelectionContainer { Text(raw, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
        }
        if (detail.evidence.isNotEmpty()) {
            SectionLabel("Prove allegate")
            SelectionContainer {
                Column { detail.evidence.forEach { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) } }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Record JSONL", modifier = Modifier.weight(1f))
            CopyIconButton(text = detail.json, contentDescription = "Copia il JSON del tentativo")
        }
        SelectionContainer { Text(detail.json, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall) }
    }
}

private fun List<FieldRow>.toRows(): List<KeyValueRow> = map { KeyValueRow(label = it.label, value = it.value, trailing = it.position) }

// ─── Preview ──────────────────────────────────────────────────────────────────────

@Suppress("unused")
@Preview
@Composable
private fun JournalScreenPreview() {
    val rows = listOf(
        JournalRowView("a1", "14/10 09:35:05", 'P', "Pagamento 0,10 €", "Esito sconosciuto", ResultTone.WARNING, 1, persisted = false),
        JournalRowView("a2", "14/10 09:20:11", 'P', "Pagamento 5,00 €", "Approvato", ResultTone.SUCCESS, 1),
        JournalRowView("a3", "14/10 09:10:00", null, "Tentativo con dati incompleti", "Risolto: chiuso dall'operatore", ResultTone.NEUTRAL, 1, resolved = true),
    )
    val detail = JournalDetailView(
        attemptId = "a1", title = "Pagamento 0,10 €",
        summary = listOf(FieldRow("Stato", "Esito sconosciuto"), FieldRow("Motivo", "nessun esito entro 120 s")),
        timeline = listOf(FieldRow("creato", "14/10/2026 09:35:05", "#1"), FieldRow("invio", "14/10/2026 09:35:05 · invio 1", "#2")),
        rawResult = null, evidence = emptyList(), isUnresolved = true, needsReview = false, blocksPayments = true, persisted = false,
        json = "{\"attemptId\":\"a1\",\"seq\":1,\"at\":1,\"phase\":\"CREATED\"}",
    )
    PreviewTheme {
        Box(Modifier.size(1100.dp, 560.dp)) {
            JournalScreen(
                state = JournalUiState(rows = rows, selectedId = "a1", detail = detail, unresolvedCount = 1, filePath = "C:\\Users\\utente\\.protocollo17\\journal\\journal.jsonl"),
                onAction = {}, onOpenFolder = {},
            )
        }
    }
}
