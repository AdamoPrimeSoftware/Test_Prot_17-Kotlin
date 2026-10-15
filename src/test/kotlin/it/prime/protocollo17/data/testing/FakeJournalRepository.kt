package it.prime.protocollo17.data.testing

import it.prime.protocollo17.data.mapper.JournalMapper
import it.prime.protocollo17.domain.model.journal.JournalEntry
import it.prime.protocollo17.domain.model.journal.JournalPhase
import it.prime.protocollo17.domain.model.journal.JournalRecord
import it.prime.protocollo17.domain.model.journal.KnownResult
import it.prime.protocollo17.domain.model.journal.ResolutionKind
import it.prime.protocollo17.domain.repository.JournalRepository
import it.prime.protocollo17.ecr17.model.CommandCode
import it.prime.protocollo17.ecr17.model.CommandOutcome
import it.prime.protocollo17.ecr17.model.Ecr17Request
import it.prime.protocollo17.ecr17.profile.Ecr17Config
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * [WP5] Journal finto per i test di repository e servizio: registra ogni chiamata in ordine ([calls]) e applica la
 * semantica essenziale del journal reale (fold con JournalMapper, finish(null) ⇒ UNKNOWN dopo un invio e NOT_SENT
 * dopo CREATED, append fallito ⇒ entry forzata a UNKNOWN). Errori e gate si impostano dal test.
 */
class FakeJournalRepository : JournalRepository {

    /** Chiamate in ordine, es. "begin:att-1", "phase:att-1:SENDING:1", "finish:att-1:Answered", "finish:att-1:null". */
    val calls = CopyOnWriteArrayList<String>()
    val finishCalls = CopyOnWriteArrayList<Pair<String, CommandOutcome?>>()
    val evidenceCalls = CopyOnWriteArrayList<Triple<String, String, String>>()

    @Volatile var failBegin: Exception? = null
    @Volatile var failFinish: Exception? = null

    /**
     * Se impostato, begin attende il gate prima di scrivere: simula un fsync lento. L'attesa è in NonCancellable come
     * nel journal reale (§5.3.3): prima era cancellabile, begin lanciava CancellationException e i test
     * sull'annullamento durante begin passavano anche senza il controllo di cancellazione dopo begin.
     */
    @Volatile var beginGate: CompletableDeferred<Unit>? = null
    val beginEntered = CompletableDeferred<Unit>()

    /** Contato alla prima chiamata di finish: un test può attenderlo bloccando il thread, senza sospendere. */
    val finishEntered = CountDownLatch(1)

    private val ids = AtomicInteger(0)
    private val lock = Any()
    private val records = LinkedHashMap<String, MutableList<JournalRecord>>()
    private val forcedUnknown = HashSet<String>()
    private var clock = 1_000L

    override val entries = MutableStateFlow<List<JournalEntry>>(emptyList())
    override val unresolved = MutableStateFlow<List<JournalEntry>>(emptyList())

    /** Tentativo non risolto preesistente (UNKNOWN dopo un invio) creato all'istante [createdAt]. */
    fun seedUnknown(attemptId: String, command: CommandCode, createdAt: Long = 1L) {
        synchronized(lock) {
            records[attemptId] = mutableListOf(
                JournalRecord(attemptId, 0, createdAt, JournalPhase.CREATED, command = command.wire.toString()),
                JournalRecord(attemptId, 1, createdAt + 1, JournalPhase.SENDING, sends = 1),
                JournalRecord(attemptId, 2, createdAt + 2, JournalPhase.UNKNOWN),
            )
            publishLocked()
        }
    }

    fun entry(attemptId: String): JournalEntry? = entries.value.firstOrNull { it.attemptId == attemptId }

    override suspend fun recoverOnStartup(): List<JournalEntry> = emptyList()

    override fun newAttemptId(): String = "att-${ids.incrementAndGet()}"

    override suspend fun begin(attemptId: String, request: Ecr17Request, config: Ecr17Config) {
        calls += "begin:$attemptId"
        beginEntered.complete(Unit)
        beginGate?.let { gate -> withContext(NonCancellable) { gate.await() } }
        failBegin?.let { throw it }
        add(attemptId, JournalPhase.CREATED) { it.copy(command = request.command.wire.toString(), amountCents = null) }
    }

    override suspend fun recordPhase(attemptId: String, phase: JournalPhase, sends: Int, rawResult: String?) {
        calls += "phase:$attemptId:$phase:$sends" + (rawResult?.let { ":$it" } ?: "")
        add(attemptId, phase) { it.copy(sends = sends, rawResult = rawResult) }
    }

    override suspend fun attachEvidence(attemptId: String, rawResult: String, note: String) {
        calls += "evidence:$attemptId:$rawResult"
        evidenceCalls += Triple(attemptId, rawResult, note)
        add(attemptId, JournalPhase.EVIDENCE) { it.copy(rawResult = rawResult, evidenceNote = note) }
    }

    override suspend fun finish(attemptId: String, outcome: CommandOutcome?) {
        calls += "finish:$attemptId:" + (outcome?.let { it::class.simpleName } ?: "null")
        finishCalls += attemptId to outcome
        finishEntered.countDown()
        synchronized(lock) {
            val list = records[attemptId] ?: return
            failFinish?.let {
                forcedUnknown += attemptId
                publishLocked()
                throw it
            }
            val record = if (outcome != null) {
                JournalMapper.terminalRecord(attemptId, list.size, clock++, outcome)
            } else {
                val last = JournalMapper.lastDiskPhase(list)
                val phase = when (last) {
                    in JournalMapper.IN_FLIGHT_AFTER_SEND -> JournalPhase.UNKNOWN
                    JournalPhase.CREATED -> JournalPhase.NOT_SENT
                    else -> return
                }
                JournalMapper.interruptedRecord(attemptId, list.size, clock++, phase, "ECR17: cancelled", last)
            }
            list += record
            forcedUnknown -= attemptId
            publishLocked()
        }
    }

    override suspend fun resolve(attemptId: String, kind: ResolutionKind, note: String, gOutcome: CommandOutcome.Answered?) {
        calls += "resolve:$attemptId:$kind"
        synchronized(lock) {
            val list = records[attemptId] ?: throw IllegalStateException("inesistente")
            list += JournalMapper.resolvedRecord(attemptId, list.size, clock++, kind, note, gOutcome)
            forcedUnknown -= attemptId
            publishLocked()
        }
    }

    override fun knownResultsBefore(attemptId: String): List<KnownResult> = emptyList()

    override fun journalFile(): File = File("fake-journal.jsonl")

    override fun close() {}

    private fun add(attemptId: String, phase: JournalPhase, fill: (JournalRecord) -> JournalRecord) {
        synchronized(lock) {
            val list = records.getOrPut(attemptId) { mutableListOf() }
            list += fill(JournalRecord(attemptId, list.size, clock++, phase))
            publishLocked()
        }
    }

    private fun publishLocked() {
        val all = records.map { (id, list) -> JournalMapper.fold(id, list, persisted = id !in forcedUnknown) }
        entries.value = all.sortedByDescending { it.createdAt }
        unresolved.value = entries.value.filter { it.isUnresolved }
    }
}
