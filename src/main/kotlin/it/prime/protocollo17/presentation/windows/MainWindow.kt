package it.prime.protocollo17.presentation.windows

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import it.prime.protocollo17.data.local.AppDirs
import it.prime.protocollo17.domain.model.journal.JournalEntry
import it.prime.protocollo17.domain.model.journal.JournalPhase
import it.prime.protocollo17.domain.model.simulator.SimulatorFailure
import it.prime.protocollo17.domain.model.simulator.SimulatorStatus
import it.prime.protocollo17.domain.repository.SimulatorController
import it.prime.protocollo17.presentation.components.NoticePresenter
import it.prime.protocollo17.presentation.components.NoticeTone
import it.prime.protocollo17.presentation.components.rememberNoticePresenter
import it.prime.protocollo17.presentation.screen.main.MainScreen
import it.prime.protocollo17.presentation.state.AppNotifications
import it.prime.protocollo17.presentation.state.AppWindowId
import it.prime.protocollo17.presentation.state.AppWindows
import it.prime.protocollo17.presentation.state.CommandRunner
import it.prime.protocollo17.presentation.state.Shortcut
import it.prime.protocollo17.presentation.state.ShortcutBus
import it.prime.protocollo17.presentation.state.closeNeedsConfirmation
import it.prime.protocollo17.presentation.theme.PreviewTheme
import it.prime.protocollo17.presentation.util.AppVersion
import it.prime.protocollo17.presentation.util.ApplyMinimumSize
import it.prime.protocollo17.presentation.util.PickerOwner
import it.prime.protocollo17.presentation.util.TrackPickerOwner
import it.prime.protocollo17.presentation.util.WindowSpec
import it.prime.protocollo17.presentation.util.openInExplorer
import it.prime.protocollo17.presentation.util.persistWindowGeometry
import it.prime.protocollo17.presentation.util.rememberAppIcon
import it.prime.protocollo17.presentation.util.rememberPersistedWindowState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.util.concurrent.atomic.AtomicReference

/** Misure della finestra principale (§6.1): default 1280×800, minimo 960×600 dp (ci sta anche 1920×1080 al 150%). */
val MainWindowSpec = WindowSpec(defaultWidth = 1280f, defaultHeight = 800f, minWidth = 960f, minHeight = 600f)

/** Chiave della geometria della finestra principale in `ui.windows`. */
private const val MAIN_KEY = "MAIN"

/**
 * Finestra principale (§6.18): menu, scorciatoie, avvisi globali e chiusura.
 *
 * Non vede ShutdownWatchdog (data.local.system, vietato alla presentation, §2.10): riceve la lambda [onBeforeExit].
 * Chiudere con un comando journaled già inviato chiede conferma (R11): l'esito resterebbe sconosciuto. Il menu sta
 * sopra il Navigator e non vede il ConsoleViewModel: Comandi e Strumenti passano dal ShortcutBus (il Root della
 * Console decide se un dialog aperto le blocca), Finestre chiama direttamente AppWindows.
 */
