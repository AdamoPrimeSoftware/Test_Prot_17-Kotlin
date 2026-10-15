package it.prime.protocollo17.presentation.util

import org.slf4j.LoggerFactory
import java.awt.Desktop
import java.io.File

private val desktopLog = LoggerFactory.getLogger("DesktopActions")

/**
 * Apre una cartella (o la cartella di un file) in Esplora risorse: cartella dati, log traffico, journal.
 *
 * Chiamata SOLO dagli `onClick` delle view, mai dai ViewModel (che non conoscono AWT). Gira su un thread a parte:
 * `Desktop.open` passa dalla shell di Windows e può impiegare un attimo, che sull'EDT congelerebbe la finestra.
 * Tutto in `runCatching`: un'eccezione AWT dentro un `onClick` non deve mai chiudere l'app.
 */
fun openInExplorer(file: File) {
    val target = when {
        file.isDirectory -> file
        file.exists() -> file.parentFile ?: file
        else -> generateSequence(file) { it.parentFile }.firstOrNull { it.isDirectory } ?: return
    }
    Thread({
        runCatching {
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                error("Desktop.open non supportato su questo sistema")
            }
            Desktop.getDesktop().open(target)
        }.onFailure { desktopLog.warn("Apertura di {} non riuscita: {}", target, it.message) }
    }, "open-in-explorer").apply { isDaemon = true }.start()
}
