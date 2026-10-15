package it.prime.protocollo17.presentation.screen.traffic.view

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SaveAs
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import it.prime.protocollo17.domain.model.traffic.TrafficEntry
import it.prime.protocollo17.domain.model.traffic.TrafficKind
import it.prime.protocollo17.domain.model.traffic.TrafficSeverity
import it.prime.protocollo17.presentation.components.AppVerticalScrollbar
import it.prime.protocollo17.presentation.components.CopyIconButton
import it.prime.protocollo17.presentation.components.TooltipIconButton
import it.prime.protocollo17.presentation.components.WithTooltip
import it.prime.protocollo17.presentation.screen.traffic.viewModel.TrafficFilter
import it.prime.protocollo17.presentation.screen.traffic.viewModel.TrafficLogAction
import it.prime.protocollo17.presentation.screen.traffic.viewModel.TrafficLogUiState
import it.prime.protocollo17.presentation.theme.PreviewTheme
import it.prime.protocollo17.presentation.theme.WarningAmber
import it.prime.protocollo17.presentation.util.formatTimeMillis
import kotlinx.coroutines.launch
import java.awt.datatransfer.StringSelection

/**
 * Finestra Log traffico (§6.12): frame TX/RX in hex con la resa ASCII ed eventi dello scambio, filtrabili.
 *
 * Un SelectionContainer PER RIGA (una selezione unica su una LazyColumn non attraversa le righe riciclate e
 * registrerebbe un selezionabile per ogni riga, R30); "Copia tutto" ed "Esporta…" coprono più righe. Lo scroll
 * automatico in fondo scatta solo se l'operatore è già in fondo: chi sta leggendo una riga vecchia non viene
 * strappato via a ogni frame.
 */
