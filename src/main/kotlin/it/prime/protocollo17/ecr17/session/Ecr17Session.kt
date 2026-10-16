package it.prime.protocollo17.ecr17.session

import it.prime.protocollo17.ecr17.error.Ecr17Failure
import it.prime.protocollo17.ecr17.event.FrameDirection
import it.prime.protocollo17.ecr17.event.FrameKind
import it.prime.protocollo17.ecr17.event.ProtocolEvent
import it.prime.protocollo17.ecr17.event.WarningCode
import it.prime.protocollo17.ecr17.message.ResponseParser
import it.prime.protocollo17.ecr17.model.AckMode
import it.prime.protocollo17.ecr17.model.CloseCause
import it.prime.protocollo17.ecr17.model.ExchangeKind
import it.prime.protocollo17.ecr17.model.ExchangePhase
import it.prime.protocollo17.ecr17.model.LinkState
import it.prime.protocollo17.ecr17.model.ReceiptLine
import it.prime.protocollo17.ecr17.profile.AppLrcRxPolicy
import it.prime.protocollo17.ecr17.profile.FinancialAckPolicy
import it.prime.protocollo17.ecr17.profile.ProtocolOptions
import it.prime.protocollo17.ecr17.transport.Ecr17Transport
import it.prime.protocollo17.ecr17.transport.TransportErrorKind
import it.prime.protocollo17.ecr17.transport.TransportException
import it.prime.protocollo17.ecr17.wire.AppLrcCheck
import it.prime.protocollo17.ecr17.wire.ControlChars
import it.prime.protocollo17.ecr17.wire.ControlKind
import it.prime.protocollo17.ecr17.wire.ControlLrcMatch
import it.prime.protocollo17.ecr17.wire.DiscardReason
import it.prime.protocollo17.ecr17.wire.FrameCodec
import it.prime.protocollo17.ecr17.wire.FrameSplitter
import it.prime.protocollo17.ecr17.wire.Hex
import it.prime.protocollo17.ecr17.wire.LrcMode
import it.prime.protocollo17.ecr17.wire.PanMasker
import it.prime.protocollo17.ecr17.wire.SplitItem
import it.prime.protocollo17.ecr17.wire.WireFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Uno scambio ECR17 completo su una connessione già aperta: invio del frame principale, handshake, attesa
 * dell'esito e drain (algoritmo vincolante di §3.21). Una istanza per comando, con un'istantanea delle opzioni.
 *
 * Perché è fatta così:
 * - **Gira sul dispatcher del chiamante** e misura il tempo con il [timeSource] iniettato (§2.10): i test in tempo
 *   virtuale percorrono 120 s di timeout in microsecondi. Tutte le attese sono `withTimeoutOrNull`, mai
 *   `withTimeout` (un timeout dell'ACK diventerebbe un annullamento).
 * - **Legge solo dall'RxBuffer** e attende un segnale conflated: nessun byte viaggia in un Channel, quindi una
 *   cancellazione pronta non può perdere proprio l'esito (A§12.4).
 * - **Money-safety** (M1-M10): un frame finanziario senza ACK non viene reinviato con IMPLICIT_GO_AHEAD (M2);
 *   solo un NAK con ETX e LRC di una convenzione fa ritrasmettere (lo splitter scarta gli altri come
 *   AMBIGUOUS_NAK); progress, riga S ed esito atteso sono un ACK implicito; un frame diventa esito solo se il suo
 *   codice è tra quelli attesi (M4); ACK finale, NAK e drain sono best effort (M5).
 *
 * Prima (nei repo) l'attesa dell'esito accettava qualsiasi frame applicativo, un ACK con LRC "sbagliato" faceva
 * ritrasmettere un pagamento e un errore nell'ACK finale trasformava un esito decodificato in un errore.
 */
internal class Ecr17Session(
    private val transport: Ecr17Transport,
    private val options: ProtocolOptions,
    private val emitter: EventEmitter,
    private val trace: TraceRecorder,
    private val cancel: CancelToken,
    private val timeSource: TimeSource.WithComparableMarks,
) {
    /**
     * Esegue lo scambio descritto da [spec]. Non lancia per i casi attesi (timeout, caduta, NAK, annullamento
     * dell'operatore): li restituisce come [ExchangeResult.Failure]. Lancia solo CancellationException
     * (cancellazione della coroutine) ed eccezioni inattese, che il client mappa con il flag del [trace] (M10).
     */
    suspend fun run(spec: ExchangeSpec): ExchangeResult = Exchange(spec).run()

    // ─── Tipi interni ────────────────────────────────────────────────────────

    private enum class SendReason { FIRST, NAK, ACK_TIMEOUT }

    /** Risultato di `next(deadline)`: un elemento dello splitter, la scadenza o la chiusura del collegamento. */
    private sealed interface Next {
        class Item(val item: SplitItem) : Next
        data object Timeout : Next
        class LinkClosed(val cause: CloseCause, val detail: String?) : Next
    }

    /** Come esce un tratto dell'algoritmo: si passa al punto 4, si chiude, si torna al punto 3 o si fallisce. */
    private sealed interface Step {
        data object ToResult : Step
        data object AckOnlyDone : Step
        data object ResentAfterLateNak : Step
        class Received(val frame: WireFrame.Application) : Step
        class Failed(val failure: ExchangeResult.Failure) : Step
    }

    /**
     * Stato di UN run: splitter, contatori e scadenze. Una istanza per run, così il replay dopo una caduta
     * (stesso TraceRecorder, §3.23 punto 8) riparte con contatori locali puliti.
     */
    private inner class Exchange(private val spec: ExchangeSpec) {
        private val splitter = FrameSplitter(options.appLrcMode, options.controlLrcRx)
        private val frame = FrameCodec(options.appLrcMode, options.controlLrcTx).encodeApplication(spec.payload)

        /** Reinvii per NAK (limite nakRetryCount) e per ACK mancante (limite ackTimeoutRetransmitCount). */
        private var nakResends = 0
        private var timeoutResends = 0

        /** true se dall'ultimo invio è arrivato un ACK, un progress, una riga S o un esito atteso. */
        private var activitySinceSend = false

        /** true dopo un ACK (esplicito o nudo) di questo invio: distingue l'ACK tardivo da quello spurio. */
        private var ackSeen = false

        /** Esito atteso arrivato prima dell'ACK (punto 3b): consumato per primo al punto 4. */
        private var pending: WireFrame.Application? = null

        private var receiptEndSeen = false
        private var overflowReported = false
        private var ackDeadline: ComparableTimeMark = timeSource.markNow()

        suspend fun run(): ExchangeResult {
            // Il trace è condiviso col replay: l'ackMode del run fallito non descrive questo run.
            trace.ackMode = null
            try {
                collectOrphans()
                transmitMain(SendReason.FIRST)?.let { return it }
                while (true) {
                    when (val handshake = awaitAck()) {
                        is Step.Failed -> return handshake.failure
                        Step.AckOnlyDone -> return complete(null)
                        else -> Unit
                    }
                    when (val wait = awaitResult()) {
                        is Step.Failed -> return wait.failure
                        is Step.Received -> {
                            drain(wait.frame)
                            return complete(wait.frame)
                        }
                        else -> Unit // ResentAfterLateNak: di nuovo al punto 3 con la nuova deadline dell'ACK
                    }
                }
            } finally {
                // Codice non sospendente: vale anche con la coroutine cancellata. Il residuo non riceve ACK,
                // non è mai un esito e non passa a un altro splitter, ma resta nel log (§3.7).
                logResidue(splitter)
            }
        }

        // ─── Punto 1: orfani prima dell'invio ────────────────────────────────

        private suspend fun collectOrphans() {
            if (spec.freshConnection && options.preSendQuietMs > 0) delay(options.preSendQuietMs)
            val stale = transport.rx.takeAll()
            if (stale.isEmpty()) return
            checkOverflow()
            trace.warning(WarningCode.STALE_BYTES, "${stale.size} byte ricevuti prima dell'invio: scartati senza ACK")
            val orphans = FrameSplitter(options.appLrcMode, options.controlLrcRx)
            orphans.feed(stale)
            try {
                while (true) {
                    val item = orphans.next() ?: break
                    report(item)
                    val app = (item as? SplitItem.Parsed)?.frame as? WireFrame.Application ?: continue
                    if (app.replyCode in RESULT_FAMILY) possibleLostResult(app, where = "prima dell'invio")
                }
            } finally {
                logResidue(orphans)
            }
        }

        // ─── Invio del frame principale ──────────────────────────────────────

        /** null = frame scritto e attesa dell'ACK armata; altrimenti il fallimento da restituire. */
        private suspend fun transmitMain(reason: SendReason): ExchangeResult.Failure? {
            if (cancel.cancelled) return ExchangeResult.Failure(Ecr17Failure.Cancelled(byUser = true), currentPhase())
            try {
                trace.phase(ExchangePhase.SENDING, trace.sends + 1)
            } catch (e: ListenerAbortException) {
                // Possibile solo prima del primo tentativo di scrittura: il journal non ha l'invio, il frame non parte (M3).
                return ExchangeResult.Failure(Ecr17Failure.Internal("journal: ${e.message}"), ExchangePhase.SENDING)
            }
            // L'attesa del journal (fsync) può durare: un Annulla arrivato nel frattempo ferma l'invio.
            if (cancel.cancelled) {
                return ExchangeResult.Failure(Ecr17Failure.Cancelled(byUser = true), ExchangePhase.SENDING)
            }
            // rx chiuso = caduta già vista: il trasporto rifiuterebbe comunque la scrittura, quindi nulla è partito.
            // cancelCurrent() imposta il token PRIMA di abort(): un rx chiuso dall'Annulla è un annullamento, non
            // una caduta (come in linkClosedFailure e writeFailure).
            if (!transport.isOpen || transport.rx.closedCause != null) {
                val failure = if (cancel.cancelled) Ecr17Failure.Cancelled(byUser = true) else disconnected()
                return ExchangeResult.Failure(failure, ExchangePhase.SENDING)
            }
            trace.anyMainFrameWriteAttempted = true   // M10: appiccicoso, PRIMA della send
            try {
                transport.send(frame, options.writeTimeoutMs)
            } catch (e: TransportException) {
                return ExchangeResult.Failure(writeFailure(e), ExchangePhase.SENDING)
            }
            trace.sends++
            if (reason == SendReason.ACK_TIMEOUT) trace.timeoutRetransmissions++
            activitySinceSend = false
            ackDeadline = timeSource.markNow() + options.ackTimeoutMs.milliseconds
            emitTx(
                bytes = frame.copyOf(),
                kind = FrameKind.APPLICATION,
                code = spec.command.wire,
                attempt = trace.sends,
                lrcNote = "LRC ${hex2(frame.last().toInt())} (${options.appLrcMode.wireName})",
            )
            enter(ExchangePhase.AWAITING_ACK)
            return null
        }

        // ─── Punto 3: handshake ──────────────────────────────────────────────

        private suspend fun awaitAck(): Step {
            while (true) {
                val step = when (val next = next(ackDeadline)) {
                    is Next.Item -> onHandshakeItem(next.item)
                    Next.Timeout -> onAckTimeout()
                    is Next.LinkClosed -> Step.Failed(
                        ExchangeResult.Failure(linkClosedFailure(next), ExchangePhase.AWAITING_ACK),
                    )
                }
                if (step != null) return step
            }
        }

        /** null = si resta in attesa dell'ACK (con la deadline corrente, o con quella nuova dopo un reinvio). */
        private suspend fun onHandshakeItem(item: SplitItem): Step? {
            val frame = (item as? SplitItem.Parsed)?.frame ?: return null   // scarti: evento (e AMBIGUOUS_NAK) già emessi
            return when (frame) {
                is WireFrame.Control -> if (frame.kind == ControlKind.ACK) onHandshakeAck(frame) else onHandshakeNak()
                is WireFrame.Application -> onHandshakeApplication(frame)
                is WireFrame.Progress -> {
                    handleProgress(frame)
                    if (spec.kind == ExchangeKind.EXCHANGE) {
                        // ACK implicito: il terminale ha ricevuto la richiesta e la sta elaborando (L§7.3, M2).
                        acknowledgeImplicitly()
                        Step.ToResult
                    } else {
                        // Solo ACK: si continua ad attendere l'ACK, ma il terminale ha dato un segnale e il
                        // timeout non deve più reinviare (una ristampa verso il terminale uscirebbe due volte).
                        activitySinceSend = true
                        null
                    }
                }
            }
        }

        private suspend fun onHandshakeAck(ack: WireFrame.Control): Step {
            trace.ackMode = ackModeOf(ack)
            ackSeen = true
            activitySinceSend = true
            enter(ExchangePhase.ACKNOWLEDGED)
            if (spec.kind == ExchangeKind.EXCHANGE) return Step.ToResult
            if (spec.collectReceiptAfterAck) drain(resultFrame = null)
            return Step.AckOnlyDone
        }

        private suspend fun onHandshakeNak(): Step? {
            // Lo splitter consegna come Control(NAK) solo NAK con ETX e LRC WIRE/SAME_AS_APP (§3.7).
            trace.naksReceived++
            if (nakResends < options.nakRetryCount) {
                nakResends++
                delay(options.retryDelayMs)
                return transmitMain(SendReason.NAK)?.let { Step.Failed(it) }
            }
            return Step.Failed(ExchangeResult.Failure(Ecr17Failure.Nak(trace.sends), ExchangePhase.AWAITING_ACK))
        }

        private suspend fun onHandshakeApplication(app: WireFrame.Application): Step? {
            if (!lrcAccepted(app)) {
                rejectFrame(app)   // il terminale ritrasmetterà; si resta in AWAITING_ACK
                return null
            }
            val code = app.replyCode
            if (spec.kind == ExchangeKind.EXCHANGE && code != null && code in spec.expectedReplies) {
                pending = app
                trace.ackMode = AckMode.RESULT_BEFORE_ACK
                activitySinceSend = true
                if (spec.command.usesFinancialAckPolicy) {
                    val reused = if (spec.freshConnection) "" else
                        " (connessione riusata: potrebbe essere l'esito tardivo di un comando precedente)"
                    trace.warning(
                        WarningCode.EARLY_RESULT,
                        "esito arrivato prima dell'ACK: verificalo con Ultimo esito (G)$reused",
                    )
                }
                return Step.ToResult
            }
            if (code == RECEIPT_CODE) {
                sendControl(ControlChars.ACK)
                receiveReceiptLine(app)
                if (spec.kind == ExchangeKind.EXCHANGE) {
                    acknowledgeImplicitly()
                    return Step.ToResult
                }
                if (spec.collectReceiptAfterAck) {
                    acknowledgeImplicitly()
                    drain(resultFrame = null)
                    return Step.AckOnlyDone
                }
                // Una riga S è attività del terminale (§3.21): da qui il timeout dell'ACK non reinvia più.
                activitySinceSend = true
                trace.warning(
                    WarningCode.UNEXPECTED_REPLY,
                    "riga di scontrino 'S' inattesa per '${spec.command.wire}' durante l'attesa dell'ACK: ACK inviato",
                )
                return null
            }
            // Un frame non atteso non dimostra che il terminale abbia ricevuto QUESTA richiesta: niente ACK
            // implicito, stessa deadline, e un NAK o il timeout che seguono valgono ancora.
            sendControl(ControlChars.ACK)
            unexpectedReply(app, where = "durante l'attesa dell'ACK")
            return null
        }

        /** Progress o riga S prima dell'ACK: il terminale sta elaborando la richiesta. Nessun reinvio dopo (M2). */
        private suspend fun acknowledgeImplicitly() {
            trace.ackMode = AckMode.PROGRESS_BEFORE_ACK
            activitySinceSend = true
            enter(ExchangePhase.ACKNOWLEDGED)
        }

        private suspend fun onAckTimeout(): Step? {
            // Un 06 / 06 03 isolati diventano un ACK solo ora: il silenzio dice che l'LRC non arriverà.
            splitter.flushBareControl()?.let { item ->
                report(item)
                val ack = (item as? SplitItem.Parsed)?.frame as? WireFrame.Control
                if (ack != null && ack.kind == ControlKind.ACK) return onHandshakeAck(ack)
            }
            if (spec.kind == ExchangeKind.EXCHANGE && spec.ackPolicy == FinancialAckPolicy.IMPLICIT_GO_AHEAD) {
                // M2: un frame finanziario senza ACK non si reinvia mai; si attende l'esito.
                trace.warning(
                    WarningCode.IMPLICIT_GO_AHEAD,
                    "ACK non ricevuto entro ${options.ackTimeoutMs} ms: attendo l'esito senza reinviare",
                )
                trace.ackMode = AckMode.IMPLICIT_GO_AHEAD
                enter(ExchangePhase.IMPLICIT_GO_AHEAD)
                return Step.ToResult
            }
            if (!activitySinceSend && timeoutResends < options.ackTimeoutRetransmitCount) {
                timeoutResends++
                delay(options.retryDelayMs)
                return transmitMain(SendReason.ACK_TIMEOUT)?.let { Step.Failed(it) }
            }
            return Step.Failed(ExchangeResult.Failure(Ecr17Failure.AckTimeout(trace.sends), ExchangePhase.AWAITING_ACK))
        }

        // ─── Punto 4: attesa dell'esito ──────────────────────────────────────

        private suspend fun awaitResult(): Step {
            // La deadline non è mai estesa da progress, scontrini o ACK tardivi (A§5.2).
            val deadline = timeSource.markNow() + options.responseTimeoutMs.milliseconds
            enter(ExchangePhase.AWAITING_RESULT)
            pending?.let { early ->
                pending = null
                return acceptResult(early)   // LRC già verificato al punto 3: nessun warning ripetuto
            }
            while (true) {
                val step = when (val next = next(deadline)) {
                    is Next.Item -> onResultItem(next.item)
                    Next.Timeout -> Step.Failed(
                        ExchangeResult.Failure(Ecr17Failure.ResponseTimeout(options.responseTimeoutMs), currentPhase()),
                    )
                    is Next.LinkClosed -> Step.Failed(ExchangeResult.Failure(linkClosedFailure(next), currentPhase()))
                }
                if (step != null) return step
            }
        }

        private suspend fun onResultItem(item: SplitItem): Step? {
            val frame = (item as? SplitItem.Parsed)?.frame ?: return null
            return when (frame) {
                is WireFrame.Application -> onResultApplication(frame)
                is WireFrame.Control -> if (frame.kind == ControlKind.ACK) onResultAck(frame) else onResultNak()
                is WireFrame.Progress -> {
                    handleProgress(frame)
                    activitySinceSend = true
                    null
                }
            }
        }

        private suspend fun onResultApplication(app: WireFrame.Application): Step? {
            if (!lrcAccepted(app)) {
                rejectFrame(app)
                return null
            }
            val code = app.replyCode
            if (code == RECEIPT_CODE) {
                sendControl(ControlChars.ACK)
                receiveReceiptLine(app)
                activitySinceSend = true
                return null
            }
            if (code != null && code in spec.expectedReplies) return acceptResult(app)
            // M4: codice non atteso (U stantio, esito di un altro comando…): ACK, orfano, mai esito.
            sendControl(ControlChars.ACK)
            unexpectedReply(app, where = "durante l'attesa dell'esito")
            return null
        }

        private suspend fun onResultAck(ack: WireFrame.Control): Step? {
            val mode = trace.ackMode
            when {
                mode == AckMode.IMPLICIT_GO_AHEAD && !ackSeen -> {
                    ackSeen = true
                    activitySinceSend = true
                    trace.lateAck = true
                    warnAboutAckShape(ack)
                    trace.warning(WarningCode.LATE_ACK, "ACK arrivato dopo il go-ahead: il terminale ha ricevuto la richiesta")
                    enter(ExchangePhase.ACKNOWLEDGED)
                    enter(ExchangePhase.AWAITING_RESULT)   // stessa deadline: l'ACK tardivo non la estende
                }
                mode == AckMode.PROGRESS_BEFORE_ACK && !ackSeen -> {
                    // È l'ACK che il progress (o la riga S) ha preceduto: accettato in silenzio.
                    ackSeen = true
                    activitySinceSend = true
                    warnAboutAckShape(ack)
                }
                else -> trace.warning(WarningCode.SPURIOUS_CONTROL, "ACK ignorato durante l'attesa dell'esito")
            }
            return null
        }

        private suspend fun onResultNak(): Step? {
            val lateNakOfOurFrame = trace.ackMode == AckMode.IMPLICIT_GO_AHEAD && !activitySinceSend
            if (lateNakOfOurFrame && nakResends < options.nakRetryCount) {
                // NAK tardivo del nostro frame: il terminale non l'ha accettato, quindi non lo sta elaborando.
                trace.naksReceived++
                nakResends++
                delay(options.retryDelayMs)
                return transmitMain(SendReason.NAK)?.let { Step.Failed(it) } ?: Step.ResentAfterLateNak
            }
            trace.warning(WarningCode.SPURIOUS_CONTROL, "NAK ignorato durante l'attesa dell'esito: nessun reinvio")
            return null
        }

        private suspend fun acceptResult(app: WireFrame.Application): Step {
            sendControl(ControlChars.ACK)   // best effort (M5): un errore di trasporto non annulla l'esito
            activitySinceSend = true
            val masked = PanMasker.maskApplicationPayload(app.payload)
            // Il journal salva subito il payload (record RECEIVED): sopravvive a un annullamento nel drain (§5.5).
            enter(ExchangePhase.RESULT_RECEIVED, resultRaw = String(masked, Charsets.ISO_8859_1))
            if (trace.timeoutRetransmissions > 0) {
                trace.warning(
                    WarningCode.POSSIBLE_DUPLICATE,
                    "esito ricevuto dopo ${trace.timeoutRetransmissions} reinvii per ACK mancante: " +
                        "possibile doppio addebito, verifica con G o Totali",
                )
            }
            return Step.Received(app)
        }

        // ─── Punto 5: drain ──────────────────────────────────────────────────

        /**
         * Raccoglie quello che il terminale invia dopo l'esito (righe S, esito ritrasmesso, orfani) finché c'è
         * silenzio, la riga finale dello scontrino (`isEnd`, ESC in coda) o il tetto. Best effort: non trasforma mai
         * l'esito in errore (M5).
         */
        private suspend fun drain(resultFrame: WireFrame.Application?) {
            enter(ExchangePhase.DRAINING)
            val window = options.drainWindowMs(spec.expectReceipt)
            val capMs = maxOf(DRAIN_MIN_CAP_MS, DRAIN_CAP_WINDOWS * window)
            val capDeadline = timeSource.markNow() + capMs.milliseconds
            // Solo una riga finale vista DURANTE il drain lo chiude (§3.21 punto 5). Prima si partiva da
            // receiptEndSeen: con le righe S arrivate prima dell'esito (stampa su ECR, A§5.5) il drain era saltato
            // e un esito ritrasmesso o le righe di una seconda copia restavano senza ACK.
            var endSeen = false
            var items = 0
            try {
                while (!endSeen) {
                    val silenceDeadline = timeSource.markNow() + window.milliseconds
                    val capFirst = capDeadline < silenceDeadline
                    when (val next = next(if (capFirst) capDeadline else silenceDeadline)) {
                        Next.Timeout -> {
                            if (capFirst) drainInterrupted("limite del drain raggiunto ($capMs ms)")
                            break
                        }
                        is Next.LinkClosed -> {
                            if (next.cause != CloseCause.PEER_CLOSED) {
                                drainInterrupted("collegamento chiuso durante il drain (${next.cause})")
                            }
                            break
                        }
                        is Next.Item -> {
                            items++
                            endSeen = onDrainItem(next.item, resultFrame)
                            if (!endSeen && items >= DRAIN_MAX_ITEMS) {
                                drainInterrupted("limite del drain raggiunto ($DRAIN_MAX_ITEMS frame)")
                                break
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                drainInterrupted("errore durante il drain: ${e.message ?: e::class.simpleName}")
            }
            // La riga finale può essere arrivata prima dell'esito: in quel caso lo scontrino è completo.
            if (spec.expectReceipt && !endSeen && !receiptEndSeen) {
                trace.warning(WarningCode.RECEIPT_END_MISSING, "drain chiuso senza la riga di fine scontrino")
            }
        }

        /** true se è arrivata la riga finale dello scontrino (`isEnd`, §3.14). */
        private suspend fun onDrainItem(item: SplitItem, resultFrame: WireFrame.Application?): Boolean {
            val frame = (item as? SplitItem.Parsed)?.frame ?: return false   // AMBIGUOUS_NAK: warning già emesso
            return when (frame) {
                is WireFrame.Application -> {
                    when {
                        resultFrame != null && frame.raw.contentEquals(resultFrame.raw) -> {
                            trace.warning(
                                WarningCode.RESULT_RETRANSMITTED,
                                "esito '${frame.replyCode}' ricevuto di nuovo nel drain: il terminale non ha accettato il nostro ACK?",
                            )
                            sendControl(ControlChars.ACK)
                            trace.resultRetransmissions++
                            false
                        }
                        !lrcAccepted(frame) -> {
                            rejectFrame(frame)
                            false
                        }
                        frame.replyCode == RECEIPT_CODE -> {
                            sendControl(ControlChars.ACK)
                            receiveReceiptLine(frame).isEnd
                        }
                        else -> {
                            sendControl(ControlChars.ACK)
                            unexpectedReply(frame, where = "nel drain")
                            false
                        }
                    }
                }
                is WireFrame.Progress -> {
                    handleProgress(frame)
                    false
                }
                is WireFrame.Control -> false   // ACK/NAK dopo l'esito: ignorati
            }
        }

        private fun drainInterrupted(detail: String) {
            trace.warning(WarningCode.DRAIN_INTERRUPTED, "$detail: esito comunque valido")
        }

        private suspend fun complete(result: WireFrame.Application?): ExchangeResult {
            enter(ExchangePhase.COMPLETED)
            return ExchangeResult.Success(result, trace.ackMode)
        }

        // ─── Primitiva next(deadline) ────────────────────────────────────────

        /**
         * Ordine vincolante (§3.21): (1) elemento dello splitter; (2) byte nuovi dall'RxBuffer; (3) chiusura, solo
         * dopo aver consumato tutti i byte (A§6.2); (4) deadline; (5) attesa del segnale, e si ripete.
         */
        private suspend fun next(deadline: ComparableTimeMark): Next {
            while (true) {
                splitter.next()?.let { item ->
                    report(item)
                    return Next.Item(item)
                }
                val bytes = transport.rx.takeAll()
                if (bytes.isNotEmpty()) {
                    checkOverflow()
                    splitter.feed(bytes)
                    continue
                }
                val cause = transport.rx.closedCause
                if (cause != null) {
                    // Il lettore accoda i byte PRIMA di chiudere l'epoca: dopo aver visto la chiusura un ultimo
                    // takeAll raccoglie quelli arrivati tra il controllo precedente e questo.
                    val late = transport.rx.takeAll()
                    if (late.isNotEmpty()) {
                        splitter.feed(late)
                        continue
                    }
                    return Next.LinkClosed(cause, transport.rx.closedDetail)
                }
                val remaining = deadline - timeSource.markNow()
                if (!remaining.isPositive()) return Next.Timeout
                withTimeoutOrNull(remaining) { transport.rx.awaitDataOrClose() }
            }
        }

        // ─── Primitive di invio ──────────────────────────────────────────────

        /** ACK/NAK best effort (M5): un errore di trasporto diventa un warning e lo scambio prosegue. */
        private suspend fun sendControl(ctrl: Int) {
            val bytes = options.controlLrcTx.encode(ctrl, options.appLrcMode)
            val name = ControlChars.nameOf(ctrl) ?: hex2(ctrl)
            try {
                transport.send(bytes, options.writeTimeoutMs)
            } catch (e: TransportException) {
                trace.warning(
                    WarningCode.CONTROL_SEND_FAILED,
                    "invio di $name non riuscito (${e.kind}${e.detail?.let { ": $it" } ?: ""}): best effort, si prosegue",
                )
                return
            }
            emitTx(
                bytes = bytes,
                kind = if (ctrl == ControlChars.ACK) FrameKind.ACK else FrameKind.NAK,
                code = null,
                attempt = null,
                lrcNote = "LRC ${hex2(bytes.last().toInt())}, ${options.controlLrcTx.name}",
            )
        }

        private fun emitTx(bytes: ByteArray, kind: FrameKind, code: Char?, attempt: Int?, lrcNote: String) {
            // Le richieste dell'ECR non contengono PAN: i byte TX restano quelli sul filo (§3.8).
            emitter.emit { seq, at ->
                ProtocolEvent.Wire(seq, at, spec.exchangeId, FrameDirection.TX, kind, code, bytes, attempt, lrcNote)
            }
        }

        // ─── Classificazione dei frame ───────────────────────────────────────

        /**
         * true se l'LRC è valido nella modalità configurata; con ACCEPT_ANY_MODE anche se valido in un'altra
         * modalità (warning), TRANNE per gli esiti attesi "rigidi" (journaled e G): un errore di 1 bit con delta
         * 01/02/03 renderebbe un KO un OK con un LRC valido in un'altra modalità (§3.16, SE-48).
         */
        private fun lrcAccepted(app: WireFrame.Application): Boolean {
            val check = app.check
            if (check.validInConfiguredMode) return true
            val strictResult = spec.strictResultLrc && app.replyCode in spec.expectedReplies
            if (check.validInSomeMode && options.appLrcRx == AppLrcRxPolicy.ACCEPT_ANY_MODE && !strictResult) {
                trace.warning(
                    WarningCode.APP_LRC_OTHER_MODE,
                    "LRC ${hex2(check.received)} valido solo in ${modes(check.matchingModes)} " +
                        "(configurata ${check.configuredMode.wireName}): frame accettato",
                )
                return true
            }
            return false
        }

        /** NAK + APP_LRC_INVALID: il terminale ritrasmetterà il frame. */
        private suspend fun rejectFrame(app: WireFrame.Application) {
            sendControl(ControlChars.NAK)
            val check = app.check
            val lrc = hex2(check.received)
            val detail = when {
                check.validInSomeMode && options.appLrcRx == AppLrcRxPolicy.ACCEPT_ANY_MODE ->
                    "LRC $lrc valido solo in ${modes(check.matchingModes)}: rifiutato per un esito che decide un pagamento"
                check.validInSomeMode ->
                    "LRC $lrc valido solo in ${modes(check.matchingModes)}: rifiutato (ricezione LRC rigida)"
                else ->
                    "LRC $lrc errato (atteso ${hex2(check.configuredMode.compute(app.payload))} in " +
                        "${check.configuredMode.wireName})"
            }
            trace.warning(WarningCode.APP_LRC_INVALID, "$detail: NAK inviato")
        }

        private fun unexpectedReply(app: WireFrame.Application, where: String) {
            val code = app.replyCode
            val expected = if (spec.expectedReplies.isEmpty()) "nessuno (comando solo ACK)" else
                spec.expectedReplies.joinToString("/")
            val detail = if (code == null) {
                "frame applicativo di ${app.payload.size} byte senza codice in pos 10 $where: ACK inviato, ignorato"
            } else {
                "codice '$code' inatteso per '${spec.command.wire}', attesi $expected ($where): ACK inviato, mai esito"
            }
            trace.warning(WarningCode.UNEXPECTED_REPLY, detail)
            if (code in RESULT_FAMILY) possibleLostResult(app, where)
        }

        /** Un esito non attribuibile: payload mascherato allegato come prova per il journal (M4, §5.5). */
        private fun possibleLostResult(app: WireFrame.Application, where: String) {
            val masked = PanMasker.maskApplicationPayload(app.payload)
            trace.warning(
                WarningCode.POSSIBLE_LOST_RESULT,
                "possibile esito perso ('${app.replyCode}' $where): usa Ultimo esito (G)",
                payload = String(masked, Charsets.ISO_8859_1),
            )
        }

        private fun receiveReceiptLine(app: WireFrame.Application): ReceiptLine {
            val line = ResponseParser.parseReceiptLine(app.payload, maskPan = true)
            if (line.isEnd) receiptEndSeen = true
            trace.receipt(line)
            return line
        }

        private fun handleProgress(progress: WireFrame.Progress) {
            val text = PanMasker.maskDigitRuns(String(progress.text, Charsets.ISO_8859_1))
            trace.progress(text)
            if (progress.text.size != ControlChars.PROGRESS_TEXT_LENGTH) {
                trace.warning(
                    WarningCode.PROGRESS_LENGTH,
                    "progress di ${progress.text.size} caratteri (attesi ${ControlChars.PROGRESS_TEXT_LENGTH})",
                )
            }
        }

        private fun ackModeOf(ack: WireFrame.Control): AckMode {
            warnAboutAckShape(ack)
            return if (ack.match == ControlLrcMatch.BARE) AckMode.BARE else AckMode.EXPLICIT
        }

        /** MISMATCH e BARE sono accettati solo per l'ACK (asimmetria U3), sempre con un avviso. */
        private fun warnAboutAckShape(ack: WireFrame.Control) {
            when (ack.match) {
                ControlLrcMatch.MISMATCH -> trace.warning(
                    WarningCode.CONTROL_LRC_MISMATCH,
                    "ACK con LRC ${ack.lrc?.let(::hex2)} di nessuna convenzione: accettato (ricezione tollerante)",
                )
                ControlLrcMatch.BARE -> trace.warning(
                    WarningCode.BARE_CONTROL,
                    "ACK senza ETX o senza LRC (${Hex.spaced(ack.raw)}): accettato",
                )
                ControlLrcMatch.WIRE, ControlLrcMatch.SAME_AS_APP -> Unit
            }
        }

        // ─── Eventi RX e residui ─────────────────────────────────────────────

        /** Ogni elemento dello splitter esce come evento, con il PAN mascherato (M9). */
        private fun report(item: SplitItem) {
            when (item) {
                is SplitItem.Discarded -> {
                    val masked = PanMasker.maskFragment(item.bytes)
                    emitter.emit { seq, at -> ProtocolEvent.Discarded(seq, at, spec.exchangeId, masked, item.reason) }
                    if (item.reason == DiscardReason.AMBIGUOUS_NAK) {
                        trace.warning(
                            WarningCode.AMBIGUOUS_NAK,
                            "NAK ambiguo ignorato (${Hex.spaced(item.bytes)}): nessun reinvio",
                        )
                    }
                }
                is SplitItem.Parsed -> reportFrame(item.frame)
            }
        }

        private fun reportFrame(frame: WireFrame) {
            val (kind, code, note) = when (frame) {
                is WireFrame.Control -> Triple(
                    if (frame.kind == ControlKind.ACK) FrameKind.ACK else FrameKind.NAK,
                    null,
                    controlNote(frame),
                )
                is WireFrame.Application -> Triple(FrameKind.APPLICATION, frame.replyCode, appNote(frame))
                is WireFrame.Progress -> Triple(FrameKind.PROGRESS, null, null)
            }
            val bytes = PanMasker.maskFrame(frame.raw)
            emitter.emit { seq, at ->
                ProtocolEvent.Wire(seq, at, spec.exchangeId, FrameDirection.RX, kind, code, bytes, null, note)
            }
        }

        private fun logResidue(source: FrameSplitter) {
            val residue = source.clear()
            if (residue.isEmpty()) return
            val masked = PanMasker.maskFragment(residue)
            emitter.emit { seq, at -> ProtocolEvent.Discarded(seq, at, spec.exchangeId, masked, DiscardReason.NOISE) }
        }

        private fun checkOverflow() {
            if (overflowReported || !transport.rx.overflowed) return
            overflowReported = true
            trace.warning(WarningCode.RX_OVERFLOW, "buffer di ricezione pieno: i byte più vecchi sono stati scartati")
        }

        // ─── Fasi e fallimenti ───────────────────────────────────────────────

        private suspend fun enter(phase: ExchangePhase, resultRaw: String? = null) {
            trace.phase(phase, trace.sends, resultRaw)
        }

        private fun currentPhase(): ExchangePhase = trace.lastPhase ?: ExchangePhase.SENDING

        private fun linkClosedFailure(closed: Next.LinkClosed): Ecr17Failure =
            if (cancel.cancelled) Ecr17Failure.Cancelled(byUser = true) else Ecr17Failure.Disconnected(closed.cause, closed.detail)

        private fun disconnected(): Ecr17Failure {
            val closed = transport.linkState.value as? LinkState.Closed
            val cause = transport.rx.closedCause ?: closed?.cause ?: CloseCause.LOCAL
            val detail = transport.rx.closedDetail ?: closed?.detail ?: "collegamento non aperto"
            return Ecr17Failure.Disconnected(cause, detail)
        }

        private fun writeFailure(e: TransportException): Ecr17Failure = when (e.kind) {
            TransportErrorKind.NOT_CONNECTED, TransportErrorKind.ABORTED ->
                if (cancel.cancelled) Ecr17Failure.Cancelled(byUser = true) else disconnected()
            else -> Ecr17Failure.WriteFailed(e.detail ?: e.kind.name)
        }
    }

    private companion object {
        /** Codice in pos 10 delle righe di scontrino POS→ECR (A§4.5.9). */
        const val RECEIPT_CODE = 'S'

        /** Famiglia degli esiti: un orfano di questi codici è una possibile prova di un esito perso (M4). */
        val RESULT_FAMILY: Set<Char> = setOf('E', 'V', 'e', 'i', 'c', 'v')

        /** Tetto del drain: durata ≤ max(5 s, 4 × finestra) e al massimo 20 elementi (§3.21 punto 5). */
        const val DRAIN_MIN_CAP_MS = 5_000L
        const val DRAIN_CAP_WINDOWS = 4L
        const val DRAIN_MAX_ITEMS = 20

        fun hex2(value: Int): String = "%02X".format(value and 0xFF)

        fun modes(modes: Set<LrcMode>): String = modes.joinToString("/") { it.wireName }

        fun controlNote(frame: WireFrame.Control): String = when (frame.match) {
            ControlLrcMatch.WIRE -> "LRC ${frame.lrc?.let(::hex2)}, WIRE"
            ControlLrcMatch.SAME_AS_APP -> "LRC ${frame.lrc?.let(::hex2)}, SAME_AS_APP"
            ControlLrcMatch.MISMATCH -> "LRC ${frame.lrc?.let(::hex2)}, nessuna convenzione"
            ControlLrcMatch.BARE -> "senza LRC (nudo)"
        }

        fun appNote(frame: WireFrame.Application): String {
            val check: AppLrcCheck = frame.check
            val lrc = hex2(check.received)
            return when {
                check.validInConfiguredMode -> "LRC $lrc ok (${check.configuredMode.wireName})"
                check.validInSomeMode -> "LRC $lrc valido solo in ${modes(check.matchingModes)}"
                else -> "LRC $lrc errato (atteso ${hex2(check.configuredMode.compute(frame.payload))} in " +
                    "${check.configuredMode.wireName})"
            }
        }
    }
}
