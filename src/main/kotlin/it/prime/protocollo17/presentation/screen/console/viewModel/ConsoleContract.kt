package it.prime.protocollo17.presentation.screen.console.viewModel

import it.prime.protocollo17.domain.model.command.FieldKey
import it.prime.protocollo17.domain.model.journal.ResolutionKind
import it.prime.protocollo17.domain.model.simulator.SimulatorStatus
import it.prime.protocollo17.ecr17.model.CommandCode
import it.prime.protocollo17.ecr17.profile.ConnectionMode
import it.prime.protocollo17.ecr17.profile.Evidence
import it.prime.protocollo17.ecr17.profile.FinancialAckPolicy
import it.prime.protocollo17.presentation.components.ResolveDialogModel
import it.prime.protocollo17.presentation.model.HistoryRowView
import it.prime.protocollo17.presentation.model.ResultView
import it.prime.protocollo17.presentation.state.RunningCommand

// Contratto MVI della Console (§6.5): stato, azioni ed eventi della finestra principale.

/** Tono del pallino del collegamento nell'intestazione. */
enum class LinkTone { IDLE, CONNECTING, CONNECTED, CLOSED, ERROR }

data class LinkView(val tone: LinkTone = LinkTone.IDLE, val label: String = "Inattivo")

/** Intestazione di connessione (§6.10): profilo, destinazione, switch U4/U5, LRC, collegamento, TID. */
data class ConnectionView(
    val profileName: String = "Ingenico",
    val evidence: Evidence = Evidence.CAPTURE,
    val endpoint: String = "",
    val connectionMode: ConnectionMode = ConnectionMode.PER_COMMAND,
    val ackPolicy: FinancialAckPolicy = FinancialAckPolicy.IMPLICIT_GO_AHEAD,
    val lrcSummary: String = "stx · ACK 06 03 7A",      // calcolato con ControlLrcRule.encode + Hex.spaced
    val link: LinkView = LinkView(),
    val lastSeenTerminalId: String? = null,
    val configUsable: Boolean = true,
    /** Issues, problemi e avvisi di configurazione già in italiano. */
    val configMessages: List<String> = emptyList(),
    /** Test connessione in corso. */
    val testing: Boolean = false,
    /** Connetti o Disconnetti in corso (solo PERSISTENT). */
    val switching: Boolean = false,
)

data class SimulatorView(
    val status: SimulatorStatus = SimulatorStatus.Stopped,
    val isTarget: Boolean = false,
    /** "Simulatore attivo su 127.0.0.1:15577" / "Porta 15577 in uso: cambiala nel Simulatore" / "Simulatore fermo". */
    val label: String = "Simulatore fermo",
)

/** Tentativo con esito sconosciuto o da verificare, per il banner (§6.8). */
data class UnresolvedView(
    val attemptId: String,
    val title: String,            // "Pagamento 0,10 €" (o "Tentativo con dati incompleti")
    val time: String,             // "14/10 09:35:05"
    val reason: String,           // da FailureMessages, es. "nessun esito entro 180 s dopo l'ACK" o "possibile doppio addebito"
    val needsReview: Boolean,     // esito ricevuto ma da verificare
    val blocksPayments: Boolean,
)

enum class ResultTab { RESULT, HISTORY }

sealed interface ConsoleDialog {
    data class ConfirmRun(val command: CommandCode, val message: String) : ConsoleDialog

    /** message costruito da command.movesMoney: blocco dei pagamenti solo per i comandi che muovono denaro. */
    data class ConfirmCancelAfterSend(val command: CommandCode, val message: String) : ConsoleDialog

    data class Resolve(val model: ResolveDialogModel) : ConsoleDialog
    data class BlockedInfo(val attempts: List<UnresolvedView>) : ConsoleDialog
}

