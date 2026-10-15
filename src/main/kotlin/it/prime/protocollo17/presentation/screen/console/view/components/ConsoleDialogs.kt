package it.prime.protocollo17.presentation.screen.console.view.components

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import it.prime.protocollo17.domain.model.command.CommandTitles
import it.prime.protocollo17.ecr17.model.CommandCode
import it.prime.protocollo17.presentation.components.ResolveAttemptDialog
import it.prime.protocollo17.presentation.screen.console.viewModel.ConsoleAction
import it.prime.protocollo17.presentation.screen.console.viewModel.ConsoleDialog
import it.prime.protocollo17.presentation.screen.console.viewModel.UnresolvedView
import it.prime.protocollo17.presentation.theme.PreviewTheme

/**
 * Dialog della Console come stato (skill navigation: un dialog non è una destinazione). Esc li chiude tramite
 * `onDismissRequest` (la finestra restituisce `false` per Esc) e, finché sono aperti, Esc non annulla il comando
 * in corso e Ctrl+Invio non esegue nulla (`escapeCancels`, `shortcutsEnabled`).
 */
@Composable
fun ConsoleDialogs(dialog: ConsoleDialog?, onAction: (ConsoleAction) -> Unit) {
    val dismiss = { onAction(ConsoleAction.DismissDialog) }
    when (dialog) {
        null -> Unit
        is ConsoleDialog.ConfirmRun -> AlertDialog(
            onDismissRequest = dismiss,
            title = { Text(CommandTitles.title(dialog.command)) },
            text = { Text(dialog.message) },
            confirmButton = {
                Button(
                    onClick = { onAction(ConsoleAction.ConfirmRun) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                ) { Text("Esegui") }
            },
            dismissButton = { TextButton(onClick = dismiss) { Text("Annulla") } },
        )
        is ConsoleDialog.ConfirmCancelAfterSend -> AlertDialog(
            onDismissRequest = dismiss,
            title = { Text("Interrompere ${CommandTitles.title(dialog.command)}?") },
            text = { Text(dialog.message) },
            confirmButton = {
                Button(
                    onClick = { onAction(ConsoleAction.ConfirmCancel) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                ) { Text("Interrompi l'attesa") }
            },
            dismissButton = { TextButton(onClick = dismiss) { Text("Continua ad attendere") } },
        )
        is ConsoleDialog.Resolve -> ResolveAttemptDialog(
            model = dialog.model,
            onResolve = { kind, note -> onAction(ConsoleAction.Resolve(dialog.model.attemptId, kind, note)) },
            onDismiss = dismiss,
        )
        is ConsoleDialog.BlockedInfo -> AlertDialog(
            onDismissRequest = dismiss,
            title = { Text("Comando bloccato") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Questi tentativi hanno l'esito da risolvere:")
                    dialog.attempts.forEach { Text("• ${it.title} delle ${it.time}: ${it.reason}", style = MaterialTheme.typography.bodySmall) }
                    Text(
                        "Esegui Ultimo esito (G) per verificare l'ultima transazione, oppure chiudi il tentativo dopo averlo " +
                            "verificato sullo scontrino o sul terminale.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    dialog.attempts.firstOrNull()?.let { first ->
                        OutlinedButton(onClick = { onAction(ConsoleAction.OpenResolve(first.attemptId)) }) { Text("Chiudi tentativo…") }
                    }
                    Button(onClick = {
                        onAction(ConsoleAction.DismissDialog)
                        onAction(ConsoleAction.RecoverWithG)
                    }) { Text("Ultimo esito (G)") }
                }
            },
            dismissButton = { TextButton(onClick = dismiss) { Text("Chiudi") } },
        )
    }
}

@Suppress("unused")
@Preview
@Composable
private fun ConsoleDialogsPreview() {
    PreviewTheme {
        ConsoleDialogs(
            dialog = ConsoleDialog.BlockedInfo(
                listOf(UnresolvedView("a1", "Pagamento 0,10 €", "14/10 09:35:05", "nessun esito entro 120 s", false, true)),
            ),
            onAction = {},
        )
    }
}

@Suppress("unused")
@Preview
@Composable
private fun ConfirmCancelPreview() {
    PreviewTheme {
        ConsoleDialogs(
            dialog = ConsoleDialog.ConfirmCancelAfterSend(
                CommandCode.PAYMENT,
                "Interrompere l'attesa? L'esito resterà SCONOSCIUTO e i pagamenti saranno bloccati finché non esegui Ultimo esito (G).",
            ),
            onAction = {},
        )
    }
}
