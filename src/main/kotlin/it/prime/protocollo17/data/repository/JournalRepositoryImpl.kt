package it.prime.protocollo17.data.repository

import it.prime.protocollo17.data.local.journal.JournalStore
import it.prime.protocollo17.data.mapper.JournalMapper
import it.prime.protocollo17.domain.model.journal.JournalEntry
import it.prime.protocollo17.domain.model.journal.JournalPhase
import it.prime.protocollo17.domain.model.journal.JournalRecord
import it.prime.protocollo17.domain.model.journal.KnownResult
import it.prime.protocollo17.domain.model.journal.ResolutionKind
import it.prime.protocollo17.domain.model.settings.JournalSettings
import it.prime.protocollo17.domain.repository.JournalRepository
import it.prime.protocollo17.ecr17.model.CommandOutcome
import it.prime.protocollo17.ecr17.model.Ecr17Request
import it.prime.protocollo17.ecr17.profile.Ecr17Config
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.util.UUID

/**
 * Journal in memoria (fold per tentativo) sopra il file JSONL. Un errore di scrittura dopo il primo invio forza
 * l'entry a UNKNOWN in memoria prima di rilanciare: il blocco dei pagamenti scatta subito, senza riavvio (§5.3.3).
 *
 * Lo stato in memoria rispecchia SOLO ciò che è su disco: un record il cui append è fallito non entra nella
 * lista dei record, e l'entry è marcata `persisted = false` con fase UNKNOWN. Un append successivo riuscito
 * sullo stesso tentativo ripristina il fold normale, tranne una prova EVIDENCE (non porta una fase): il blocco
 * resta solo finché il disco non contiene una chiusura del tentativo.
 *
 * Tutte le scritture girano in NonCancellable sotto un unico [mutex]: un record scritto è sempre anche
 * pubblicato in [entries]/[unresolved], anche se il chiamante viene annullato a metà.
 */
