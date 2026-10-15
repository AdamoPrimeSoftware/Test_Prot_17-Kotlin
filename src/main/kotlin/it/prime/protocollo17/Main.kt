package it.prime.protocollo17

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import it.prime.protocollo17.data.local.AppDirs
import it.prime.protocollo17.di.appModule
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

// STUB di WP0 (§8.2): avvia Koin e apre una finestra vuota di bootstrap. NON risolve i singleton del protocollo
// (sono stub che lanciano TODO). La sequenza definitiva di avvio e chiusura (§6.18: SingleInstanceGuard,
// recoverOnStartup del journal, simulatore, ShutdownWatchdog, chiusura ordinata) è a carico di WP6.

private val log = LoggerFactory.getLogger("Protocollo17.Main")

/**
 * Punto d'ingresso: solo sequenza, nessuna logica (skill project-structure).
 *
 * In WP0 serve a dimostrare che build, classpath, logback e grafo Koin stanno in piedi: `.\gradlew.bat run` deve
 * aprire la finestra senza eccezioni nel log `~/.protocollo17/logs/app.log`.
 */
fun main() {
    Thread.setDefaultUncaughtExceptionHandler { t, e -> log.error("Eccezione non gestita sul thread '{}'", t.name, e) }
    val dirs = AppDirs.default().ensure()
    log.info(
        "Protocollo17ComposeDesktop (bootstrap WP0): JVM {} su {} {}, cartella dati {}",
        System.getProperty("java.version"), System.getProperty("os.name"), System.getProperty("os.arch"),
        dirs.root.absolutePath,
    )
    runCatching { startKoin { modules(appModule) } }.onFailure { log.error("Avvio Koin fallito", it); throw it }
    try {
        application(exitProcessOnExit = false) {
            Window(
                onCloseRequest = ::exitApplication,
                title = "Protocollo17ComposeDesktop — bootstrap",
                state = rememberWindowState(width = 720.dp, height = 400.dp),
            ) {
                BootstrapContent(dataFolder = dirs.root.absolutePath)
            }
        }
    } catch (t: Throwable) {
        log.error("Errore nella UI di bootstrap", t)
    } finally {
        runCatching { stopKoin() }
        log.info("Protocollo17ComposeDesktop (bootstrap WP0): chiusura")
    }
    exitProcess(0)
}

/** Contenuto della finestra di bootstrap: solo testo, le finestre vere arrivano con WP6. */
@Composable
private fun BootstrapContent(dataFolder: String) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Protocollo17ComposeDesktop", style = MaterialTheme.typography.headlineSmall)
                Text("Finestra di bootstrap (WP0): le schermate dell'app arrivano con WP6.")
                Text("Cartella dati: $dataFolder", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Suppress("unused")
@Preview
@Composable
private fun BootstrapContentPreview() {
    BootstrapContent(dataFolder = "C:\\Users\\utente\\.protocollo17")
}
