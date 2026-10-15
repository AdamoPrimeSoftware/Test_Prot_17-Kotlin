package it.prime.protocollo17.domain.repository

import it.prime.protocollo17.domain.model.simulator.SimLogEntry
import it.prime.protocollo17.domain.model.simulator.SimStats
import it.prime.protocollo17.domain.model.simulator.SimulatorStatus
import kotlinx.coroutines.flow.StateFlow

/**
 * Controllo del simulatore di terminale dall'app (§5.6).
 *
 * La UI non vede mai l'engine (skill embedded-server §1): solo lo stato utente ([SimulatorStatus], con la porta
 * occupata come stato e non come eccezione), il log già mascherato e i contatori. I metodi [useAsTarget] e
 * [restoreTarget] stanno qui perché li usano sia la finestra Simulatore sia il menu Strumenti.
 */
interface SimulatorController {
    /** Stato del simulatore per la UI (skill embedded-server §1): mai l'engine. */
    val status: StateFlow<SimulatorStatus>

    val log: StateFlow<List<SimLogEntry>>          // ultime 500 voci
    val stats: StateFlow<SimStats>

    /** true se terminal.host/port puntano al simulatore in esecuzione. */
    val isTarget: StateFlow<Boolean>

    /** Avvia sulla porta delle impostazioni; restituisce lo stato finale (Running o Failed). Idempotente se già avviato. */
    suspend fun start(): SimulatorStatus

    suspend fun stop()

    /** terminal.host = 127.0.0.1, terminal.port = porta attiva, salvando la destinazione precedente. false se non è in esecuzione. */
    fun useAsTarget(): Boolean

    /** Rimette previousHost/previousPort. false se non c'è una destinazione salvata. */
    fun restoreTarget(): Boolean

    fun clearLog()
    fun resetState()

    /** stop() + cancellazione dello scope; idempotente. */
    fun close()
}