@Composable
fun TrafficLogScreen(
    state: TrafficLogUiState,
    onAction: (TrafficLogAction) -> Unit,
    onExport: () -> Unit,
    onOpenFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    Column(modifier.fillMaxSize()) {
        Toolbar(state, onAction, onExport, onOpenFolder)
        HorizontalDivider()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (state.visible.isEmpty()) {
                Text(
                    if (state.totalInMemory == 0) "Nessun traffico. Esegui un comando o avvia il simulatore." else "Nessuna voce corrisponde al filtro.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                LazyColumn(state = listState, contentPadding = PaddingValues(start = 8.dp, end = 16.dp, top = 4.dp, bottom = 4.dp)) {
                    items(state.visible, key = { it.id }) { entry -> TrafficRow(entry, state.filter.showAscii) }
                }
                AppVerticalScrollbar(listState)
                AutoScroll(listState, state)
            }
        }
        HorizontalDivider()
        SelectionContainer {
            Text(
                text = state.currentFile?.let { "File di oggi: $it" } ?: "Scrittura su file disattivata (Opzioni › Log e journal)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/**
 * In fondo se l'operatore era già in fondo (derivedStateOf: non ricompone la schermata a ogni pixel di scroll).
 * "Era in fondo" si decide quando lo scroll dell'operatore si ferma, non all'arrivo delle righe nuove: in quel
 * momento la lista è già più lunga e sembrerebbe sempre "non in fondo".
 */
@Composable
private fun AutoScroll(listState: LazyListState, state: TrafficLogUiState) {
    val atBottom by remember(listState) { derivedStateOf { !listState.canScrollForward } }
    var stickToBottom by remember(listState) { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling -> if (!scrolling) stickToBottom = atBottom }
    }
    LaunchedEffect(state.visible.size, state.autoScroll) {
        if (state.autoScroll && !state.paused && stickToBottom && state.visible.isNotEmpty()) {
            listState.scrollToItem(state.visible.lastIndex)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Toolbar(state: TrafficLogUiState, onAction: (TrafficLogAction) -> Unit, onExport: () -> Unit, onOpenFolder: () -> Unit) {
    val filter = state.filter
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = filter.text,
                onValueChange = { onAction(TrafficLogAction.SetText(it)) },
                singleLine = true,
                placeholder = { Text("Filtra (testo, hex, lettera)…", style = MaterialTheme.typography.bodySmall) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                trailingIcon = {
                    if (filter.text.isNotEmpty()) {
                        TooltipIconButton(icon = Icons.Filled.Close, tooltip = "Azzera filtro", onClick = { onAction(TrafficLogAction.SetText("")) }, iconSize = 16.dp)
                    }
                },
                textStyle = MaterialTheme.typography.bodySmall,
                modifier = Modifier.width(260.dp),
            )
            FilterChip(selected = filter.showFrames, onClick = { onAction(TrafficLogAction.ToggleFrames) }, label = { Text("Frame") })
            FilterChip(selected = filter.showEvents, onClick = { onAction(TrafficLogAction.ToggleEvents) }, label = { Text("Eventi") })
            FilterChip(selected = filter.onlyWarnings, onClick = { onAction(TrafficLogAction.ToggleOnlyWarnings) }, label = { Text("Solo avvisi") })
            FilterChip(selected = filter.showAscii, onClick = { onAction(TrafficLogAction.ToggleAscii) }, label = { Text("ASCII") })
            FilterChip(selected = state.autoScroll, onClick = { onAction(TrafficLogAction.SetAutoScroll(!state.autoScroll)) }, label = { Text("Auto-scroll") })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${state.visible.size} di ${state.totalInMemory} voci" + if (state.paused) " · in pausa (la registrazione continua)" else "",
                style = MaterialTheme.typography.labelMedium,
                color = if (state.paused) WarningAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TooltipIconButton(
                icon = if (state.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                tooltip = if (state.paused) "Riprendi la vista" else "Pausa (congela la vista, la registrazione continua)",
                onClick = { onAction(TrafficLogAction.TogglePause) },
            )
            CopyIconButton(text = state.visible.joinToString("\n") { it.asText(withAscii = state.filter.showAscii) }, contentDescription = "Copia tutte le righe visibili")
            TooltipIconButton(icon = Icons.Filled.SaveAs, tooltip = "Esporta le righe visibili…", onClick = onExport, enabled = state.visible.isNotEmpty())
            TooltipIconButton(icon = Icons.Filled.DeleteSweep, tooltip = "Svuota la memoria (il file resta)", onClick = { onAction(TrafficLogAction.ClearMemory) })
            TooltipIconButton(icon = Icons.Filled.FolderOpen, tooltip = "Apri la cartella dei log", onClick = onOpenFolder)
        }
    }
}

@Composable
private fun TrafficRow(entry: TrafficEntry, showAscii: Boolean) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    fun copy(text: String) {
        scope.launch { runCatching { clipboard.setClipEntry(ClipEntry(StringSelection(text))) } }
    }
    ContextMenuArea(items = {
        buildList {
            add(ContextMenuItem("Copia riga") { copy(entry.asText(withAscii = true)) })
            entry.hex?.let { hex -> add(ContextMenuItem("Copia hex") { copy(hex) }) }
        }
    }) {
        SelectionContainer {
            Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(formatTimeMillis(entry.at), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.width(8.dp))
                    DirectionBadge(entry)
                    Spacer(Modifier.width(6.dp))
                    Text(entry.command?.toString() ?: " ", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        entry.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = when (entry.severity) {
                            TrafficSeverity.WARNING -> WarningAmber
                            TrafficSeverity.ERROR -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
                entry.hex?.let { hex ->
                    Text(hex, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 112.dp))
                }
                if (showAscii) {
                    entry.ascii?.let { ascii ->
                        Text(
                            ascii,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 112.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DirectionBadge(entry: TrafficEntry) {
    val (label, color, tooltip) = when {
        entry.kind == TrafficKind.TX -> Triple("TX", MaterialTheme.colorScheme.primary, "Inviato dall'ECR (app)")
        entry.kind == TrafficKind.RX -> Triple("RX", MaterialTheme.colorScheme.tertiary, "Ricevuto dal terminale")
        entry.kind == TrafficKind.DISCARDED || entry.kind == TrafficKind.WARNING || entry.severity == TrafficSeverity.WARNING ->
            Triple("!", WarningAmber, "Byte scartati o avviso del protocollo")
        else -> Triple("i", MaterialTheme.colorScheme.outline, "Evento dello scambio")
    }
    WithTooltip(tooltip) {
        Text(
            label,
            color = Color.White,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.background(color, RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}

private fun TrafficEntry.asText(withAscii: Boolean): String = buildString {
    append(formatTimeMillis(at)).append(' ').append(kind.name).append(' ')
    command?.let { append(it).append(' ') }
    append(summary)
    hex?.let { append("\n    ").append(it) }
    if (withAscii) ascii?.let { append("\n    ").append(it) }
}

// ─── Preview ──────────────────────────────────────────────────────────────────────

@Suppress("unused")
@Preview
@Composable
private fun TrafficLogScreenPreview() {
    val entries = listOf(
        TrafficEntry(1, 1_760_000_000_000, 1, TrafficKind.COMMAND, 'P', null, null, "P avviato verso 192.168.1.131:5577", TrafficSeverity.INFO),
        TrafficEntry(2, 1_760_000_000_050, 1, TrafficKind.TX, 'P', "02 30 30 30 30 30 30 30 30 30 50 03 2F", "<STX>000000000P<ETX>/", "Richiesta 'P' (invio 1)", TrafficSeverity.NORMAL),
        TrafficEntry(3, 1_760_000_002_050, 1, TrafficKind.WARNING, 'P', null, null, "IMPLICIT_GO_AHEAD ACK non ricevuto entro 2000 ms", TrafficSeverity.WARNING),
        TrafficEntry(4, 1_760_000_008_000, 1, TrafficKind.RX, 'P', "06 03 7A", "<ACK><ETX>z", "ACK (LRC 7A, WIRE)", TrafficSeverity.NORMAL),
    )
    PreviewTheme {
        Box(Modifier.size(1000.dp, 420.dp)) {
            TrafficLogScreen(
                state = TrafficLogUiState(visible = entries, totalInMemory = 4, currentFile = "C:\\Users\\utente\\.protocollo17\\traffic\\2026_10_14-p17-traffic.txt"),
                onAction = {}, onExport = {}, onOpenFolder = {},
            )
        }
    }
}

@Suppress("unused")
@Preview
@Composable
private fun TrafficLogScreenEmptyPreview() {
    PreviewTheme {
        Box(Modifier.size(700.dp, 300.dp)) {
            TrafficLogScreen(state = TrafficLogUiState(filter = TrafficFilter(onlyWarnings = true), paused = true), onAction = {}, onExport = {}, onOpenFolder = {})
        }
    }
}
