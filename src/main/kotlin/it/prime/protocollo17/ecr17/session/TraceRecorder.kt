package it.prime.protocollo17.ecr17.session

import it.prime.protocollo17.ecr17.event.ExchangeListener
import it.prime.protocollo17.ecr17.event.ProtocolEvent
import it.prime.protocollo17.ecr17.event.ProtocolWarning
import it.prime.protocollo17.ecr17.event.WarningCode
import it.prime.protocollo17.ecr17.model.AckMode
import it.prime.protocollo17.ecr17.model.CommandCode
import it.prime.protocollo17.ecr17.model.Endpoint
import it.prime.protocollo17.ecr17.model.ExchangePhase
import it.prime.protocollo17.ecr17.model.ExchangeTrace
import it.prime.protocollo17.ecr17.model.PhaseChange
import it.prime.protocollo17.ecr17.model.PhaseStamp
import it.prime.protocollo17.ecr17.model.ReceiptLine
import it.prime.protocollo17.ecr17.profile.TerminalProfileId
import kotlin.coroutines.cancellation.CancellationException

/**
 * Registro di UN comando, creato e posseduto dal client (uno per execute) e condiviso con la sessione principale
 * (anche con quella del replay dopo una caduta).
 *
 * Esiste per due motivi.
 * 1. Il flag [anyMainFrameWriteAttempted] è appiccicoso (M10): impostato subito prima della prima
 *    `transport.send` del frame principale e mai azzerato. Il client lo legge da qui, non dal risultato della
 *    sessione: anche quando la sessione esce con un'eccezione il client sa se il terminale può aver ricevuto
 *    il comando, e un comando forse eseguito non diventa mai NotSent.
 * 2. Ogni chiamata al listener (journal, UI) passa da qui e applica in un solo punto la regola delle eccezioni
 *    di §3.19: solo SENDING prima del primo tentativo di scrittura può fermare il comando (M3); ogni altro errore
 *    del listener diventa un warning JOURNAL_WRITE_FAILED e lo scambio prosegue.
 *
 * Non è thread-safe per i contatori: la sessione gira su una sola coroutine. Solo il flag è @Volatile, perché il
 * client lo legge anche nel ramo di annullamento.
 */
