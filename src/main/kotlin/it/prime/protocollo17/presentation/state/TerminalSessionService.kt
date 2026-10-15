package it.prime.protocollo17.presentation.state

import it.prime.protocollo17.domain.repository.JournalRepository
import it.prime.protocollo17.domain.repository.PosTerminalRepository
import it.prime.protocollo17.ecr17.model.Ecr17Request
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

// STUB di WP0 (§8.2): firme definitive di §6.4, corpi a carico di WP5 (comportamento vincolante in §6.4).

/**
 * App-scoped: i comandi sopravvivono alla chiusura delle finestre secondarie. Nessun import Compose.
 *
 * Applica la seconda difesa del blocco pagamenti (PaymentBlockPolicy, oltre al pulsante disabilitato), rifiuta
 * un secondo comando invece di accodarlo (M7) e pubblica le notifiche di fine comando per tutta l'app.
 */
class TerminalSessionService(
    private val repository: PosTerminalRepository,
    private val journal: JournalRepository,
    private val notifications: AppNotifications,
    private val wallClock: () -> Long = System::currentTimeMillis,
) : CommandRunner, AutoCloseable {

    override val state: StateFlow<RunnerState>
        get() = TODO("WP5: TerminalSessionService.kt")

    override val finished: SharedFlow<FinishedRun>
        get() = TODO("WP5: TerminalSessionService.kt")

    override fun run(request: Ecr17Request, origin: RunOrigin): RunStart = TODO("WP5: TerminalSessionService.kt")

    override fun requestCancel(): CancelResult = TODO("WP5: TerminalSessionService.kt")

    override fun clearHistory(): Unit = TODO("WP5: TerminalSessionService.kt")

    /** Chiusura app (fuori dall'EDT): annulla il comando in corso e attende al massimo timeoutMs che il journal sia scritto. */
    fun shutdown(timeoutMs: Long = 3_000): Unit = TODO("WP5: TerminalSessionService.kt")

    override fun close(): Unit = TODO("WP5: TerminalSessionService.kt")
}
