package it.prime.protocollo17.data.local.simulator

import it.prime.protocollo17.data.local.settings.SettingsRepository
import it.prime.protocollo17.domain.model.simulator.SimLogEntry
import it.prime.protocollo17.domain.model.simulator.SimStats
import it.prime.protocollo17.domain.model.simulator.SimulatorStatus
import it.prime.protocollo17.domain.repository.SimulatorController
import kotlinx.coroutines.flow.StateFlow

// STUB di WP0 (§8.2): firme definitive di §5.6, corpi a carico di WP5 (comportamento vincolante in §5.6).

/**
 * Ponte tra impostazioni e TerminalSimulator: mappa AppSettings.simulator sulla configurazione dell'engine,
 * lo riavvia al cambio di porta e trasforma i fallimenti (porta occupata) in stato utente, mai in eccezioni.
 */
class SimulatorControllerImpl(
    private val settings: SettingsRepository,
    private val wallClock: () -> Long = System::currentTimeMillis,
) : SimulatorController {

    override val status: StateFlow<SimulatorStatus>
        get() = TODO("WP5: SimulatorControllerImpl.kt")

    override val log: StateFlow<List<SimLogEntry>>
        get() = TODO("WP5: SimulatorControllerImpl.kt")

    override val stats: StateFlow<SimStats>
        get() = TODO("WP5: SimulatorControllerImpl.kt")

    override val isTarget: StateFlow<Boolean>
        get() = TODO("WP5: SimulatorControllerImpl.kt")

    override suspend fun start(): SimulatorStatus = TODO("WP5: SimulatorControllerImpl.kt")

    override suspend fun stop(): Unit = TODO("WP5: SimulatorControllerImpl.kt")

    override fun useAsTarget(): Boolean = TODO("WP5: SimulatorControllerImpl.kt")

    override fun restoreTarget(): Boolean = TODO("WP5: SimulatorControllerImpl.kt")

    override fun clearLog(): Unit = TODO("WP5: SimulatorControllerImpl.kt")

    override fun resetState(): Unit = TODO("WP5: SimulatorControllerImpl.kt")

    override fun close(): Unit = TODO("WP5: SimulatorControllerImpl.kt")
}
