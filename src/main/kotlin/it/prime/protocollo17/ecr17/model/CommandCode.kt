package it.prime.protocollo17.ecr17.model

import it.prime.protocollo17.ecr17.model.ExchangeKind.ACK_ONLY
import it.prime.protocollo17.ecr17.model.ExchangeKind.EXCHANGE
import it.prime.protocollo17.ecr17.model.JournalPolicy.NONE
import it.prime.protocollo17.ecr17.model.JournalPolicy.RECORD
import it.prime.protocollo17.ecr17.model.JournalPolicy.RECORD_AND_BLOCK

/** Forma dello scambio: richiesta con esito applicativo oppure richiesta confermata dal solo ACK (E, R). */
enum class ExchangeKind { EXCHANGE, ACK_ONLY }

/** Cosa registra il journal (§5.3) e cosa succede con un esito sconosciuto. */
enum class JournalPolicy {
    NONE,              // s T G E R
    RECORD,            // H C: non ripetibili; esito sconosciuto = blocco SOLO della stessa famiglia di risposta (§5.8)
    RECORD_AND_BLOCK,  // P X S p i c: muovono denaro; esito sconosciuto = blocco di tutti i journaled (U7)
}

/**
 * I 13 comandi v1 (§1.3) con tutte le regole che li distinguono, in un solo posto.
 *
 * Le regole di money-safety (codici attesi U8, policy sull'ACK mancante U4, replay dopo caduta M1,
 * journal e blocco U7) sono proprietà del comando e non `when` sparsi tra sessione, client e UI:
 * aggiungere un comando significa compilare una riga, e una regola dimenticata si vede nella tabella.
 *
 * Note: `H` e `C` seguono la policy finanziaria sull'ACK per prudenza (L§10.5). Un `U` o `K` ricevuto non è
 * mai in `expectedReplies` ⇒ orfano (§1.4). `H` condivide con `P X S` la famiglia di risposta `E`/`V`: un `E`
 * tardivo di un `H` senza esito potrebbe essere letto come esito di un pagamento, per questo un `H`
 * sconosciuto blocca `P X S H` (§5.8).
 */
enum class CommandCode(
    val wire: Char,
    val kind: ExchangeKind,
    /** Codici (pos 10) accettati come esito. Vuoto per i comandi solo-ACK. (U8) */
    val expectedReplies: Set<Char>,
    /** true: su ACK mancante vale ProtocolOptions.financialAckPolicy; false: sempre ritrasmissione. */
    val usesFinancialAckPolicy: Boolean,
    /** Tabella money-safety (A§5.3): un solo replay dopo caduta + riconnessione. */
    val safeToRetryAfterReconnect: Boolean,
    val journalPolicy: JournalPolicy,
    /** true: con "stampa su ECR" attiva si invia E1 prima del comando (A§4.3.6, non persistente). */
    val wantsEcrPrintingSetup: Boolean,
) {
    STATUS('s', EXCHANGE, setOf('s'), false, true, NONE, false),
    PAYMENT('P', EXCHANGE, setOf('E', 'V'), true, false, RECORD_AND_BLOCK, true),
    PAYMENT_EXTENDED('X', EXCHANGE, setOf('E', 'V'), true, false, RECORD_AND_BLOCK, true),
    REVERSAL('S', EXCHANGE, setOf('E', 'V'), true, false, RECORD_AND_BLOCK, true),
    PRE_AUTH('p', EXCHANGE, setOf('e'), true, false, RECORD_AND_BLOCK, true),
    INCREMENTAL_AUTH('i', EXCHANGE, setOf('i'), true, false, RECORD_AND_BLOCK, true),
    PRE_AUTH_CLOSURE('c', EXCHANGE, setOf('c', 'v'), true, false, RECORD_AND_BLOCK, true),
    CARD_VERIFICATION('H', EXCHANGE, setOf('E', 'V'), true, false, RECORD, true),
    CLOSE_SESSION('C', EXCHANGE, setOf('C'), true, false, RECORD, true),
    TOTALS('T', EXCHANGE, setOf('T'), false, true, NONE, true),
    LAST_RESULT('G', EXCHANGE, setOf('E', 'V', 'e', 'i', 'c', 'v'), false, true, NONE, false),
    ECR_PRINTING('E', ACK_ONLY, emptySet(), false, true, NONE, false),
    REPRINT('R', ACK_ONLY, emptySet(), false, false, NONE, false);

    val movesMoney: Boolean get() = journalPolicy == RECORD_AND_BLOCK
    val journaled: Boolean get() = journalPolicy != NONE

    companion object {
        fun fromWire(c: Char): CommandCode? = entries.firstOrNull { it.wire == c }
    }
}
