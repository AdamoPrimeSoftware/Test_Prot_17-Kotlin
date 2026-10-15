package it.prime.protocollo17.data.repository

import it.prime.protocollo17.data.local.journal.JournalStore
import it.prime.protocollo17.domain.model.journal.JournalEntry
import it.prime.protocollo17.domain.model.journal.JournalPhase
import it.prime.protocollo17.domain.model.journal.KnownResult
import it.prime.protocollo17.domain.model.journal.ResolutionKind
import it.prime.protocollo17.domain.model.settings.JournalSettings
import it.prime.protocollo17.domain.repository.JournalRepository
import it.prime.protocollo17.ecr17.model.CommandOutcome
import it.prime.protocollo17.ecr17.model.Ecr17Request
import it.prime.protocollo17.ecr17.profile.Ecr17Config
import kotlinx.coroutines.flow.StateFlow
import java.io.File

// STUB di WP0 (§8.2): firme definitive di §5.3.3, corpi a carico di WP5.

/**
 * Journal in memoria (fold per tentativo) sopra il file JSONL. Un errore di scrittura dopo il primo invio forza
 * l'entry a UNKNOWN in memoria prima di rilanciare: il blocco dei pagamenti scatta subito, senza riavvio (§5.3.3).
 */
class JournalRepositoryImpl(
    private val store: JournalStore,
    private val settingsProvider: () -> JournalSettings,
    private val wallClock: () -> Long = System::currentTimeMillis,
) : JournalRepository {

    override val entries: StateFlow<List<JournalEntry>>
        get() = TODO("WP5: JournalRepositoryImpl.kt")

    override val unresolved: StateFlow<List<JournalEntry>>
        get() = TODO("WP5: JournalRepositoryImpl.kt")

    override suspend fun recoverOnStartup(): List<JournalEntry> = TODO("WP5: JournalRepositoryImpl.kt")

    override fun newAttemptId(): String = TODO("WP5: JournalRepositoryImpl.kt")

    override suspend fun begin(attemptId: String, request: Ecr17Request, config: Ecr17Config): Unit =
        TODO("WP5: JournalRepositoryImpl.kt")

    override suspend fun recordPhase(attemptId: String, phase: JournalPhase, sends: Int, rawResult: String?): Unit =
        TODO("WP5: JournalRepositoryImpl.kt")

    override suspend fun attachEvidence(attemptId: String, rawResult: String, note: String): Unit =
        TODO("WP5: JournalRepositoryImpl.kt")

    override suspend fun finish(attemptId: String, outcome: CommandOutcome?): Unit = TODO("WP5: JournalRepositoryImpl.kt")

    override suspend fun resolve(attemptId: String, kind: ResolutionKind, note: String, gOutcome: CommandOutcome.Answered?): Unit =
        TODO("WP5: JournalRepositoryImpl.kt")

    override fun knownResultsBefore(attemptId: String): List<KnownResult> = TODO("WP5: JournalRepositoryImpl.kt")

    override fun journalFile(): File = TODO("WP5: JournalRepositoryImpl.kt")

    override fun close(): Unit = TODO("WP5: JournalRepositoryImpl.kt")
}
