package it.prime.protocollo17.presentation.screen.console.view.components

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.prime.protocollo17.domain.model.command.CommandTitles
import it.prime.protocollo17.ecr17.model.CommandCode
import it.prime.protocollo17.ecr17.model.ExchangePhase
import it.prime.protocollo17.ecr17.model.PaymentRequest
import it.prime.protocollo17.ecr17.model.ReceiptLine
import it.prime.protocollo17.presentation.components.ScrollableColumn
import it.prime.protocollo17.presentation.model.ReceiptText
import it.prime.protocollo17.presentation.model.ResultViewMapper
import it.prime.protocollo17.presentation.state.RunOrigin
import it.prime.protocollo17.presentation.state.RunningCommand
import it.prime.protocollo17.presentation.theme.PreviewTheme
import it.prime.protocollo17.presentation.theme.WarningAmber
import it.prime.protocollo17.presentation.util.formatDuration
import kotlinx.coroutines.delay

/**
 * Velo sopra il contenuto durante un comando (§6.9): fase in italiano, cronometro, ultimo progress, righe di
 * scontrino e avvisi, Annulla (Esc).
 *
 * Il cronometro è SOLO una vista (aggiornata ogni 500 ms), non un timeout (M8): durante l'handshake conta dal
 * SENDING verso il timeout ACK, poi dalla prima attesa dell'esito verso il timeout esito. Un ACK tardivo non lo
 * azzera, coerente con la deadline della sessione che non viene mai estesa. Il velo consuma i clic, così nulla
 * sotto si può cliccare a metà di un pagamento; sidebar e intestazione restano visibili ma disabilitate.
 */
@Composable
fun BusyOverlay(running: RunningCommand, endpoint: String, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(running.startedAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f))
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
        contentAlignment = Alignment.Center,
    ) {
        Card(Modifier.width(460.dp).heightIn(max = 520.dp)) {
            ScrollableColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("${CommandTitles.title(running.command)} in corso…", style = MaterialTheme.typography.titleMedium)
                }
                Text(phaseText(running, endpoint), style = MaterialTheme.typography.bodyMedium)
                Text(chronometer(running, now), style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace)
                running.lastProgress?.let {
                    Text(it, fontFamily = FontFamily.Monospace, fontSize = 20.sp, color = MaterialTheme.colorScheme.primary)
                }
                if (running.receipt.isNotEmpty()) {
                    // Righe di stampa, non una per riga S (§6.9): il cronometro ricompone ogni 500 ms, lo scontrino no.
                    val receipt = remember(running.receipt) { ReceiptText.lines(running.receipt).joinToString("\n") }
                    SelectionContainer {
                        Text(
                            receipt,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                running.warnings.forEach { warning ->
                    Text(
                        "${ResultViewMapper.warningLabel(warning.code)}: ${warning.detail}",
                        style = MaterialTheme.typography.bodySmall,
                        color = WarningAmber,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Button(onClick = onCancel, enabled = !running.cancelRequested, modifier = Modifier.fillMaxWidth()) {
                    Text(if (running.cancelRequested) "Annullamento…" else "Annulla (Esc)")
                }
            }
        }
    }
}

/** Testo della fase (§6.9). */
private fun phaseText(running: RunningCommand, endpoint: String): String = when (running.phase) {
    ExchangePhase.PREPARING -> "Preparazione del comando…"
    ExchangePhase.CONNECTING -> "Connessione a $endpoint…"
    ExchangePhase.PRINTING_SETUP -> "Abilitazione stampa su ECR…"
    ExchangePhase.SENDING, ExchangePhase.AWAITING_ACK -> "Richiesta inviata (invio ${running.sends.coerceAtLeast(1)}), attesa ACK…"
    ExchangePhase.IMPLICIT_GO_AHEAD -> "ACK non ricevuto: attendo l'esito senza reinviare"
    ExchangePhase.ACKNOWLEDGED -> "Richiesta accettata"
    ExchangePhase.AWAITING_RESULT ->
        "Attesa esito…" + if (running.implicitGoAhead) " (ACK non ricevuto, nessun reinvio)" else ""
    ExchangePhase.RESULT_RECEIVED, ExchangePhase.DRAINING -> "Esito ricevuto, chiusura dello scambio…"
    ExchangePhase.COMPLETED -> "Completato"
}

/** "00:23 / 03:00": verso il timeout esito dalla prima attesa dell'esito, verso il timeout ACK durante l'handshake. */
private fun chronometer(running: RunningCommand, now: Long): String {
    val resultWait = running.resultWaitStartedAt
    return when {
        resultWait != null -> "${formatDuration(now - resultWait)} / ${formatDuration(running.responseTimeoutMs)}"
        running.phase == ExchangePhase.SENDING || running.phase == ExchangePhase.AWAITING_ACK ->
            "${formatDuration(now - running.phaseAt)} / ${formatDuration(running.ackTimeoutMs)}"
        else -> formatDuration(now - running.startedAt)
    }
}

@Suppress("unused")
@Preview
@Composable
private fun BusyOverlayPreview() {
    val now = System.currentTimeMillis()
    PreviewTheme {
        Box(Modifier.width(720.dp).height(520.dp)) {
            BusyOverlay(
                running = RunningCommand(
                    command = CommandCode.PAYMENT, request = PaymentRequest(amountCents = 10), origin = RunOrigin.CONSOLE,
                    attemptId = "a1", startedAt = now - 25_000, phase = ExchangePhase.AWAITING_RESULT, phaseAt = now - 23_000,
                    sends = 1, implicitGoAhead = true, resultWaitStartedAt = now - 23_000, lastProgress = "OPERAZIONE IN CORSO ",
                    receipt = listOf(
                        ReceiptLine(raw = "SIMULATORE P17", text = "SIMULATORE P17", doubleHeight = false, isEnd = false),
                        ReceiptLine(
                            raw = "PAGAMENTO CARTA ************1111", text = "PAGAMENTO CARTA ************1111",
                            doubleHeight = false, isEnd = false,
                        ),
                    ),
                    warnings = emptyList(), cancelRequested = false,
                    ackTimeoutMs = 2_000, responseTimeoutMs = 180_000,
                ),
                endpoint = "192.168.1.131:5577",
                onCancel = {},
            )
        }
    }
}
