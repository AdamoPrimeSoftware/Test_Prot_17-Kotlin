package it.prime.protocollo17.domain.repository

import it.prime.protocollo17.domain.model.journal.JournalEntry
import it.prime.protocollo17.domain.model.journal.JournalPhase
import it.prime.protocollo17.domain.model.journal.KnownResult
import it.prime.protocollo17.domain.model.journal.ResolutionKind
import it.prime.protocollo17.ecr17.model.CommandOutcome
import it.prime.protocollo17.ecr17.model.Ecr17Request
import it.prime.protocollo17.ecr17.profile.Ecr17Config
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Journal persistente dei tentativi dei comandi journaled (§5.3, U7).
 *
 * Le scritture che precedono un invio (begin, recordPhase SENDING) ritornano solo a dato durevole (fsync) e
 * lanciano se il disco non è scrivibile: è ciò che impedisce di inviare un pagamento senza traccia (M3).
 * I tentativi non risolti bloccano i pagamenti finché l'operatore non li chiude (M6).
 */
interface JournalRepository {
    /** Più recenti prima: ultimi JournalSettings.memoryAttempts + tutti i non risolti. */
    val entries: StateFlow<List<JournalEntry>>

    /** isUnresolved: fase UNKNOWN (anche forzata in memoria) oppure RESULT con needsReview. Blocchi e avvisi. */
    val unresolved: StateFlow<List<JournalEntry>>

    /**
     * Avvio (prima della UI): carica, compatta se oltre la soglia, chiude come UNKNOWN i tentativi rimasti in
     * SENDING/ACKED/GO_AHEAD/RECEIVED (failure "app terminata durante lo scambio"; con RECEIVED il payload resta
     * come prova), come NOT_SENT quelli in CREATED, e come UNKNOWN quelli senza CREATED non chiusi.
     */
    suspend fun recoverOnStartup(): List<JournalEntry>

    /** Nuovo attemptId (UUID), generato PRIMA di begin così che il chiamante possa sempre chiudere il tentativo. */
    fun newAttemptId(): String

    /** Record CREATED con fsync (scrittura in NonCancellable). Lancia IOException se il file non è scrivibile. */
    suspend fun begin(attemptId: String, request: Ecr17Request, config: Ecr17Config)

    /** SENDING / ACKED / GO_AHEAD / RECEIVED (con rawResult) con fsync. Lancia IOException se non scrivibile (M3). */
    suspend fun recordPhase(attemptId: String, phase: JournalPhase, sends: Int, rawResult: String? = null)

    /** Record EVIDENCE (payload mascherato di un esito orfano) su un tentativo esistente. */
    suspend fun attachEvidence(attemptId: String, rawResult: String, note: String)

    /**
     * Record terminale (RESULT, NOT_SENT, REFUSED, UNKNOWN) da un outcome. outcome == null (coroutine annullata):
     * UNKNOWN se l'ultimo record è SENDING/ACKED/GO_AHEAD/RECEIVED, NOT_SENT se è CREATED, nulla se il tentativo
     * non esiste (begin mai completato). Lancia IOException se non scrivibile (dopo aver forzato UNKNOWN in memoria).
     */
    suspend fun finish(attemptId: String, outcome: CommandOutcome?)

    /** Chiude un tentativo isUnresolved. note non vuota obbligatoria. IllegalStateException se non è da risolvere. */
    suspend fun resolve(attemptId: String, kind: ResolutionKind, note: String, gOutcome: CommandOutcome.Answered?)

    /**
     * Esiti noti di tentativi creati PRIMA di attemptId (escluso il tentativo stesso), più recenti prima:
     * rawResult dei RESULT e RECEIVED, gRawResult/gStan/gOnlineId/gHostDateTime dei RESOLVED, EVIDENCE.
     */
    fun knownResultsBefore(attemptId: String): List<KnownResult>

    fun journalFile(): File
    fun close()
}
