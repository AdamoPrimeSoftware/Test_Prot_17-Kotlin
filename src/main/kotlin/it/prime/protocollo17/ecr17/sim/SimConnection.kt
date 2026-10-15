package it.prime.protocollo17.ecr17.sim

import it.prime.protocollo17.ecr17.profile.ControlLrcRxPolicy
import it.prime.protocollo17.ecr17.wire.ControlKind
import it.prime.protocollo17.ecr17.wire.FrameSplitter
import it.prime.protocollo17.ecr17.wire.Hex
import it.prime.protocollo17.ecr17.wire.LrcMode
import it.prime.protocollo17.ecr17.wire.SplitItem
import it.prime.protocollo17.ecr17.wire.WireFrame
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.Socket
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Una connessione dell'ECR al simulatore, servita dall'inizio alla chiusura (§4.3).
 *
 * Due coroutine: un lettore bloccante (`read` del socket su Dispatchers.IO) che accoda i byte in un buffer sotto
 * lock e sveglia l'elaboratore con un segnale CONFLATED, e l'elaboratore, che possiede il [FrameSplitter] ed esegue
 * il piano di [SimResponder] con i suoi tempi. Come nella sessione dell'ECR (A§12.4) i byte non passano mai da un
 * Channel: con la cancellazione pronta di `withTimeoutOrNull` un chunk ricevuto proprio alla scadenza andrebbe perso,
 * e un test di integrazione fallirebbe una volta su mille. Perdere un segnale, invece, è innocuo.
 *
 * Durante un piano l'elaboratore continua a leggere: gli ACK/NAK dell'ECR sono registrati (e un NAK all'esito lo fa
 * reinviare), una ritrasmissione identica della richiesta in corso è contata ma non rielaborata (il doppio invio deve
 * restare visibile nei contatori, §4.5) e una richiesta diversa viene messa in coda e servita dopo.
 */