@Composable
fun MainWindow(onBeforeExit: () -> Unit, startupRecovered: List<JournalEntry>, onExit: () -> Unit) {
    val windows: AppWindows = koinInject()
    val runner: CommandRunner = koinInject()
    val shortcutBus: ShortcutBus = koinInject()
    val notifications: AppNotifications = koinInject()
    val simulator: SimulatorController = koinInject()
    val dirs: AppDirs = koinInject()

    val state = rememberPersistedWindowState(MAIN_KEY, MainWindowSpec, windows)
    val awtWindow = remember { AtomicReference<java.awt.Window?>(null) }
    var confirmClose by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }

    fun exitNow() {
        confirmClose = false
        runCatching { onBeforeExit() }
        // Ultimo salvataggio della geometria (quello in continuo ha un debounce di 500 ms).
        runCatching { persistWindowGeometry(MAIN_KEY, state, windows) }
        runCatching { awtWindow.get()?.isVisible = false }
        onExit()
    }

    fun requestClose() {
        if (closeNeedsConfirmation(runner.state.value)) confirmClose = true else exitNow()
    }

    Window(
        onCloseRequest = ::requestClose,
        title = "Protocollo17ComposeDesktop",
        icon = rememberAppIcon(),
        state = state,
        onKeyEvent = { mainWindowShortcut(it, shortcutBus) },
    ) {
        ApplyMinimumSize(MainWindowSpec)
        LaunchedEffect(window) {
            // Dialog di file con owner: senza, possono finire dietro la finestra e l'app sembra bloccata.
            PickerOwner.window = window
            awtWindow.set(window)
        }
        TrackPickerOwner()

        val runnerState by runner.state.collectAsState()
        val simulatorStatus by simulator.status.collectAsState()
        val idle = runnerState.running == null
        MenuBar {
            Menu("File") {
                Item("Apri cartella dati", onClick = { openInExplorer(dirs.root) })
                Separator()
                Item("Esci", onClick = ::requestClose)
            }
            Menu("Comandi") {
                Item("Esegui comando selezionato    Ctrl+Invio", enabled = idle, onClick = { shortcutBus.send(Shortcut.RunSelected) })
                Item(
                    "Annulla comando    Esc",
                    enabled = runnerState.running?.cancelRequested == false,
                    onClick = { shortcutBus.send(Shortcut.StopCurrentJob) },
                )
                Item("Stato terminale    F5", enabled = idle, onClick = { shortcutBus.send(Shortcut.PingStatus) })
                Item("Test connessione", enabled = idle, onClick = { shortcutBus.send(Shortcut.TestConnection) })
            }
            Menu("Finestre") {
                Item("Opzioni", shortcut = KeyShortcut(Key.Comma, ctrl = true), onClick = { windows.open(AppWindowId.OPTIONS) })
                Item("Log traffico", shortcut = KeyShortcut(Key.L, ctrl = true), onClick = { windows.open(AppWindowId.TRAFFIC_LOG) })
                Item("Journal", shortcut = KeyShortcut(Key.J, ctrl = true), onClick = { windows.open(AppWindowId.JOURNAL) })
                Item("Simulatore", shortcut = KeyShortcut(Key.S, ctrl = true, shift = true), onClick = { windows.open(AppWindowId.SIMULATOR) })
            }
            Menu("Strumenti") {
                if (simulatorStatus is SimulatorStatus.Running) {
                    Item("Ferma simulatore", enabled = idle, onClick = { shortcutBus.send(Shortcut.StopSimulator) })
                } else {
                    Item("Avvia simulatore", enabled = idle && simulatorStatus !is SimulatorStatus.Starting, onClick = { shortcutBus.send(Shortcut.StartSimulator) })
                }
                Item(
                    "Usa simulatore come terminale",
                    enabled = idle && simulatorStatus is SimulatorStatus.Running,
                    onClick = { shortcutBus.send(Shortcut.UseSimulatorAsTarget) },
                )
                Item("Ripristina terminale", enabled = idle, onClick = { shortcutBus.send(Shortcut.RestoreTerminal) })
            }
            Menu("Aiuto") {
                Item("Informazioni", onClick = { showAbout = true })
            }
        }

        val notices = rememberNoticePresenter()
        NotificationsEffect(notifications, notices, windows, simulator, startupRecovered)

        WindowFrame(notices = notices) {
            Navigator(remember { MainScreen() }, key = "nav-main") { CurrentScreen() }
            if (confirmClose) CloseConfirmDialog(onConfirm = ::exitNow, onDismiss = { confirmClose = false })
            if (showAbout) AboutDialog(dataFolder = dirs.root.absolutePath, onDismiss = { showAbout = false })
        }
    }
}

/**
 * Avvisi globali sullo snackbar della finestra principale: AppNotifications (esiti, journal, simulatore), il
 * riepilogo del recupero all'avvio e ogni passaggio del simulatore a Failed (anche dell'avvio automatico).
 *
 * Il collect parte UNDISPATCHED: AppNotifications non ha replay, e un avviso emesso prima dell'iscrizione andrebbe
 * perso. Ogni avviso passa dal NoticePresenter della finestra, lo stesso degli eventi della Console: SOSTITUISCE
 * quello visibile invece di accodarsi (§6.18).
 */