/** Stato della Console. Le regole di abilitazione sono proprietà calcolate: testabili senza Compose. */
data class ConsoleUiState(
    val selected: CommandCode = CommandCode.PAYMENT,
    val drafts: Map<CommandCode, Map<FieldKey, String>> = emptyMap(),
    /** Errori di campo del comando selezionato (dopo un tentativo di esecuzione o in digitazione). */
    val fieldErrors: Map<FieldKey, String> = emptyMap(),
    val running: RunningCommand? = null,
    val results: Map<CommandCode, ResultView> = emptyMap(),
    val resultTab: ResultTab = ResultTab.RESULT,
    val history: List<HistoryRowView> = emptyList(),
    /** Voce di cronologia mostrata nella scheda Esito al posto dell'ultimo esito del comando selezionato. */
    val viewedHistory: ResultView? = null,
    /** Testo completo della cronologia per "Copia" (generato dal VM). */
    val historyText: String = "",
    val connection: ConnectionView = ConnectionView(),
    val unresolved: List<UnresolvedView> = emptyList(),
    /** Da PaymentBlockPolicy.blockedCommands(journal.unresolved). */
    val blockedCommands: Set<CommandCode> = emptySet(),
    val dialog: ConsoleDialog? = null,
    val simulator: SimulatorView = SimulatorView(),
) {
    val busy: Boolean get() = running != null

    /**
     * Test connessione, Connetti o Disconnetti in corso: tengono il lock del client come un comando. Prima i comandi
     * restavano attivi e un pagamento lanciato in quel momento scriveva il tentativo nel journal e finiva subito
     * `NotSent(Busy)`; ora si disabilitano come durante un comando (M7: mai accodati).
     */
    val linkBusy: Boolean get() = connection.testing || connection.switching
    val paymentsBlocked: Boolean get() = unresolved.any { it.blocksPayments }
    fun isBlocked(command: CommandCode): Boolean = command in blockedCommands
    val canRunSelected: Boolean get() = !busy && !linkBusy && !isBlocked(selected) && connection.configUsable
    val canCancel: Boolean get() = running?.cancelRequested == false
    val canRecoverWithG: Boolean get() = !busy && !linkBusy && unresolved.isNotEmpty() && connection.configUsable

    /**
     * Azioni della barra (Stato, Test connessione, Connetti/Disconnetti): disabilitate durante un comando e durante
     * un'operazione di collegamento. Il menu Strumenti non legge questo valore: MainWindow sta sopra il Navigator, non
     * vede il ConsoleViewModel e abilita le sue voci solo con il runner inattivo (§6.15).
     */
    val barActionsEnabled: Boolean get() = !busy && !linkBusy

    /** Esc annulla il comando solo se nessun dialog è aperto (con un dialog aperto Esc chiude il dialog e basta). */
    val escapeCancels: Boolean get() = canCancel && dialog == null

    /** Ctrl+Invio, F5, Ctrl+frecce: ignorati con un dialog aperto. */
    val shortcutsEnabled: Boolean get() = dialog == null
}

sealed interface ConsoleAction {
    data class Select(val command: CommandCode) : ConsoleAction
    data object SelectNext : ConsoleAction
    data object SelectPrevious : ConsoleAction
    data class UpdateField(val command: CommandCode, val key: FieldKey, val value: String) : ConsoleAction
    data class ResetForm(val command: CommandCode) : ConsoleAction

    /** Azione rapida del pannello esito (§6.7): copia un valore nel form di un altro comando e lo seleziona. */
    data class CopyToForm(val target: CommandCode, val key: FieldKey, val value: String) : ConsoleAction
    data object RunSelected : ConsoleAction
    data object ConfirmRun : ConsoleAction
    data object PingStatus : ConsoleAction
    data object RequestCancel : ConsoleAction
    data object ConfirmCancel : ConsoleAction
    data object RecoverWithG : ConsoleAction
    data class OpenResolve(val attemptId: String) : ConsoleAction            // chiusura senza G
    data class Resolve(val attemptId: String, val kind: ResolutionKind, val note: String) : ConsoleAction
    data object DismissDialog : ConsoleAction
    data object TestConnection : ConsoleAction
    data object Connect : ConsoleAction                                      // solo PERSISTENT
    data object Disconnect : ConsoleAction
    data class SetResultTab(val tab: ResultTab) : ConsoleAction
    data class ShowHistoryItem(val id: Long?) : ConsoleAction                // null = torna all'ultimo esito
    data object ClearHistory : ConsoleAction
    data object StartSimulator : ConsoleAction
    data object StopSimulator : ConsoleAction
    data object UseSimulatorAsTarget : ConsoleAction
    data object RestoreTerminal : ConsoleAction
}

sealed interface ConsoleEvent {
    data class ShowError(val message: String) : ConsoleEvent
    data class ShowInfo(val message: String) : ConsoleEvent
    data class ShowSuccess(val message: String) : ConsoleEvent
}