internal class SimConnection(
    private val socket: Socket,
    private val core: SimCore,
) {
    private val output: OutputStream = socket.getOutputStream()
    private val clock = TimeSource.Monotonic

    // ─── Ricezione (lettore → elaboratore) ───
    private val rxLock = Any()
    private val rxBuffer = ByteArrayOutputStream()
    private var rxEof = false                    // sotto rxLock
    private val rxSignal = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var closedLocally = false

    @Volatile
    private var closedByPeer = false

    // ─── Stato dell'elaboratore (una sola coroutine) ───
    private var splitterMode: LrcMode = core.currentConfig().appLrcMode
    private var splitter = FrameSplitter(splitterMode, ControlLrcRxPolicy.TOLERANT)
    private val queued = ArrayDeque<WireFrame.Application>()
    private var currentRequest: ByteArray? = null

    /** Risultato di [receive]: un frame dell'ECR (applicativo o di controllo), una scadenza o la fine del flusso. */
    private sealed interface Inbound {
        class Frame(val frame: WireFrame) : Inbound
        data object Timeout : Inbound
        data object Closed : Inbound
    }

    /** Serve la connessione fino alla chiusura (dell'ECR, di un guasto o di stop()). true se l'ha chiusa l'ECR. */
    suspend fun serve(): Boolean {
        coroutineScope {
            val reader = launch(Dispatchers.IO + CoroutineName("p17-sim-reader")) { readLoop() }
            try {
                processLoop()
            } finally {
                // Prima si chiude il socket (sblocca la read bloccante), poi si cancella il lettore: l'ordine inverso
                // lascerebbe coroutineScope ad aspettare una read che la cancellazione non interrompe.
                closeLocally()
                reader.cancel()
            }
        }
        return closedByPeer
    }

    // ─── Lettore ────────────────────────────────────────────────────────────────

    /** Bloccante, sul thread IO: accoda ogni chunk e segnala; EOF o errore chiudono il flusso. */
    private fun readLoop() {
        val buf = ByteArray(READ_BUFFER_SIZE)
        try {
            val input = socket.getInputStream()
            while (true) {
                val n = input.read(buf)
                if (n < 0) {
                    if (!closedLocally && !core.stopping) closedByPeer = true
                    break
                }
                if (n > 0) {
                    synchronized(rxLock) { rxBuffer.write(buf, 0, n) }
                    rxSignal.trySend(Unit)
                }
            }
        } catch (e: IOException) {
            // Reset dell'ECR o socket chiuso da noi (guasto, stop): si distingue col flag di chiusura locale.
            if (!closedLocally && !core.stopping) closedByPeer = true
        } finally {
            synchronized(rxLock) { rxEof = true }
            rxSignal.trySend(Unit)
        }
    }

    /** Byte arrivati e stato di fine flusso letti INSIEME: un EOF visto con il buffer vuoto è definitivo. */
    private fun takeRx(): Pair<ByteArray, Boolean> = synchronized(rxLock) {
        val bytes = rxBuffer.toByteArray()
        rxBuffer.reset()
        bytes to rxEof
    }

    // ─── Elaboratore ────────────────────────────────────────────────────────────

    private suspend fun processLoop() {
        for (step in core.connectSteps()) {
            if (!execute(step)) return
        }
        while (true) {
            val request = queued.removeFirstOrNull() ?: when (val inbound = receive(deadline = null)) {
                Inbound.Closed -> return
                Inbound.Timeout -> continue
                is Inbound.Frame -> inbound.frame as? WireFrame.Application ?: continue
            }
            if (!serveRequest(request)) return
        }
    }

    /** Esegue il piano di una richiesta. false = la connessione va chiusa (guasto, closeAfterTransaction, EOF). */
    private suspend fun serveRequest(request: WireFrame.Application): Boolean {
        val arrived = clock.markNow()
        val plan = core.plan(request)
        currentRequest = request.payload
        try {
            for (step in plan.steps) {
                if (!waitUntil(arrived + step.atMs.milliseconds)) return false
                if (!execute(step)) return false
            }
        } finally {
            currentRequest = null
        }
        if (plan.closeAfter) {
            core.note("closeAfterTransaction: chiusura della connessione a fine transazione")
            return false
        }
        return true
    }

    private suspend fun execute(step: SimStep): Boolean = when (step) {
        is SimStep.Close -> {
            core.note(step.reason)
            false
        }
        is SimStep.Send -> {
            if (!write(step.frames)) {
                false
            } else {
                val wait = step.ackWait
                if (wait == null) true else awaitEcrReply(step, wait)
            }
        }
    }

    /** Attende fino a [deadline] continuando a leggere; false se l'ECR ha chiuso. */
    private suspend fun waitUntil(deadline: ComparableTimeMark): Boolean {
        while (true) {
            when (val inbound = receive(deadline)) {
                Inbound.Timeout -> return true
                Inbound.Closed -> return false
                is Inbound.Frame -> (inbound.frame as? WireFrame.Application)?.let(::requestDuringTransaction)
            }
        }
    }

    /**
     * Dopo un frame applicativo: ACK ⇒ fine attesa (con `validAck`, solo l'ACK esatto di ackRule); NAK ⇒ reinvio;
     * scadenza senza ACK valido ⇒ reinvio se il piano lo prevede (retransmitResultIfNoValidAck), altrimenti fine.
     * Una richiesta nuova chiude l'attesa: l'ECR è già oltre, la si serve subito dopo.
     */
    private suspend fun awaitEcrReply(step: SimStep.Send, wait: AckWait): Boolean {
        val resend = step.resend ?: step.frames.last()
        var deadline = clock.markNow() + wait.waitMs.milliseconds
        var nakResends = 0
        var noAckResends = 0
        while (true) {
            when (val inbound = receive(deadline)) {
                Inbound.Closed -> return false
                Inbound.Timeout -> {
                    if (wait.validAck == null || noAckResends >= wait.maxResendsWithoutValidAck) return true
                    noAckResends++
                    val again = OutFrame(resend.bytes, "${resend.description}: reinvio $noAckResends (nessun ACK valido entro ${wait.waitMs} ms)", resend.kind)
                    if (!write(listOf(again))) return false
                    deadline = clock.markNow() + wait.waitMs.milliseconds
                }
                is Inbound.Frame -> when (val frame = inbound.frame) {
                    is WireFrame.Control -> if (frame.kind == ControlKind.ACK) {
                        val expected = wait.validAck
                        if (expected == null || frame.raw.contentEquals(expected)) return true
                        core.note("ACK dell'ECR ${Hex.spaced(frame.raw)} diverso da ${Hex.spaced(expected)} (regola del simulatore): non vale")
                    } else {
                        if (nakResends >= SimResponder.MAX_NAK_RESENDS) {
                            core.note("NAK ripetuti dall'ECR: frame abbandonato dopo $nakResends reinvii")
                            return true
                        }
                        nakResends++
                        val again = OutFrame(resend.bytes, "${resend.description}: reinvio dopo NAK", resend.kind)
                        if (!write(listOf(again))) return false
                        deadline = clock.markNow() + wait.waitMs.milliseconds
                    }
                    is WireFrame.Application -> if (!requestDuringTransaction(frame)) return true
                    is WireFrame.Progress -> Unit
                }
            }
        }
    }

    /**
     * Richiesta arrivata mentre un piano è in corso. La ritrasmissione identica della richiesta corrente è già
     * contata (rischio di doppio addebito visibile) ma non rielaborata: true. Un'altra richiesta va in coda: false.
     */
    private fun requestDuringTransaction(frame: WireFrame.Application): Boolean {
        val current = currentRequest
        if (current != null && current.contentEquals(frame.payload)) {
            core.note("Ritrasmissione della richiesta '${frame.replyCode ?: '?'}' in elaborazione: contata, non rielaborata")
            return true
        }
        queued.addLast(frame)
        core.note("Richiesta '${frame.replyCode ?: '?'}' arrivata durante un'elaborazione: servita subito dopo")
        return false
    }

    // ─── Primitive ──────────────────────────────────────────────────────────────

    /**
     * Prossimo frame dell'ECR entro [deadline] (null = senza limite). Ordine: splitter, poi byte arrivati, poi fine
     * flusso (solo dopo aver consumato tutto), poi scadenza, poi attesa del segnale. Scarti e progress dell'ECR
     * finiscono nel log come note e non sono restituiti. Ogni frame è registrato nei contatori da [SimCore].
     */
    private suspend fun receive(deadline: ComparableTimeMark?): Inbound {
        while (true) {
            val item = splitter.next()
            if (item != null) {
                inbound(item)?.let { return it }
                continue
            }
            val (chunk, eof) = takeRx()
            if (chunk.isNotEmpty()) {
                feed(chunk)
                continue
            }
            if (eof) {
                splitter.flushBareControl()?.let { item -> inbound(item)?.let { return it } }
                val rest = splitter.clear()
                if (rest.isNotEmpty()) core.noteDiscarded(rest, "byte incompleti alla chiusura")
                return Inbound.Closed
            }
            val remaining: Duration? = deadline?.let { it - clock.markNow() }
            if (remaining != null && remaining <= Duration.ZERO) {
                splitter.flushBareControl()?.let { item -> inbound(item)?.let { return it } }
                return Inbound.Timeout
            }
            if (remaining == null) rxSignal.receive() else withTimeoutOrNull(remaining) { rxSignal.receive() }
        }
    }

    /** Registra un elemento dello splitter; restituisce l'Inbound per i frame che interessano all'elaboratore. */
    private fun inbound(item: SplitItem): Inbound? = when (item) {
        is SplitItem.Discarded -> {
            core.noteDiscarded(item.bytes, item.reason.name)
            null
        }
        is SplitItem.Parsed -> when (val frame = item.frame) {
            is WireFrame.Application -> {
                core.recordApplication(frame)
                Inbound.Frame(frame)
            }
            is WireFrame.Control -> {
                core.recordControl(frame)
                Inbound.Frame(frame)
            }
            is WireFrame.Progress -> {
                core.note("Progress inatteso dall'ECR: ignorato")
                null
            }
        }
    }

    /** Un cambio di appLrcMode vale dalla prossima richiesta: lo splitter viene ricreato conservando i byte pendenti. */
    private fun feed(chunk: ByteArray) {
        val mode = core.currentConfig().appLrcMode
        if (mode != splitterMode) {
            val pending = splitter.clear()
            splitter = FrameSplitter(mode, ControlLrcRxPolicy.TOLERANT)
            splitterMode = mode
            splitter.feed(pending)
        }
        splitter.feed(chunk)
    }

    /**
     * Una sola write (+flush) per tutti i frame del passo. L'evento FrameOut (PAN mascherato) parte PRIMA della write,
     * così nel log precede la risposta dell'ECR. Un errore di scrittura chiude la connessione: l'ECR è andato.
     */
    private fun write(frames: List<OutFrame>): Boolean {
        val at = core.now()
        frames.forEach { core.emit(SimResponder.frameOut(at, it)) }
        val bytes = frames.fold(ByteArray(0)) { acc, f -> acc + f.bytes }
        return try {
            output.write(bytes)
            output.flush()
            true
        } catch (e: IOException) {
            core.note("Scrittura non riuscita (${e.message ?: e::class.java.simpleName}): connessione chiusa")
            false
        }
    }

    /** Chiusura ordinata (FIN) da parte del simulatore; idempotente. */
    private fun closeLocally() {
        closedLocally = true
        try {
            socket.close()
        } catch (e: IOException) {
            // già chiuso: niente da fare
        }
    }

    private companion object {
        const val READ_BUFFER_SIZE = 4096
    }
}
