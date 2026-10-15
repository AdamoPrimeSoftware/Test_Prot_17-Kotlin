package it.prime.protocollo17.ecr17.event

import it.prime.protocollo17.ecr17.model.PhaseChange
import it.prime.protocollo17.ecr17.model.ReceiptLine

/**
 * Callback di un singolo comando (il repository le usa per journal e progress UI).
 * Tutte le chiamate passano dal TraceRecorder (creato dal client, uno per comando), che applica la regola delle eccezioni:
 * - CancellationException: sempre rilanciata;
 * - eccezione in onPhase(SENDING) PRIMA del primo tentativo di scrittura del frame principale
 *   (TraceRecorder.anyMainFrameWriteAttempted == false): il frame NON viene scritto, NotSent(Internal) (M3);
 * - qualsiasi altra eccezione (onPhase con SENDING ≥ 2, ACKNOWLEDGED, IMPLICIT_GO_AHEAD, RESULT_RECEIVED…,
 *   onProgress, onReceiptLine, onWarning): catturata, warning JOURNAL_WRITE_FAILED (solo evento + trace,
 *   non rinviato a onWarning), e lo scambio PROSEGUE (M10). Mai NotSent dopo una scrittura.
 */
interface ExchangeListener {
    /**
     * Sospesa e ATTESA dalla sessione. Con phase = SENDING è chiamata prima di OGNI scrittura del frame
     * principale (sends = numero dell'invio che sta per partire). Con phase = RESULT_RECEIVED,
     * change.resultRaw contiene il payload dell'esito con PAN mascherato (il journal lo salva subito, §5.5).
     */
    suspend fun onPhase(change: PhaseChange) {}
    fun onProgress(text: String) {}
    fun onReceiptLine(line: ReceiptLine) {}
    fun onWarning(warning: ProtocolWarning) {}

    companion object { val NONE: ExchangeListener = object : ExchangeListener {} }
}
