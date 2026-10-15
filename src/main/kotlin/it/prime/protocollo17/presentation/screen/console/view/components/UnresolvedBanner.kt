package it.prime.protocollo17.presentation.screen.console.view.components

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import it.prime.protocollo17.ecr17.model.CommandCode
import it.prime.protocollo17.presentation.screen.console.viewModel.ConsoleAction
import it.prime.protocollo17.presentation.screen.console.viewModel.UnresolvedView
import it.prime.protocollo17.presentation.theme.PreviewTheme
import it.prime.protocollo17.presentation.theme.WarningAmber

/** Righe di tentativi mostrate prima di "e altri n": il banner non deve spingere il form fuori dalla finestra. */
private const val MAX_LINES = 3

/**
 * Banner dei tentativi da risolvere (§6.8): perché i comandi sono bloccati e come sbloccarli. "Ultimo esito (G)"
 * è il pulsante primario ma resta un'azione dell'operatore (U7): G non parte mai da solo, nemmeno all'avvio.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UnresolvedBanner(
    unresolved: List<UnresolvedView>,
    paymentsBlocked: Boolean,
    blockedCommands: Set<CommandCode>,
    canRecoverWithG: Boolean,
    onAction: (ConsoleAction) -> Unit,
    onOpenJournal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (unresolved.isEmpty()) return
    Surface(
        color = WarningAmber.copy(alpha = 0.15f),
        border = BorderStroke(1.dp, WarningAmber),
        shape = MaterialTheme.shapes.small,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    unresolved.take(MAX_LINES).forEach { attempt ->
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(Icons.Filled.Warning, contentDescription = null, tint = WarningAmber, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            val kind = if (attempt.needsReview) "Esito da verificare" else "Esito sconosciuto"
                            Text(
                                "$kind: ${attempt.title} delle ${attempt.time} (${attempt.reason})",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                    if (unresolved.size > MAX_LINES) {
                        Text("e altri ${unresolved.size - MAX_LINES}", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        text = if (paymentsBlocked) {
                            "I comandi non ripetibili (P X S p i c H C) sono bloccati finché non esegui Ultimo esito (G) o non chiudi il tentativo."
                        } else {
                            val letters = CommandCode.entries.filter { it in blockedCommands }.joinToString(" ") { it.wire.toString() }
                            "Bloccati solo i comandi con la stessa risposta ($letters) finché non verifichi il tentativo."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { onAction(ConsoleAction.RecoverWithG) }, enabled = canRecoverWithG) { Text("Ultimo esito (G)") }
                OutlinedButton(onClick = { onAction(ConsoleAction.OpenResolve(unresolved.first().attemptId)) }) { Text("Chiudi tentativo…") }
                TextButton(onClick = onOpenJournal) { Text("Journal") }
            }
        }
    }
}

@Suppress("unused")
@Preview
@Composable
private fun UnresolvedBannerPreview() {
    PreviewTheme {
        Column(Modifier.width(760.dp).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            UnresolvedBanner(
                unresolved = listOf(
                    UnresolvedView("a1", "Pagamento 0,10 €", "14/10 09:35:05", "nessun esito entro 120 s", needsReview = false, blocksPayments = true),
                    UnresolvedView("a2", "Pagamento 5,00 €", "14/10 09:20:11", "possibile doppio addebito", needsReview = true, blocksPayments = true),
                ),
                paymentsBlocked = true, blockedCommands = emptySet(), canRecoverWithG = true, onAction = {}, onOpenJournal = {},
            )
            UnresolvedBanner(
                unresolved = listOf(UnresolvedView("a3", "Verifica carta", "14/10 10:01:00", "connessione chiusa dal terminale durante lo scambio", false, false)),
                paymentsBlocked = false,
                blockedCommands = setOf(CommandCode.PAYMENT, CommandCode.PAYMENT_EXTENDED, CommandCode.REVERSAL, CommandCode.CARD_VERIFICATION),
                canRecoverWithG = false, onAction = {}, onOpenJournal = {},
            )
        }
    }
}
