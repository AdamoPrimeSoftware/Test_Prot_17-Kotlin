package it.prime.protocollo17.data.repository

import it.prime.protocollo17.domain.model.command.CommandProgress
import it.prime.protocollo17.domain.model.command.ConfigResolution
import it.prime.protocollo17.domain.model.settings.AppSettings
import it.prime.protocollo17.domain.repository.JournalRepository
import it.prime.protocollo17.domain.repository.PosTerminalRepository
import it.prime.protocollo17.ecr17.client.Ecr17Client
import it.prime.protocollo17.ecr17.error.Ecr17Failure
import it.prime.protocollo17.ecr17.model.CommandCode
import it.prime.protocollo17.ecr17.model.ConnectionTestResult
import it.prime.protocollo17.ecr17.model.Ecr17Request
import it.prime.protocollo17.ecr17.model.LinkState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

// STUB di WP0 (§8.2): firme definitive di §5.5, corpi a carico di WP5 (execute vincolante in §5.5).

/**
 * Unico punto in cui client ECR17 e journal si incontrano: CREATED prima della connessione, SENDING prima di ogni
 * scrittura (M3), chiusura del tentativo sempre scritta in NonCancellable e mai al posto dell'esito vero (R7).
 */
class PosTerminalRepositoryImpl(
    private val client: Ecr17Client,
    private val journal: JournalRepository,
    private val settingsProvider: () -> AppSettings,
) : PosTerminalRepository {

    override val linkState: StateFlow<LinkState>
        get() = TODO("WP5: PosTerminalRepositoryImpl.kt")

    override val busy: StateFlow<CommandCode?>
        get() = TODO("WP5: PosTerminalRepositoryImpl.kt")

    override fun effectiveConfig(): ConfigResolution = TODO("WP5: PosTerminalRepositoryImpl.kt")

    override fun execute(request: Ecr17Request): Flow<CommandProgress> = TODO("WP5: PosTerminalRepositoryImpl.kt")

    override fun cancelCurrent(): Boolean = TODO("WP5: PosTerminalRepositoryImpl.kt")

    override suspend fun testConnection(): ConnectionTestResult = TODO("WP5: PosTerminalRepositoryImpl.kt")

    override suspend fun connect(): Ecr17Failure? = TODO("WP5: PosTerminalRepositoryImpl.kt")

    override suspend fun disconnect(): Ecr17Failure? = TODO("WP5: PosTerminalRepositoryImpl.kt")
}