internal class TraceRecorder(
    private val exchangeId: Long,
    private val command: CommandCode,
    private val endpoint: Endpoint,
    private val profile: TerminalProfileId,
    private val optionsSummary: String,
    private val listener: ExchangeListener,
    private val emitter: EventEmitter,
    private val wallClock: () -> Long,
) {
    /** Impostato a true SUBITO PRIMA della prima transport.send del frame principale; mai azzerato (M10). */
    @Volatile
    var anyMainFrameWriteAttempted: Boolean = false

    /** Invii riusciti del frame principale (ritrasmissioni comprese). */
    var sends: Int = 0

    /** NAK validi (Control NAK) ricevuti per il frame principale, compresi i NAK tardivi. */
    var naksReceived: Int = 0

    /** Reinvii causati da ACK mancante. */
    var timeoutRetransmissions: Int = 0

    var ackMode: AckMode? = null
    var lateAck: Boolean = false
    var resultRetransmissions: Int = 0
    var replayedAfterReconnect: Boolean = false
    var printingSetupSent: Boolean = false

    private val startedAt: Long = wallClock()
    private val phaseStamps = mutableListOf<PhaseStamp>()
    private val receiptLines = mutableListOf<ReceiptLine>()
    private val warningList = mutableListOf<ProtocolWarning>()

    /** Copia degli avvisi registrati finora (JOURNAL_WRITE_FAILED compresi). */
    val warnings: List<ProtocolWarning> get() = warningList.toList()

    /** Ultima fase registrata: è la "fase corrente" di un esito Unknown/Failed nato da un'eccezione (§3.23 p.11). */
    val lastPhase: ExchangePhase? get() = phaseStamps.lastOrNull()?.phase

    /**
     * Registra la fase, emette PhaseChanged e attende listener.onPhase. Regola delle eccezioni di §3.19:
     * lancia ListenerAbortException SOLO se phase == SENDING && !anyMainFrameWriteAttempted; altrimenti
     * cattura, warning JOURNAL_WRITE_FAILED e ritorna normalmente. CancellationException sempre rilanciata.
     */
    suspend fun phase(phase: ExchangePhase, sends: Int, resultRaw: String? = null) {
        val at = wallClock()
        phaseStamps += PhaseStamp(phase, at)
        val change = PhaseChange(
            exchangeId = exchangeId, command = command, phase = phase, at = at, sends = sends, resultRaw = resultRaw,
        )
        emitter.emit { seq, eventAt -> ProtocolEvent.PhaseChanged(seq, eventAt, change) }
        try {
            listener.onPhase(change)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Throwable e non Exception: anche un Error del listener (es. un TODO) dopo una scrittura non deve
            // interrompere lo scambio di un comando che il terminale può aver già eseguito (M10).
            if (phase == ExchangePhase.SENDING && !anyMainFrameWriteAttempted) throw ListenerAbortException(e)
            listenerFailed("onPhase($phase)", e)
        }
    }

    /** Evento + listener.onWarning (protetto). */
    fun warning(code: WarningCode, detail: String, payload: String? = null) {
        val warning = record(code, detail, payload)
        try {
            listener.onWarning(warning)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            listenerFailed("onWarning($code)", e)
        }
    }

    /** Evento + listener.onProgress (protetto). */
    fun progress(text: String) {
        emitter.emit { seq, at -> ProtocolEvent.Progress(seq, at, exchangeId, text) }
        try {
            listener.onProgress(text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            listenerFailed("onProgress", e)
        }
    }

    /** Evento + listener.onReceiptLine (protetto). La riga arriva già mascherata dal parser (M9). */
    fun receipt(line: ReceiptLine) {
        receiptLines += line
        emitter.emit { seq, at -> ProtocolEvent.Receipt(seq, at, exchangeId, line) }
        try {
            listener.onReceiptLine(line)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            listenerFailed("onReceiptLine", e)
        }
    }

    fun build(finishedAt: Long): ExchangeTrace = ExchangeTrace(
        exchangeId = exchangeId,
        command = command,
        endpoint = endpoint,
        profile = profile,
        optionsSummary = optionsSummary,
        sends = sends,
        anyMainFrameWriteAttempted = anyMainFrameWriteAttempted,
        naksReceived = naksReceived,
        timeoutRetransmissions = timeoutRetransmissions,
        ackMode = ackMode,
        lateAck = lateAck,
        phases = phaseStamps.toList(),
        receipt = receiptLines.toList(),
        warnings = warningList.toList(),
        resultRetransmissions = resultRetransmissions,
        replayedAfterReconnect = replayedAfterReconnect,
        printingSetupSent = printingSetupSent,
        startedAt = startedAt,
        finishedAt = finishedAt,
    )

    // ─── Dettagli ────────────────────────────────────────────────────────────

    private fun record(code: WarningCode, detail: String, payload: String?): ProtocolWarning {
        val warning = ProtocolWarning(code = code, detail = detail, at = wallClock(), payload = payload)
        warningList += warning
        emitter.emit { seq, at -> ProtocolEvent.Warning(seq, at, exchangeId, warning) }
        return warning
    }

    /**
     * JOURNAL_WRITE_FAILED: solo evento + trace, NON rinviato a onWarning (§3.19). Il listener che ha appena
     * fallito fallirebbe di nuovo, e un ciclo di avvisi sul journal rotto non serve a nessuno.
     */
    private fun listenerFailed(where: String, error: Throwable) {
        val reason = error.message ?: error::class.simpleName ?: "errore sconosciuto"
        record(
            code = WarningCode.JOURNAL_WRITE_FAILED,
            detail = "il listener ha lanciato in $where: $reason; lo scambio prosegue",
            payload = null,
        )
    }
}