class JournalRepositoryImpl(
    private val store: JournalStore,
    private val settingsProvider: () -> JournalSettings,
    private val wallClock: () -> Long = System::currentTimeMillis,
) : JournalRepository {

    private val log = LoggerFactory.getLogger(JournalRepositoryImpl::class.java)

    private val mutex = Mutex()

    /** Stato di un tentativo: record su disco (in ordine di file), prossimo seq, esito dell'ultimo append. */
    private class Attempt(val records: MutableList<JournalRecord>, var nextSeq: Int, var persisted: Boolean = true)

    /** In ordine di creazione (= ordine di prima comparsa nel file): serve a knownResultsBefore. */
    private val attempts = LinkedHashMap<String, Attempt>()

    /** Ultimo fold di ogni tentativo; riletto da knownResultsBefore senza sospendere. */
    @Volatile
    private var folded: Map<String, JournalEntry> = emptyMap()

    @Volatile
    private var order: List<String> = emptyList()

    private val _entries = MutableStateFlow<List<JournalEntry>>(emptyList())
    override val entries: StateFlow<List<JournalEntry>> = _entries.asStateFlow()

    private val _unresolved = MutableStateFlow<List<JournalEntry>>(emptyList())
    override val unresolved: StateFlow<List<JournalEntry>> = _unresolved.asStateFlow()

    // ─── Avvio ─────────────────────────────────────────────────────────────────

    /**
     * Restituisce i tentativi CHIUSI da questo recupero (UNKNOWN o NOT_SENT), più recenti prima: sono quelli
     * rimasti a metà all'ultima chiusura dell'app, per l'avviso all'avvio (§6.18). Non lancia mai: un errore
     * di lettura lascia un'entry sintetica bloccante, uno di scrittura lascia l'entry forzata a UNKNOWN.
     */
    override suspend fun recoverOnStartup(): List<JournalEntry> = withContext(NonCancellable) {
        mutex.withLock {
            attempts.clear()
            val records = try {
                store.readAll()
            } catch (e: Exception) {
                log.error("Journal illeggibile ({}): comandi journaled bloccati finché non lo risolvi", e.message, e)
                // Nel dubbio si blocca (M6): senza il file non si può sapere se un pagamento è rimasto a metà.
                val id = "journal-illeggibile-${wallClock()}"
                val record = JournalRecord(
                    attemptId = id, seq = 0, at = wallClock(), phase = JournalPhase.UNKNOWN,
                    failure = "journal illeggibile all'avvio: ${e.message}",
                )
                attempts[id] = Attempt(mutableListOf(record), nextSeq = 1, persisted = false)
                publishLocked()
                return@withLock listOf(foldLocked(id))
            }
            for (record in records) {
                val attempt = attempts.getOrPut(record.attemptId) { Attempt(mutableListOf(), 0) }
                attempt.records += record
                attempt.nextSeq = maxOf(attempt.nextSeq, record.seq + 1)
            }
            compactIfNeededLocked()

            val closedNow = mutableListOf<String>()
            for ((id, attempt) in attempts.entries.toList()) {
                val created = attempt.records.any { it.phase == JournalPhase.CREATED }
                val last = JournalMapper.lastDiskPhase(attempt.records)
                val closing = when {
                    last in JournalMapper.IN_FLIGHT_AFTER_SEND -> JournalPhase.UNKNOWN to "app terminata durante lo scambio"
                    last == JournalPhase.CREATED -> JournalPhase.NOT_SENT to "app terminata prima dell'invio"
                    !created && last !in JournalMapper.CLOSING -> JournalPhase.UNKNOWN to "tentativo con dati incompleti non chiuso"
                    else -> null
                } ?: continue
                val record = JournalMapper.interruptedRecord(
                    attemptId = id, seq = attempt.nextSeq, at = wallClock(), phase = closing.first,
                    failure = closing.second, lastPhase = last,
                )
                appendLocked(id, record, forceUnknownOnFailure = true, rethrow = false)
                closedNow += id
            }
            publishLocked()
            val result = closedNow.map { foldLocked(it) }.sortedByDescending { it.createdAt }
            if (result.isNotEmpty()) log.warn("Journal: {} tentativi rimasti a metà chiusi al riavvio", result.size)
            result
        }
    }

    /** Nuovo attemptId (UUID), generato PRIMA di begin così che il chiamante possa sempre chiudere il tentativo. */
    override fun newAttemptId(): String = UUID.randomUUID().toString()

    // ─── Scritture del ciclo di vita ───────────────────────────────────────────

    override suspend fun begin(attemptId: String, request: Ecr17Request, config: Ecr17Config) = write {
        val attempt = attempts[attemptId]
        val seq = attempt?.nextSeq ?: 0
        val record = JournalMapper.createdRecord(attemptId = attemptId, seq = seq, at = wallClock(), request = request, config = config)
        // Un begin fallito non crea entry (M3): l'eccezione arriva al repository, che non invia nulla.
        store.append(record)
        if (attempt == null) {
            attempts[attemptId] = Attempt(mutableListOf(record), nextSeq = seq + 1)
        } else {
            attempt.records += record
            attempt.nextSeq = seq + 1
            attempt.persisted = true
        }
        publishLocked()
    }

    override suspend fun recordPhase(attemptId: String, phase: JournalPhase, sends: Int, rawResult: String?) {
        require(phase in JournalMapper.IN_FLIGHT_AFTER_SEND) { "recordPhase accetta solo SENDING/ACKED/GO_AHEAD/RECEIVED, non $phase" }
        write {
            val seq = attempts[attemptId]?.nextSeq ?: 0
            val record = JournalRecord(attemptId = attemptId, seq = seq, at = wallClock(), phase = phase, sends = sends, rawResult = rawResult)
            appendLocked(attemptId, record, forceUnknownOnFailure = true, rethrow = true)
            publishLocked()
        }
    }

    override suspend fun attachEvidence(attemptId: String, rawResult: String, note: String) = write {
        val attempt = attempts[attemptId]
        if (attempt == null) {
            log.warn("Journal: prova per un tentativo inesistente {} ignorata", attemptId)
            return@write
        }
        val record = JournalRecord(
            attemptId = attemptId, seq = attempt.nextSeq, at = wallClock(), phase = JournalPhase.EVIDENCE,
            rawResult = rawResult, evidenceNote = note,
        )
        appendLocked(attemptId, record, forceUnknownOnFailure = true, rethrow = true)
        publishLocked()
    }

    override suspend fun finish(attemptId: String, outcome: CommandOutcome?) = write {
        // Tentativo inesistente: begin mai completato, non c'è nulla da chiudere (e nulla è stato inviato).
        val attempt = attempts[attemptId] ?: return@write
        val record = if (outcome != null) {
            JournalMapper.terminalRecord(attemptId = attemptId, seq = attempt.nextSeq, at = wallClock(), outcome = outcome)
        } else {
            // Coroutine annullata: la fase dell'ultimo record SCRITTO decide (non quella forzata in memoria).
            val last = JournalMapper.lastDiskPhase(attempt.records)
            val phase = when (last) {
                in JournalMapper.IN_FLIGHT_AFTER_SEND -> JournalPhase.UNKNOWN
                JournalPhase.CREATED -> JournalPhase.NOT_SENT
                else -> null
            } ?: return@write
            JournalMapper.interruptedRecord(
                attemptId = attemptId, seq = attempt.nextSeq, at = wallClock(), phase = phase, failure = CANCELLED, lastPhase = last,
            )
        }
        try {
            appendLocked(attemptId, record, forceUnknownOnFailure = true, rethrow = true)
        } finally {
            publishLocked()
        }
    }

    override suspend fun resolve(attemptId: String, kind: ResolutionKind, note: String, gOutcome: CommandOutcome.Answered?) {
        require(note.isNotBlank()) { "La nota di risoluzione è obbligatoria" }
        write {
            val attempt = attempts[attemptId]
            val entry = attempt?.let { foldLocked(attemptId) }
            check(entry != null && entry.isUnresolved) { "Il tentativo $attemptId non è da risolvere" }
            val record = JournalMapper.resolvedRecord(
                attemptId = attemptId, seq = attempt.nextSeq, at = wallClock(), kind = kind, note = note.trim(), gOutcome = gOutcome,
            )
            // Se fallisce l'entry resta com'è, cioè bloccante: non si forza nulla, si rilancia.
            appendLocked(attemptId, record, forceUnknownOnFailure = false, rethrow = true)
            publishLocked()
        }
    }

    // ─── Letture ───────────────────────────────────────────────────────────────

    override fun knownResultsBefore(attemptId: String): List<KnownResult> {
        val ids = order
        val index = ids.indexOf(attemptId)
        val earlier = if (index < 0) ids else ids.subList(0, index)
        val snapshot = folded
        return earlier.mapNotNull { snapshot[it] }
            .flatMap(JournalMapper::knownResults)
            .sortedByDescending { it.at }
    }

    override fun journalFile(): File = store.file

    override fun close() {
        store.close()
    }

    // ─── Implementazione ───────────────────────────────────────────────────────

    /** Ogni scrittura: NonCancellable (record scritto ⇒ record pubblicato) sotto l'unico mutex. */
    private suspend fun <T> write(block: suspend () -> T): T = withContext(NonCancellable) { mutex.withLock { block() } }

    /**
     * Append con aggiornamento dello stato. Fallimento: con [forceUnknownOnFailure] l'entry diventa UNKNOWN non
     * persistita (pubblicata prima di rilanciare, §5.3.3); il seq avanza comunque, così un record parziale e
     * il suo ritentativo non condividono mai lo stesso seq.
     */
    private suspend fun appendLocked(attemptId: String, record: JournalRecord, forceUnknownOnFailure: Boolean, rethrow: Boolean) {
        val attempt = attempts.getOrPut(attemptId) { Attempt(mutableListOf(), record.seq) }
        attempt.nextSeq = maxOf(attempt.nextSeq, record.seq + 1)
        try {
            store.append(record)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("Journal: scrittura di {} per {} fallita: {}", record.phase, attemptId, e.message, e)
            if (forceUnknownOnFailure) {
                attempt.persisted = false
                if (attempt.records.isEmpty()) {
                    // Nessun record su disco (es. recordPhase senza begin): l'entry esiste solo in memoria, bloccante.
                    attempt.records += record.copy(phase = JournalPhase.UNKNOWN)
                }
                publishLocked()
            }
            if (rethrow) throw e
            return
        }
        attempt.records += record
        // Solo un record che porta una fase ripristina il fold. Prima anche una prova EVIDENCE riuscita rimetteva
        // persisted = true: un'entry forzata a UNKNOWN (finish non scritto) tornava alla fase in volo dell'ultimo
        // record su disco, usciva da `unresolved` e sbloccava i pagamenti senza alcuna chiusura sul disco (M6).
        // EVIDENCE non cambia mai la fase (§5.3.1), quindi non può nemmeno sbloccare.
        if (record.phase != JournalPhase.EVIDENCE) attempt.persisted = true
    }

    /**
     * Compattazione all'avvio oltre `compactAboveKb`: si tengono i tentativi non chiusi, UNKNOWN o da verificare
     * e quelli chiusi negli ultimi `keepClosedDays`. Mai eliminare un tentativo non risolto. Un errore lascia il
     * file com'è (si riproverà al prossimo avvio).
     */
    private suspend fun compactIfNeededLocked() {
        val settings = settingsProvider()
        val threshold = settings.compactAboveKb.toLong() * 1024
        if (store.sizeBytes() <= threshold) return
        val cutoff = wallClock() - settings.keepClosedDays.toLong() * 24 * 60 * 60 * 1000
        val keep = attempts.filter { (id, _) ->
            val entry = foldLocked(id)
            entry.isInFlight || entry.isUnresolved || entry.phase !in JournalMapper.CLOSING || entry.lastUpdate >= cutoff
        }
        if (keep.size == attempts.size) return
        try {
            store.rewrite(keep.values.flatMap { it.records })
            val removed = attempts.keys - keep.keys
            removed.forEach { attempts.remove(it) }
            log.info("Journal compattato: {} tentativi chiusi rimossi, {} conservati", removed.size, attempts.size)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("Journal: compattazione non riuscita ({}), il file resta com'è", e.message, e)
        }
    }

    private fun foldLocked(attemptId: String): JournalEntry {
        val attempt = attempts.getValue(attemptId)
        return JournalMapper.fold(attemptId, attempt.records, attempt.persisted)
    }

    /** Pubblica entries (ultimi memoryAttempts + tutti i non risolti, più recenti prima) e unresolved. */
    private fun publishLocked() {
        val all = LinkedHashMap<String, JournalEntry>()
        for (id in attempts.keys) {
            if (attempts.getValue(id).records.isNotEmpty()) all[id] = foldLocked(id)
        }
        folded = all
        order = all.keys.toList()
        // Più recenti prima: per data di creazione, a parità l'ultimo creato prima (ordine di file invertito).
        val newestFirst = all.values.reversed().sortedByDescending { it.createdAt }
        val cap = settingsProvider().memoryAttempts.coerceAtLeast(0)
        val recent = newestFirst.take(cap)
        val recentIds = recent.mapTo(HashSet()) { it.attemptId }
        val unresolved = newestFirst.filter { it.isUnresolved }
        _entries.value = recent + unresolved.filter { it.attemptId !in recentIds }
        _unresolved.value = unresolved
    }

    private companion object {
        /** failure dei tentativi chiusi per una cancellazione della coroutine (outcome null). */
        const val CANCELLED = "ECR17: cancelled"
    }
}
