package it.prime.protocollo17.presentation.screen.simulator.view

import androidx.compose.desktop.ui.tooling.preview.Preview
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import it.prime.protocollo17.domain.model.settings.SimulatorSettings
import it.prime.protocollo17.domain.model.simulator.SimCatalog
import it.prime.protocollo17.domain.model.simulator.SimLogEntry
import it.prime.protocollo17.domain.model.simulator.SimStats
import it.prime.protocollo17.domain.model.simulator.SimulatorFailure
import it.prime.protocollo17.domain.model.simulator.SimulatorStatus
import it.prime.protocollo17.domain.util.formatCents
import it.prime.protocollo17.presentation.components.AppVerticalScrollbar
import it.prime.protocollo17.presentation.components.EnumDropdown
import it.prime.protocollo17.presentation.components.KeyValueRow
import it.prime.protocollo17.presentation.components.KeyValueTable
import it.prime.protocollo17.presentation.components.NumberTextField
import it.prime.protocollo17.presentation.components.ScrollableColumn
import it.prime.protocollo17.presentation.components.SettingsCard
import it.prime.protocollo17.presentation.components.StatusDot
import it.prime.protocollo17.presentation.components.SwitchRow
import it.prime.protocollo17.presentation.screen.simulator.viewModel.SimulatorAction
import it.prime.protocollo17.presentation.screen.simulator.viewModel.SimulatorUiState
import it.prime.protocollo17.presentation.screen.simulator.viewModel.SimulatorViewModel
import it.prime.protocollo17.presentation.theme.PreviewTheme
import it.prime.protocollo17.presentation.theme.SuccessGreen
import it.prime.protocollo17.presentation.theme.WarningAmber
import it.prime.protocollo17.presentation.util.formatTimeMillis

/**
 * Finestra Simulatore (§6.14): stato e porta (la porta occupata è un messaggio rosso accanto al campo, non un
 * errore che chiude qualcosa), comportamento del terminale simulato (guasti di §4.5 con la loro descrizione) e log
 * IN/OUT già mascherato. Il simulatore ascolta solo su 127.0.0.1: nessun prompt del firewall di Windows.
 */
