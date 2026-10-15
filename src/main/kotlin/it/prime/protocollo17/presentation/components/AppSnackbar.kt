package it.prime.protocollo17.presentation.components

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import it.prime.protocollo17.presentation.theme.PreviewTheme
import it.prime.protocollo17.presentation.theme.SuccessGreen
import it.prime.protocollo17.presentation.theme.WarningAmber

/** Tono di un avviso a schermo: decide icona e colore dello snackbar. */
enum class NoticeTone { SUCCESS, INFO, WARNING, ERROR }

/** Contenuto di uno snackbar dell'app: il tono viaggia con il messaggio. */
class AppSnackbarVisuals(
    override val message: String,
    val tone: NoticeTone,
    override val actionLabel: String? = null,
) : SnackbarVisuals {
    override val withDismissAction: Boolean get() = true

    // Gli errori restano finché l'operatore non li chiude: un "esito sconosciuto" che scompare da solo dopo 4 s,
    // mentre l'operatore guardava il terminale, è un avviso perso.
    override val duration: SnackbarDuration
        get() = if (tone == NoticeTone.ERROR || actionLabel != null) SnackbarDuration.Indefinite else SnackbarDuration.Long
}

/**
 * Lo SnackbarHostState della finestra corrente (§6.1: ogni finestra ha il proprio SnackbarHost). Ogni
 * `XScreenRoot` mostra qui i propri eventi: un solo host per finestra, quindi niente snackbar sovrapposti.
 */
val LocalSnackbarHostState = staticCompositionLocalOf<SnackbarHostState?> { null }

/**
 * Mostra un avviso SOSTITUENDO quello visibile (non accoda): una coda di cinque messaggi vecchi davanti all'esito
 * appena arrivato lo farebbe leggere un minuto dopo.
 */
suspend fun SnackbarHostState.showNotice(tone: NoticeTone, message: String, actionLabel: String? = null): SnackbarResult {
    currentSnackbarData?.dismiss()
    return showSnackbar(AppSnackbarVisuals(message, tone, actionLabel))
}

/** Host degli snackbar con il colore del tono e il testo selezionabile (un errore si copia per segnalarlo). */
@Composable
fun AppSnackbarHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(hostState = state, modifier = modifier) { data ->
        val visuals = data.visuals as? AppSnackbarVisuals
        val tone = visuals?.tone ?: NoticeTone.INFO
        Snackbar(
            modifier = Modifier.padding(12.dp).widthIn(max = 720.dp),
            action = data.visuals.actionLabel?.let { label ->
                { TextButton(onClick = { data.performAction() }) { Text(label) } }
            },
            dismissAction = { TextButton(onClick = { data.dismiss() }) { Text("Chiudi") } },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = when (tone) {
                        NoticeTone.SUCCESS -> Icons.Filled.CheckCircle
                        NoticeTone.INFO -> Icons.Filled.Info
                        NoticeTone.WARNING -> Icons.Filled.Warning
                        NoticeTone.ERROR -> Icons.Filled.Error
                    },
                    contentDescription = null,
                    tint = toneTint(tone),
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                SelectionContainer { Text(data.visuals.message) }
            }
        }
    }
}

@Composable
private fun toneTint(tone: NoticeTone): Color = when (tone) {
    NoticeTone.SUCCESS -> SuccessGreen
    NoticeTone.INFO -> MaterialTheme.colorScheme.inversePrimary
    NoticeTone.WARNING -> WarningAmber
    NoticeTone.ERROR -> MaterialTheme.colorScheme.errorContainer
}

@Suppress("unused")
@Preview
@Composable
private fun AppSnackbarPreview() {
    PreviewTheme {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(
                NoticeTone.SUCCESS to "Pagamento 0,10 €: APPROVATO",
                NoticeTone.ERROR to "Esito sconosciuto: Pagamento 0,10 €, pagamenti bloccati: esegui Ultimo esito (G)",
            ).forEach { (tone, text) ->
                Snackbar(modifier = Modifier.padding(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Info, contentDescription = null, tint = toneTint(tone))
                        Spacer(Modifier.width(8.dp))
                        Text(text)
                    }
                }
            }
        }
    }
}
