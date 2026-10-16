package it.prime.protocollo17

import androidx.compose.ui.window.application
import it.prime.protocollo17.data.local.AppDirs
import it.prime.protocollo17.data.local.settings.SettingsRepository
import it.prime.protocollo17.data.local.system.ShutdownWatchdog
import it.prime.protocollo17.data.local.system.SingleInstanceGuard
import it.prime.protocollo17.data.local.traffic.TrafficRecorder
import it.prime.protocollo17.di.appModule
import it.prime.protocollo17.domain.model.journal.JournalEntry
import it.prime.protocollo17.domain.repository.JournalRepository
import it.prime.protocollo17.domain.repository.SimulatorController
import it.prime.protocollo17.ecr17.client.Ecr17Client
import it.prime.protocollo17.presentation.state.TerminalSessionService
import it.prime.protocollo17.presentation.util.AppVersion
import it.prime.protocollo17.presentation.windows.MainWindow
import it.prime.protocollo17.presentation.windows.SecondaryWindows
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.slf4j.LoggerFactory
import javax.swing.JOptionPane
import kotlin.system.exitProcess

// Punto d'ingresso (§6.18): solo sequenza, nessuna logica (skill project-structure). È l'unico file che vede
// ShutdownWatchdog e le classi concrete da chiudere: la presentation riceve solo la lambda onBeforeExit (§2.10).

private val log = LoggerFactory.getLogger("Protocollo17.Main")

/**
 * Avvio: gestore globale delle eccezioni, cartelle, istanza unica, Koin, recupero del journal PRIMA della UI (i
 * tentativi interrotti all'ultimo avvio devono bloccare i pagamenti già alla prima schermata), simulatore (con
 * avvio automatico se impostato), poi la UI.
 *
 * Chiusura GARANTITA: `application(exitProcessOnExit = false)` ritorna (o lancia) quando le finestre sono smontate;
 * il `finally` arma il guardiano, chiude le risorse in ordine e termina il processo. Un'eccezione in un
 * `onDispose` non lascia più una finestra bianca con la JVM viva e il lucchetto preso (LocalDesktopAi, 2026-10-10).
 */
fun main() {
    Thread.setDefaultUncaughtExceptionHandler { t, e -> log.error("Eccezione non gestita sul thread '{}'", t.name, e) }
    val dirs = AppDirs.default().ensure()
    logBanner(dirs)
    if (SingleInstanceGuard.acquire(dirs.lockFile) is SingleInstanceGuard.Attempt.HeldByOther) {
        showAlreadyRunningDialog()
        exitProcess(0)
    }
    runCatching { startKoin { modules(appModule) } }.onFailure { log.error("Avvio Koin fallito", it); throw it }
    val koin = GlobalContext.get()
    val recovered = recoverJournalOrExit()
    koin.get<SimulatorController>()                   // crea il controller (autoStart se impostato)
    val watchdog = ShutdownWatchdog()
    try {
        runUi(onBeforeExit = { runCatching { watchdog.arm() } }, recovered = recovered)
    } catch (t: Throwable) {
        log.error("Errore nella UI", t)
    } finally {
        runCatching { watchdog.arm() }
        closeAppResources()
        exitProcess(0)
    }
}

private fun runUi(onBeforeExit: () -> Unit, recovered: List<JournalEntry>) = application(exitProcessOnExit = false) {
    MainWindow(onBeforeExit = onBeforeExit, startupRecovered = recovered, onExit = ::exitApplication)
    SecondaryWindows()
}

/**
 * Recupero del journal all'avvio (chiude come UNKNOWN i tentativi interrotti). Se il journal non è leggibile l'app
 * NON parte: partire senza sapere quali pagamenti sono rimasti senza esito toglierebbe il blocco (M6).
 */
private fun recoverJournalOrExit(): List<JournalEntry> = try {
    runBlocking(Dispatchers.IO) { GlobalContext.get().get<JournalRepository>().recoverOnStartup() }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    log.error("Recupero del journal fallito: l'app non parte", e)
    runCatching {
        JOptionPane.showMessageDialog(
            null,
            "Il journal dei tentativi non è leggibile (${e.message}).\n" +
                "L'app non parte per non perdere i pagamenti con esito da risolvere.\n" +
                "Controlla ~/.protocollo17/journal e il file logs/app.log.",
            "Protocollo17ComposeDesktop",
            JOptionPane.ERROR_MESSAGE,
        )
    }
    runCatching { SingleInstanceGuard.release() }
    exitProcess(1)
}

/**
 * Ogni passo nel proprio runCatching. Ordine (skill di-koin + embedded-server): comando in corso (il journal
 * scrive UNKNOWN/NOT_SENT finché il simulatore è ancora vivo), simulatore (la porta si libera prima di chiudere
 * i client), client, log, journal, settings, lock.
 */
private fun closeAppResources() {
    val koin = runCatching { GlobalContext.get() }.getOrNull() ?: return
    runCatching { koin.get<TerminalSessionService>().shutdown(timeoutMs = 3_000) }
        .onFailure { log.warn("Chiusura del comando in corso non riuscita: {}", it.message) }
    runCatching { koin.get<SimulatorController>().close() }
    runCatching { koin.get<Ecr17Client>().close() }
    runCatching { koin.get<TrafficRecorder>().close() }
    runCatching { koin.get<JournalRepository>().close() }
    runCatching { koin.get<SettingsRepository>().close() }
    runCatching { SingleInstanceGuard.release() }
    log.info("Protocollo17ComposeDesktop: chiusura completata")
}

/**
 * Versione dell'app, JVM, runtime, OS, cartella dati e file di log: la prima cosa da guardare quando si chiede un
 * log all'operatore. Prima mancava la versione e non si capiva quale MSI avesse prodotto il log; poi mancava il
 * runtime, e nello stesso app.log un avvio da `gradlew run` (JDK completo) e uno dall'MSI (runtime jlink in
 * `<installazione>\runtime`) erano indistinguibili. Il pid separa due processi, per esempio una seconda istanza.
 */
private fun logBanner(dirs: AppDirs) {
    log.info("============================================================")
    log.info(
        "Protocollo17ComposeDesktop {} avvio: JVM {} ({}), OS {} {} {}",
        AppVersion.current,
        System.getProperty("java.version"), System.getProperty("java.vendor"),
        System.getProperty("os.name"), System.getProperty("os.version"), System.getProperty("os.arch"),
    )
    log.info("Runtime: {} (pid {})", System.getProperty("java.home"), ProcessHandle.current().pid())
    log.info("Cartella dati: {}", dirs.root.absolutePath)
    log.info("File di log: {}", dirs.logsDir.resolve("app.log").absolutePath)
    log.info("============================================================")
}

/** Seconda istanza: due app sullo stesso journal e sullo stesso terminale si confonderebbero gli esiti (R10). */
private fun showAlreadyRunningDialog() {
    log.warn("Un'altra istanza è già in esecuzione: chiudo")
    runCatching {
        JOptionPane.showMessageDialog(
            null,
            "Protocollo17ComposeDesktop è già aperto.\nUsa la finestra esistente: due istanze condividerebbero journal e terminale.",
            "Protocollo17ComposeDesktop",
            JOptionPane.WARNING_MESSAGE,
        )
    }
}