@Composable
fun SimulatorScreen(state: SimulatorUiState, onAction: (SimulatorAction) -> Unit, modifier: Modifier = Modifier) {
    ScrollableColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        StatusCard(state, onAction)
        BehaviorCard(state, onAction)
        LogCard(state.log, onAction)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusCard(state: SimulatorUiState, onAction: (SimulatorAction) -> Unit) {
    SettingsCard(title = "Stato") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                state.starting -> CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                else -> StatusDot(
                    when (state.status) {
                        is SimulatorStatus.Running -> SuccessGreen
                        is SimulatorStatus.Failed -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.outline
                    },
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                when (val s = state.status) {
                    is SimulatorStatus.Running -> "In ascolto su 127.0.0.1:${s.port}" + if (state.isTarget) " · è la destinazione del terminale" else ""
                    SimulatorStatus.Starting -> "Avvio…"
                    SimulatorStatus.Stopped -> "Fermo"
                    is SimulatorStatus.Failed -> "Non avviato"
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            if (state.running) {
                OutlinedButton(onClick = { onAction(SimulatorAction.Stop) }) { Text("Ferma") }
            } else {
                Button(onClick = { onAction(SimulatorAction.Start) }, enabled = !state.starting) { Text("Avvia") }
            }
        }
        NumberTextField(
            label = "Porta (solo 127.0.0.1)",
            text = state.texts["port"] ?: state.settings.port.toString(),
            onTextChange = { onAction(SimulatorAction.SetNumber("port", it)) },
            suffix = null,
            error = state.fieldErrors["port"] ?: state.statusError,
            hint = "Cambiarla con il simulatore attivo lo riavvia sulla nuova porta",
            modifier = Modifier.width(280.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onAction(SimulatorAction.UseAsTarget) }, enabled = state.running && !state.isTarget) { Text("Usa come terminale") }
            OutlinedButton(onClick = { onAction(SimulatorAction.RestoreTarget) }) { Text("Ripristina terminale") }
            TextButton(onClick = { onAction(SimulatorAction.ResetState) }) { Text("Azzera stato") }
        }
        val stats = state.stats
        KeyValueTable(
            rows = listOf(
                KeyValueRow("Connessioni", stats.connections.toString()),
                KeyValueRow("Richieste", stats.requests.toString()),
                KeyValueRow("Rifiutate per LRC", stats.rejectedForLrc.toString(), emphasis = stats.rejectedForLrc > 0),
                KeyValueRow("Ultimo comando", stats.lastCommand?.toString() ?: "—"),
                KeyValueRow("Totale POS simulato", formatCents(stats.posTotalCents)),
            ),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BehaviorCard(state: SimulatorUiState, onAction: (SimulatorAction) -> Unit) {
    val s = state.settings
    SettingsCard(title = "Comportamento") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            EnumDropdown(
                label = "LRC applicativo del simulatore",
                options = SimCatalog.lrcModes.map { it.name to it.label },
                selected = s.appLrcMode,
                onSelect = { onAction(SimulatorAction.SetAppLrcMode(it)) },
                modifier = Modifier.width(260.dp),
            )
            EnumDropdown(
                label = "ACK del simulatore",
                options = SimCatalog.ackRules.map { it.name to it.label },
                selected = s.ackRule,
                onSelect = { onAction(SimulatorAction.SetAckRule(it)) },
                modifier = Modifier.width(260.dp),
            )
            EnumDropdown(
                label = "Decisione",
                options = SimCatalog.decisions.map { it.name to it.label },
                selected = s.decision,
                onSelect = { onAction(SimulatorAction.SetDecision(it)) },
                modifier = Modifier.width(260.dp),
            )
            EnumDropdown(
                label = "Guasto",
                options = SimCatalog.faults.map { it.name to it.label },
                selected = s.fault,
                onSelect = { onAction(SimulatorAction.SetFault(it)) },
                modifier = Modifier.width(260.dp),
            )
        }
        SimCatalog.faults.firstOrNull { it.name == s.fault }?.let { fault ->
            Text(fault.description, style = MaterialTheme.typography.bodySmall, color = if (fault.name == "NONE") MaterialTheme.colorScheme.onSurfaceVariant else WarningAmber)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(
                "ackDelayMs" to "Ritardo ACK",
                "progressDelayMs" to "Ritardo progress",
                "resultDelayMs" to "Ritardo esito",
            ).forEach { (field, label) ->
                NumberTextField(
                    label = label,
                    text = state.texts[field].orEmpty(),
                    onTextChange = { onAction(SimulatorAction.SetNumber(field, it)) },
                    error = state.fieldErrors[field],
                    modifier = Modifier.width(190.dp),
                )
            }
        }
        SwitchRow(
            checked = s.faultOnlyFinancial,
            onCheckedChange = { onAction(SimulatorAction.SetFaultOnlyFinancial(it)) },
            title = "Guasto solo sui comandi finanziari",
            hint = "Stato, Totali e G restano regolari: utile per provare il recupero con G.",
        )
        SwitchRow(
            checked = s.closeAfterTransaction,
            onCheckedChange = { onAction(SimulatorAction.SetCloseAfterTransaction(it)) },
            title = "Chiudi la connessione dopo ogni transazione",
            hint = null,
        )
        SwitchRow(
            checked = s.retransmitResultIfNoValidAck,
            onCheckedChange = { onAction(SimulatorAction.SetRetransmitResult(it)) },
            title = "Reinvia l'esito se l'ACK dell'ECR non è valido",
            hint = "Fa comparire RESULT_RETRANSMITTED nel drain.",
        )
        SwitchRow(
            checked = s.receiptAfterResult,
            onCheckedChange = { onAction(SimulatorAction.SetReceiptAfterResult(it)) },
            title = "Righe scontrino dopo l'esito",
            hint = "Con 'Stampa su ECR' attiva.",
        )
        SwitchRow(
            checked = s.autoStart,
            onCheckedChange = { onAction(SimulatorAction.SetAutoStart(it)) },
            title = "Avvia all'apertura dell'app",
            hint = null,
        )
    }
}

@Composable
private fun LogCard(log: List<SimLogEntry>, onAction: (SimulatorAction) -> Unit) {
    SettingsCard(title = "Log del simulatore", action = { TextButton(onClick = { onAction(SimulatorAction.ClearLog) }) { Text("Svuota") } }) {
        Box(Modifier.fillMaxWidth().height(280.dp)) {
            if (log.isEmpty()) {
                Text("Nessun evento.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.Center))
            } else {
                val listState = rememberLazyListState()
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(end = 12.dp)) {
                    items(log, key = { it.id }) { entry ->
                        SelectionContainer {
                            Column(Modifier.padding(vertical = 2.dp)) {
                                Row {
                                    Text(formatTimeMillis(entry.at), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        entry.direction.ifBlank { "·" }.padEnd(3),
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (entry.direction == "IN") MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(entry.text, style = MaterialTheme.typography.bodySmall)
                                }
                                entry.hex?.let {
                                    Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 128.dp))
                                }
                            }
                        }
                    }
                }
                AppVerticalScrollbar(listState)
            }
        }
    }
}

// ─── Preview ──────────────────────────────────────────────────────────────────────

@Suppress("unused")
@Preview
@Composable
private fun SimulatorScreenPortInUsePreview() {
    PreviewTheme {
        Box(Modifier.size(680.dp, 720.dp)) {
            SimulatorScreen(
                state = SimulatorUiState(
                    settings = SimulatorSettings(fault = "SILENT"),
                    status = SimulatorStatus.Failed(SimulatorFailure.PortInUse(15577)),
                    texts = mapOf(
                        SimulatorViewModel.FIELD_PORT to "15577", SimulatorViewModel.FIELD_ACK_DELAY to "40",
                        SimulatorViewModel.FIELD_PROGRESS_DELAY to "400", SimulatorViewModel.FIELD_RESULT_DELAY to "2500",
                    ),
                ),
                onAction = {},
            )
        }
    }
}

@Suppress("unused")
@Preview
@Composable
private fun SimulatorScreenRunningPreview() {
    PreviewTheme(darkTheme = true) {
        Box(Modifier.size(680.dp, 720.dp)) {
            SimulatorScreen(
                state = SimulatorUiState(
                    status = SimulatorStatus.Running(15577), isTarget = true,
                    stats = SimStats(connections = 3, requests = 5, rejectedForLrc = 1, lastCommand = 'P', posTotalCents = 1_510),
                    log = listOf(
                        SimLogEntry(1, 1_760_000_000_000, "IN", "Richiesta 'P'", "02 30 30 … 03 2F"),
                        SimLogEntry(2, 1_760_000_000_040, "OUT", "ACK", "06 03 7A"),
                    ),
                ),
                onAction = {},
            )
        }
    }
}
