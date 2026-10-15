package it.prime.protocollo17.presentation.components

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.unit.dp
import it.prime.protocollo17.presentation.theme.PreviewTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.datatransfer.StringSelection

/** Quanto resta la spunta prima di tornare l'icona "copia". */
private const val FEEDBACK_MILLIS = 1_500L

/**
 * Pulsante "copia negli appunti" con il ritorno visivo della spunta: esiti, hex, cronologia, JSON del journal.
 *
 * Un componente unico invece di tre righe di clipboard ripetute in ogni pannello (finirebbero per comportarsi in
 * modo diverso). La copia passa da [copyText]: una clipboard bloccata da un'altra app su Windows non deve far
 * cadere la finestra, al massimo non si copia.
 */
@Composable
fun CopyIconButton(text: String, modifier: Modifier = Modifier, contentDescription: String = "Copia") {
    CopyButton(textOf = { text }, modifier = modifier, contentDescription = contentDescription, enabled = true)
}

/**
 * Variante per testi grandi ("Copia tutto" del Log traffico: fino a 5 000 righe): il testo si costruisce solo al
 * clic e su [Dispatchers.Default]. Passare il testo già pronto vorrebbe dire formattare tutte le righe durante la
 * composizione, sull'EDT, a ogni ripubblicazione del registratore (10 Hz durante un comando, §5.4.3, §6.12).
 * [textProvider] deve leggere solo dati immutabili (la lista dello stato): gira fuori dal thread UI.
 */
@Composable
fun CopyIconButton(
    textProvider: () -> String,
    modifier: Modifier = Modifier,
    contentDescription: String = "Copia",
    enabled: Boolean = true,
) {
    val currentProvider by rememberUpdatedState(textProvider)
    CopyButton(
        textOf = { withContext(Dispatchers.Default) { currentProvider() } },
        modifier = modifier,
        contentDescription = contentDescription,
        enabled = enabled,
    )
}

@Composable
private fun CopyButton(textOf: suspend () -> String, modifier: Modifier, contentDescription: String, enabled: Boolean) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val currentTextOf by rememberUpdatedState(textOf)
    // Contatore e non booleano: una seconda copia mentre la spunta è visibile deve far ripartire il timer.
    var copiedTick by remember { mutableIntStateOf(0) }
    val copied = copiedTick > 0
    LaunchedEffect(copiedTick) {
        if (copiedTick > 0) {
            delay(FEEDBACK_MILLIS)
            copiedTick = 0
        }
    }
    WithTooltip(if (copied) "Copiato" else contentDescription) {
        IconButton(
            onClick = {
                scope.launch {
                    val ok = clipboard.copyText(currentTextOf())
                    copiedTick = if (ok) copiedTick + 1 else 0
                }
            },
            enabled = enabled,
            modifier = modifier.size(32.dp),
        ) {
            Icon(
                imageVector = if (copied) Icons.Filled.Check else Icons.Outlined.ContentCopy,
                contentDescription = if (copied) "Copiato" else contentDescription,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * Copia testo negli appunti; false se la clipboard non è disponibile (bloccata da un'altra app su Windows).
 * try/catch e non runCatching: è codice suspend, e un runCatching inghiottirebbe anche la CancellationException.
 */
suspend fun Clipboard.copyText(text: String): Boolean = try {
    setClipEntry(ClipEntry(StringSelection(text)))
    true
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    false
}

@Suppress("unused")
@Preview
@Composable
private fun CopyIconButtonPreview() {
    PreviewTheme {
        Row {
            CopyIconButton(text = "02 30 30 7A", contentDescription = "Copia hex")
            CopyIconButton(textProvider = { "riga 1\nriga 2" }, contentDescription = "Copia tutto")
            CopyIconButton(textProvider = { "" }, contentDescription = "Niente da copiare", enabled = false)
        }
    }
}