@Composable
private fun NotificationsEffect(
    notifications: AppNotifications,
    notices: NoticePresenter,
    windows: AppWindows,
    simulator: SimulatorController,
    startupRecovered: List<JournalEntry>,
) {
    LaunchedEffect(notifications, notices) {
        launch(start = CoroutineStart.UNDISPATCHED) {
            notifications.events.collect { notice ->
                val tone = when (notice.kind) {
                    AppNotifications.Kind.SUCCESS -> NoticeTone.SUCCESS
                    AppNotifications.Kind.WARNING -> NoticeTone.WARNING
                    AppNotifications.Kind.ERROR -> NoticeTone.ERROR
                    AppNotifications.Kind.INFO -> NoticeTone.INFO
                }
                notices.show(
                    tone = tone,
                    message = "${notice.source}: ${notice.message}",
                    actionLabel = notice.openWindow?.let { "Apri" },
                    onAction = { notice.openWindow?.let(windows::open) },
                )
            }
        }
        // G mai automatico (U7): all'avvio solo l'avviso; il banner della Console mostra i tentativi.
        val interrupted = startupRecovered.count { it.phase == JournalPhase.UNKNOWN }
        if (interrupted > 0) {
            notifications.error(
                "Journal",
                "All'ultimo avvio $interrupted ${if (interrupted == 1) "operazione è rimasta" else "operazioni sono rimaste"} senza esito: " +
                    "pagamenti bloccati finché non le risolvi",
                openWindow = AppWindowId.JOURNAL,
            )
        }
        var wasFailed = false
        simulator.status.collect { status ->
            val failed = status as? SimulatorStatus.Failed
            if (failed != null && !wasFailed) {
                val message = when (val f = failed.failure) {
                    is SimulatorFailure.PortInUse -> "Porta ${f.port} già in uso: cambiala nella finestra Simulatore"
                    is SimulatorFailure.Unexpected -> "Avvio non riuscito: ${f.message}"
                }
                notifications.error("Simulatore", message, openWindow = AppWindowId.SIMULATOR)
            }
            wasFailed = failed != null
        }
    }
}

/**
 * Scorciatoie della finestra principale (§6.15). Esc NON è una combinazione: sta fuori dal ramo Ctrl e restituisce
 * false, così chiude anche menu e dialog; il Root della Console decide se annullare (`escapeCancels`).
 */
private fun mainWindowShortcut(event: KeyEvent, bus: ShortcutBus): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    if (!event.isCtrlPressed) {
        return when (event.key) {
            Key.Escape -> {
                bus.send(Shortcut.StopCurrentJob)
                false
            }
            Key.F5 -> {
                bus.send(Shortcut.PingStatus)
                true
            }
            else -> false
        }
    }
    val shortcut = when (event.key) {
        Key.Enter, Key.NumPadEnter -> Shortcut.RunSelected
        Key.DirectionDown -> Shortcut.SelectNext
        Key.DirectionUp -> Shortcut.SelectPrevious
        else -> return false
    }
    bus.send(shortcut)
    return true
}

@Composable
private fun CloseConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Comando in corso") },
        text = {
            Text(
                "Un comando finanziario è in corso: chiudendo, l'esito resterà SCONOSCIUTO e sarà registrato nel journal. " +
                    "Chiudere comunque?",
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
            ) { Text("Chiudi comunque") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
    )
}

@Composable
private fun AboutDialog(dataFolder: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Protocollo17ComposeDesktop ${AppVersion.current}") },
        text = {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("App di test per terminali POS Protocollo 17 / ECR17 su TCP (profili Ingenico e CCV).")
                    Text("Cartella dati: $dataFolder", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "Reimplementazione indipendente del protocollo ECR17. Il comportamento è stato studiato, senza " +
                            "copiarne il codice, nei progetti di padosoft (rust-ecr17-protocol, react-native-ecr17-protocol, " +
                            "laravel-ecr17): grazie per il lavoro pubblicato. Il filo del terminale Ingenico è stato " +
                            "ricostruito dai log della DLL IAE37 (Ingenico Italia S.p.A.).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text("© 2026 Prime Software", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Chiudi") } },
    )
}

@Suppress("unused")
@Preview
@Composable
private fun MainWindowDialogsPreview() {
    PreviewTheme {
        Box(Modifier.fillMaxSize()) {
            CloseConfirmDialog(onConfirm = {}, onDismiss = {})
        }
    }
}

@Suppress("unused")
@Preview
@Composable
private fun AboutDialogPreview() {
    PreviewTheme { AboutDialog(dataFolder = "C:\\Users\\utente\\.protocollo17", onDismiss = {}) }
}
