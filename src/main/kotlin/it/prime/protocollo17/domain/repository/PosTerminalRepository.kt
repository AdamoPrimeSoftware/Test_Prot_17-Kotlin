package it.prime.protocollo17.domain.repository

import it.prime.protocollo17.domain.model.command.CommandProgress
import it.prime.protocollo17.domain.model.command.ConfigResolution
import it.prime.protocollo17.ecr17.error.Ecr17Failure
import it.prime.protocollo17.ecr17.model.CommandCode
import it.prime.protocollo17.ecr17.model.ConnectionTestResult
import it.prime.protocollo17.ecr17.model.Ecr17Request
import it.prime.protocollo17.ecr17.model.LinkState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Esecuzione dei comandi verso il terminale, con il journal dei tentativi agganciato (§5.5).
 *
 * Il servizio applicativo e i ViewModel dipendono da questa interfaccia e mai dal client ECR17: così si provano
 * con un fake, e il legame protocollo ↔ journal (record SENDING prima di ogni scrittura, M3) sta in un solo posto.
 */
interface PosTerminalRepository {
    val linkState: StateFlow<LinkState>
    val busy: StateFlow<CommandCode?>

    /** Configurazione effettiva corrente (per la UI e per i controlli prima dell'esecuzione). */
    fun effectiveConfig(): ConfigResolution

    /** Esegue un comando: Started, Phase…, Progress…, Receipt…, Warning…, poi ESATTAMENTE un Finished (salvo cancellazione del collector). */
    fun execute(request: Ecr17Request): Flow<CommandProgress>

    fun cancelCurrent(): Boolean
    suspend fun testConnection(): ConnectionTestResult
    suspend fun connect(): Ecr17Failure?
    suspend fun disconnect(): Ecr17Failure?
}
