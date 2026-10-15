package it.prime.protocollo17.presentation.screen.console.view.components

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import it.prime.protocollo17.domain.model.simulator.SimulatorFailure
import it.prime.protocollo17.domain.model.simulator.SimulatorStatus
import it.prime.protocollo17.ecr17.profile.ConnectionMode
import it.prime.protocollo17.ecr17.profile.Evidence
import it.prime.protocollo17.ecr17.profile.FinancialAckPolicy
import it.prime.protocollo17.ecr17.profile.TerminalProfiles
import it.prime.protocollo17.presentation.components.StatusDot
import it.prime.protocollo17.presentation.components.TooltipIconButton
import it.prime.protocollo17.presentation.components.WithTooltip
import it.prime.protocollo17.presentation.screen.console.viewModel.ConnectionView
import it.prime.protocollo17.presentation.screen.console.viewModel.ConsoleAction
import it.prime.protocollo17.presentation.screen.console.viewModel.LinkTone
import it.prime.protocollo17.presentation.screen.console.viewModel.LinkView
import it.prime.protocollo17.presentation.screen.console.viewModel.SimulatorView
import it.prime.protocollo17.presentation.state.AppWindowId
import it.prime.protocollo17.presentation.theme.PreviewTheme
import it.prime.protocollo17.presentation.theme.SuccessGreen
import it.prime.protocollo17.presentation.theme.WarningAmber

