package it.prime.protocollo17.presentation.util

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.window.FrameWindowScope
import it.prime.protocollo17.presentation.theme.PreviewTheme
import org.slf4j.LoggerFactory
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import javax.swing.JFileChooser
import javax.swing.UIManager
import javax.swing.filechooser.FileNameExtensionFilter

private val pickerLog = LoggerFactory.getLogger("FilePickers")

/**
 * Finestra proprietaria dei dialog di scelta file.
 *
 * Un `JFileChooser` senza owner è APPLICATION_MODAL ma non appartiene a nessuna finestra: se finisce dietro, l'app
 * sembra bloccata senza motivo visibile. La imposta `MainWindow` all'avvio e ogni finestra quando prende il focus
 * ([TrackPickerOwner]): "Esporta…" del Log traffico apre il dialog davanti al Log, non dietro la principale.
 */
object PickerOwner {
    @Volatile
    var window: java.awt.Window? = null
}

/** Look & feel di sistema applicato una volta sola: `setLookAndFeel` ricostruisce la UI di Swing a ogni chiamata. */
private val systemLookAndFeel: Unit by lazy {
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
    Unit
}

/**
 * Dialog nativo di salvataggio; aggiunge [extension] se il nome scelto non la ha. null = annullato o errore.
 *
 * Blocca l'EDT finché il dialog è aperto: va chiamato SOLO da un `onClick`, mai durante la composizione. Tutto è in
 * `runCatching`: un `HeadlessException` dentro un `onClick` chiuderebbe la finestra.
 */
fun openSaveDialog(title: String, suggestedName: String, extension: String): File? = runCatching {
    systemLookAndFeel
    val chooser = JFileChooser().apply {
        dialogTitle = title
        selectedFile = File(suggestedName)
        fileFilter = FileNameExtensionFilter("${extension.uppercase()} (*.$extension)", extension)
    }
    when (chooser.showSaveDialog(PickerOwner.window)) {
        JFileChooser.APPROVE_OPTION -> chooser.selectedFile?.let { file ->
            if (file.name.endsWith(".$extension", ignoreCase = true)) file else File(file.parentFile, "${file.name}.$extension")
        }
        else -> null
    }
}.getOrElse { e ->
    pickerLog.warn("openSaveDialog fallito: {}", e.message, e)
    null
}

/** La finestra corrente diventa proprietaria dei dialog di file quando prende il focus. */
@Composable
fun FrameWindowScope.TrackPickerOwner() {
    DisposableEffect(window) {
        val awtWindow = window
        val listener = object : WindowAdapter() {
            override fun windowGainedFocus(e: WindowEvent?) {
                PickerOwner.window = awtWindow
            }
        }
        awtWindow.addWindowFocusListener(listener)
        onDispose {
            awtWindow.removeWindowFocusListener(listener)
            if (PickerOwner.window === awtWindow) PickerOwner.window = null
        }
    }
}

// ─── Preview ──────────────────────────────────────────────────────────────────────

@Suppress("unused")
@Preview
@Composable
private fun FilePickersPreview() {
    // TrackPickerOwner è un effetto di finestra e il dialog di file è AWT: l'anteprima spiega solo il comportamento.
    PreviewTheme { Text("TrackPickerOwner: effetto senza UI (la finestra con il focus è proprietaria dei dialog di file)") }
}
