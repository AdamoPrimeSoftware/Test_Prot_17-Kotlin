package it.prime.protocollo17.ecr17.sim

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

// STUB di WP0 (§8.2): firme definitive di §4.3, corpi a carico di WP4. I tipi (SimulatorConfig, SimEvent,
// SimState) sono già completi in SimulatorConfig.kt.

/**
 * Terminale finto su TCP loopback che parla il filo Ingenico osservato: serve ai test di integrazione reali
 * (socket, lettore, timeout) e all'operatore per provare comandi e guasti senza hardware (§4.1).
 */
class TerminalSimulator(
    initial: SimulatorConfig = SimulatorConfig(),
    private val wallClock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    val config: StateFlow<SimulatorConfig>
        get() = TODO("WP4: TerminalSimulator.kt")

    /** Porta in ascolto, null se fermo. */
    val boundPort: StateFlow<Int?>
        get() = TODO("WP4: TerminalSimulator.kt")

    /** Log del simulatore (extraBufferCapacity 512, DROP_OLDEST). */
    val events: SharedFlow<SimEvent>
        get() = TODO("WP4: TerminalSimulator.kt")

    /** Vale dalla prossima richiesta ricevuta. bindHost e port valgono solo dal prossimo start(). */
    fun updateConfig(transform: (SimulatorConfig) -> SimulatorConfig): Unit = TODO("WP4: TerminalSimulator.kt")

    /** Bind su bindHost:port; restituisce la porta effettiva. java.net.BindException se la porta è occupata; IllegalStateException se già avviato. */
    suspend fun start(): Int = TODO("WP4: TerminalSimulator.kt")

    /** Chiude server e connessioni e libera la porta; idempotente. */
    suspend fun stop(): Unit = TODO("WP4: TerminalSimulator.kt")

    /** Contatori e ultimo esito, per i test. */
    fun snapshot(): SimState = TODO("WP4: TerminalSimulator.kt")

    /** Azzera contatori, STAN, totali, ultimo esito, esito trattenuto. */
    fun resetState(): Unit = TODO("WP4: TerminalSimulator.kt")

    override fun close(): Unit = TODO("WP4: TerminalSimulator.kt")
}