/**
 * Intestazione di connessione (§6.10): un'intestazione propria e non una TopAppBar, il cui slot titolo di 64 dp su
 * una riga taglierebbe tre righe di contenuto alla larghezza minima (R29).
 *
 * Riga 1: chip che vanno a capo (profilo con il livello di evidenza, destinazione, switch U4/U5, LRC, collegamento,
 * TID restituito dal terminale, configurazione non valida). Riga 2: azioni a sinistra (disabilitate durante un
 * comando), finestre a destra; sotto 1100 dp le azioni testuali diventano icone con tooltip.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConnectionHeader(
    connection: ConnectionView,
    simulator: SimulatorView,
    unresolvedCount: Int,
    barActionsEnabled: Boolean,
    onAction: (ConsoleAction) -> Unit,
    onOpenWindow: (AppWindowId) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ProfileChip(connection, onClick = { onOpenWindow(AppWindowId.OPTIONS) })
                HeaderChip(
                    text = connection.endpoint.ifBlank { "destinazione non impostata" },
                    tooltip = "Indirizzo e porta del terminale (Opzioni)",
                    monospace = true,
                    onClick = { onOpenWindow(AppWindowId.OPTIONS) },
                )
                HeaderChip(
                    text = if (connection.connectionMode == ConnectionMode.PER_COMMAND) "Per comando" else "Persistente",
                    tooltip = if (connection.connectionMode == ConnectionMode.PER_COMMAND) {
                        "Una connessione TCP per comando, come la DLL Ingenico. Clic: Opzioni"
                    } else {
                        "Connessione persistente tra i comandi: chiusa dopo ogni esito mancante o sospetto. Clic: Opzioni"
                    },
                    onClick = { onOpenWindow(AppWindowId.OPTIONS) },
                )
                HeaderChip(
                    text = if (connection.ackPolicy == FinancialAckPolicy.IMPLICIT_GO_AHEAD) "Go-ahead implicito" else "Ritrasmissione",
                    tooltip = if (connection.ackPolicy == FinancialAckPolicy.IMPLICIT_GO_AHEAD) {
                        "ACK mancante su un comando finanziario: nessun reinvio, si attende l'esito. Clic: Opzioni"
                    } else {
                        "ACK mancante: il pagamento viene ritrasmesso (rischio di doppio addebito, esito da verificare). Clic: Opzioni"
                    },
                    container = if (connection.ackPolicy == FinancialAckPolicy.RETRANSMIT) WarningAmber.copy(alpha = 0.2f) else null,
                    onClick = { onOpenWindow(AppWindowId.OPTIONS) },
                )
                HeaderChip(text = connection.lrcSummary, tooltip = "LRC applicativo e ACK che l'app invia", monospace = true)
                LinkChip(connection.link)
                connection.lastSeenTerminalId?.let {
                    HeaderChip(text = "TID terminale $it", tooltip = "TID restituito dal terminale nell'ultima risposta", monospace = true)
                }
                if (!connection.configUsable) {
                    HeaderChip(
                        text = "Configurazione non valida",
                        tooltip = connection.configMessages.joinToString("\n").ifBlank { "Apri Opzioni" },
                        container = MaterialTheme.colorScheme.errorContainer,
                        content = MaterialTheme.colorScheme.onErrorContainer,
                        onClick = { onOpenWindow(AppWindowId.OPTIONS) },
                    )
                } else if (connection.configMessages.isNotEmpty()) {
                    HeaderChip(
                        text = if (connection.configMessages.size == 1) "1 avviso" else "${connection.configMessages.size} avvisi",
                        tooltip = connection.configMessages.joinToString("\n"),
                        container = WarningAmber.copy(alpha = 0.2f),
                        onClick = { onOpenWindow(AppWindowId.OPTIONS) },
                    )
                }
            }
            ActionRow(connection, simulator, unresolvedCount, barActionsEnabled, onAction, onOpenWindow)
        }
    }
}

@Composable
private fun ActionRow(
    connection: ConnectionView,
    simulator: SimulatorView,
    unresolvedCount: Int,
    enabled: Boolean,
    onAction: (ConsoleAction) -> Unit,
    onOpenWindow: (AppWindowId) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < 1100.dp
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                BarAction(compact, Icons.Filled.MonitorHeart, "Stato (F5)", enabled) { onAction(ConsoleAction.PingStatus) }
                if (connection.testing) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    BarAction(compact, Icons.Filled.NetworkCheck, "Test connessione", enabled) { onAction(ConsoleAction.TestConnection) }
                }
                if (connection.connectionMode == ConnectionMode.PERSISTENT) {
                    BarAction(compact, Icons.Filled.Link, "Connetti", enabled) { onAction(ConsoleAction.Connect) }
                    BarAction(compact, Icons.Filled.LinkOff, "Disconnetti", enabled) { onAction(ConsoleAction.Disconnect) }
                }
            }
            Spacer(Modifier.weight(1f))
            TooltipIconButton(icon = Icons.Filled.Terminal, tooltip = "Log traffico TX/RX (Ctrl+L)", onClick = { onOpenWindow(AppWindowId.TRAFFIC_LOG) })
            TooltipIconButton(
                icon = Icons.AutoMirrored.Filled.ListAlt,
                tooltip = if (unresolvedCount > 0) "Journal (Ctrl+J): $unresolvedCount da risolvere" else "Journal tentativi (Ctrl+J)",
                onClick = { onOpenWindow(AppWindowId.JOURNAL) },
                badge = unresolvedCount,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TooltipIconButton(icon = Icons.Filled.Memory, tooltip = "Simulatore (Ctrl+Shift+S): ${simulator.label}", onClick = { onOpenWindow(AppWindowId.SIMULATOR) })
                when (simulator.status) {
                    is SimulatorStatus.Running -> StatusDot(SuccessGreen)
                    is SimulatorStatus.Failed -> StatusDot(MaterialTheme.colorScheme.error)
                    SimulatorStatus.Starting -> StatusDot(WarningAmber, pulsing = true)
                    SimulatorStatus.Stopped -> Unit
                }
            }
            TooltipIconButton(icon = Icons.Filled.Settings, tooltip = "Opzioni (Ctrl+,)", onClick = { onOpenWindow(AppWindowId.OPTIONS) })
        }
    }
}

@Composable
private fun BarAction(compact: Boolean, icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    if (compact) {
        TooltipIconButton(icon = icon, tooltip = label, onClick = onClick, enabled = enabled)
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(label)
        }
    }
}

@Composable
private fun ProfileChip(connection: ConnectionView, onClick: () -> Unit) {
    val observed = connection.evidence == Evidence.CAPTURE
    val notes = TerminalProfiles.all.firstOrNull { it.displayName == connection.profileName }?.notes.orEmpty()
    WithTooltip(notes.ifBlank { connection.profileName }) {
        ChipSurface(onClick = onClick) {
            StatusDot(if (observed) MaterialTheme.colorScheme.primary else WarningAmber)
            Spacer(Modifier.width(6.dp))
            Text(connection.profileName, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (observed) "OSSERVATO" else "IPOTESI",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = if (observed) MaterialTheme.colorScheme.primary else WarningAmber,
            )
        }
    }
}

@Composable
private fun LinkChip(link: LinkView) {
    val color = when (link.tone) {
        LinkTone.IDLE -> MaterialTheme.colorScheme.outline
        LinkTone.CONNECTING -> MaterialTheme.colorScheme.primary
        LinkTone.CONNECTED -> SuccessGreen
        LinkTone.CLOSED -> WarningAmber
        LinkTone.ERROR -> MaterialTheme.colorScheme.error
    }
    WithTooltip("Stato del collegamento TCP") {
        ChipSurface {
            StatusDot(color, pulsing = link.tone == LinkTone.CONNECTING)
            Spacer(Modifier.width(6.dp))
            Text(link.label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun HeaderChip(
    text: String,
    tooltip: String,
    monospace: Boolean = false,
    container: Color? = null,
    content: Color? = null,
    onClick: (() -> Unit)? = null,
) {
    WithTooltip(tooltip) {
        ChipSurface(container = container, onClick = onClick) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                fontFamily = if (monospace) FontFamily.Monospace else null,
                color = content ?: MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun ChipSurface(container: Color? = null, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Surface(shape = shape, color = container ?: MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(
            modifier = (if (onClick != null) Modifier.pointerHoverIcon(PointerIcon.Hand).clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) { content() }
    }
}

// ─── Preview ──────────────────────────────────────────────────────────────────────

@Suppress("unused")
@Preview
@Composable
private fun ConnectionHeaderPreview() {
    PreviewTheme {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ConnectionHeader(
                connection = ConnectionView(
                    endpoint = "192.168.1.131:5577", link = LinkView(LinkTone.CONNECTED, "Connesso"), lastSeenTerminalId = "02575733",
                ),
                simulator = SimulatorView(status = SimulatorStatus.Running(15577), label = "Simulatore attivo su 127.0.0.1:15577"),
                unresolvedCount = 2, barActionsEnabled = true, onAction = {}, onOpenWindow = {},
            )
            ConnectionHeader(
                connection = ConnectionView(
                    profileName = "CCV", evidence = Evidence.ASSUMPTION, endpoint = "10.0.0.5:0", configUsable = false,
                    configMessages = listOf("Porta del terminale obbligatoria"), connectionMode = ConnectionMode.PERSISTENT,
                    ackPolicy = FinancialAckPolicy.RETRANSMIT, link = LinkView(LinkTone.ERROR, "Connessione fallita"), testing = true,
                ),
                simulator = SimulatorView(
                    status = SimulatorStatus.Failed(SimulatorFailure.PortInUse(15577)), label = "Porta 15577 in uso: cambiala nel Simulatore",
                ),
                unresolvedCount = 0, barActionsEnabled = false, onAction = {}, onOpenWindow = {},
                modifier = Modifier.width(900.dp),
            )
        }
    }
}
