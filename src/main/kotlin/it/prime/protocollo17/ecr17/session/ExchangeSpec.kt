package it.prime.protocollo17.ecr17.session

import it.prime.protocollo17.ecr17.error.Ecr17Failure
import it.prime.protocollo17.ecr17.model.AckMode
import it.prime.protocollo17.ecr17.model.CommandCode
import it.prime.protocollo17.ecr17.model.ExchangeKind
import it.prime.protocollo17.ecr17.model.ExchangePhase
import it.prime.protocollo17.ecr17.profile.FinancialAckPolicy
import it.prime.protocollo17.ecr17.wire.WireFrame

/**
 * Tutto ciò che la sessione deve sapere di UN invio, deciso dal client prima di partire (§3.21, §3.23 punto 7).
 *
 * La sessione non guarda mai le opzioni per decidere la policy dell'ACK, la raccolta dello scontrino o il rigore
 * dell'LRC: le riceve già risolte qui. Così la regola "quale comando usa quale policy" vive in un solo punto (il
 * client) e la sessione E1 di stampa su ECR può usare una spec diversa sulla stessa connessione.
 */
internal data class ExchangeSpec(
    val exchangeId: Long,
    val command: CommandCode,
    /** Payload ASCII della richiesta (senza STX/ETX/LRC). */
    val payload: ByteArray,
    val expectedReplies: Set<Char>,
    val kind: ExchangeKind,
    /** RETRANSMIT per i comandi senza usesFinancialAckPolicy. */
    val ackPolicy: FinancialAckPolicy,
    /** R verso ECR: dopo l'ACK si raccolgono le righe S. */
    val collectReceiptAfterAck: Boolean,
    /** Si attendono righe S: (ecrPrinting && command.wantsEcrPrintingSetup) || R verso ECR. Decide finestra del drain e RECEIPT_END_MISSING. */
    val expectReceipt: Boolean,
    /** Esiti per cui ACCEPT_ANY_MODE NON vale (§3.16): command.journaled || command == LAST_RESULT. */
    val strictResultLrc: Boolean,
    /** true ⇒ applica preSendQuietMs. */
    val freshConnection: Boolean,
) {
    // Il payload è un array: equals/hashCode per contenuto, come ci si aspetta da una data class.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ExchangeSpec) return false
        return exchangeId == other.exchangeId &&
            command == other.command &&
            payload.contentEquals(other.payload) &&
            expectedReplies == other.expectedReplies &&
            kind == other.kind &&
            ackPolicy == other.ackPolicy &&
            collectReceiptAfterAck == other.collectReceiptAfterAck &&
            expectReceipt == other.expectReceipt &&
            strictResultLrc == other.strictResultLrc &&
            freshConnection == other.freshConnection
    }

    override fun hashCode(): Int {
        var result = exchangeId.hashCode()
        result = 31 * result + command.hashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + expectedReplies.hashCode()
        result = 31 * result + kind.hashCode()
        result = 31 * result + ackPolicy.hashCode()
        result = 31 * result + collectReceiptAfterAck.hashCode()
        result = 31 * result + expectReceipt.hashCode()
        result = 31 * result + strictResultLrc.hashCode()
        result = 31 * result + freshConnection.hashCode()
        return result
    }
}

/**
 * Esito grezzo di una sessione: il client lo trasforma in CommandOutcome con la tabella di §3.23.
 *
 * Non c'è un flag "scritto/non scritto" qui: il client legge sempre TraceRecorder.anyMainFrameWriteAttempted,
 * che è appiccicoso e sopravvive anche alle eccezioni (M10). Un flag nel risultato andrebbe perso proprio quando
 * la sessione esce con un'eccezione.
 */
internal sealed interface ExchangeResult {
    /** result null solo per i comandi solo-ACK. */
    data class Success(val result: WireFrame.Application?, val ackMode: AckMode?) : ExchangeResult

    data class Failure(val failure: Ecr17Failure, val phase: ExchangePhase) : ExchangeResult
}
