package it.prime.protocollo17.presentation.components

import androidx.compose.desktop.ui.tooling.preview.Preview
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.unit.dp
import it.prime.protocollo17.presentation.theme.PreviewTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.datatransfer.StringSelection

/** Quanto resta la spunta prima di tornare l'icona "copia". */
private const val FEEDBACK_MILLIS = 1_500L

/**
 * Pulsante "copia negli appunti" con il ritorno visivo della spunta: esiti, hex, cronologia, JSON del journal.
 *
 * Un componente unico invece di tre righe di clipboard ripetute in ogni pannello (finirebbero per comportarsi in
 * modo diverso). La copia è in `runCatching`: una clipboard bloccata da un'altra app su Windows non deve far
 * cadere la finestra, al massimo non si copia.
 */
@Composable
fun CopyIconButton(text: String, modifier: Modifier = Modifier, contentDescription: String = "Copia") {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
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
                    val ok = runCatching { clipboard.setClipEntry(ClipEntry(StringSelection(text))) }.isSuccess
                    copiedTick = if (ok) copiedTick + 1 else 0
                }
            },
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

@Suppress("unused")
@Preview
@Composable
private fun CopyIconButtonPreview() {
    PreviewTheme { CopyIconButton(text = "02 30 30 7A", contentDescription = "Copia hex") }
}
