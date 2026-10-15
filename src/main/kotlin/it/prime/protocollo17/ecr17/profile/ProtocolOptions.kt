package it.prime.protocollo17.ecr17.profile

import it.prime.protocollo17.ecr17.error.ConfigProblem
import it.prime.protocollo17.ecr17.model.Endpoint
import it.prime.protocollo17.ecr17.model.TerminalIds
import it.prime.protocollo17.ecr17.wire.ControlLrcRule
import it.prime.protocollo17.ecr17.wire.LrcMode

/** Tetto strutturale dei reinvii (NAK e ACK mancante): oltre, un errore di configurazione farebbe un ciclo. */
private const val MAX_RETRY_COUNT = 5

/** U4: cosa fare se manca l'ACK di un comando con usesFinancialAckPolicy. */
enum class FinancialAckPolicy { IMPLICIT_GO_AHEAD, RETRANSMIT }

/** U5. */
enum class ConnectionMode { PER_COMMAND, PERSISTENT }

/** U3 lato ricezione: ETX è SEMPRE obbligatorio; qui si decide solo per l'LRC dell'ACK (il NAK è sempre rigido, §3.7). */
enum class ControlLrcRxPolicy { TOLERANT, STRICT }

/**
 * LRC dei frame applicativi ricevuti (L§10.3). ACCEPT_ANY_MODE accetta (con warning) un frame valido solo in
 * un'altra modalità, TRANNE gli esiti attesi di comandi journaled e di G (ExchangeSpec.strictResultLrc): lì
 * un LRC valido solo in un'altra modalità riceve NAK, perché un errore di 1 bit con delta 01/02/03 diventerebbe
 * invisibile (es. E4 KO con "01"→"00": LRC std del frame alterato = LRC stx dell'originale, 46).
 */
enum class AppLrcRxPolicy { ACCEPT_ANY_MODE, STRICT }

/**
 * Opzioni di sessione. Il client ne legge UNA istantanea per comando: le modifiche valgono dal comando successivo.
 *
 * I default sono quelli del profilo Ingenico osservato (U6): ACK 2 s, esito 120 s, connessione 5 s, drain 100 ms.
 */
data class ProtocolOptions(
    val appLrcMode: LrcMode = LrcMode.STX,
    val controlLrcTx: ControlLrcRule = ControlLrcRule.WIRE,
    val controlLrcRx: ControlLrcRxPolicy = ControlLrcRxPolicy.TOLERANT,
    val appLrcRx: AppLrcRxPolicy = AppLrcRxPolicy.ACCEPT_ANY_MODE,
    val financialAckPolicy: FinancialAckPolicy = FinancialAckPolicy.IMPLICIT_GO_AHEAD,
    val connectionMode: ConnectionMode = ConnectionMode.PER_COMMAND,
    val connectTimeoutMs: Long = 5_000,
    val ackTimeoutMs: Long = 2_000,
    val responseTimeoutMs: Long = 120_000,
    /** Reinvii su NAK esplicito valido (invii massimi per soli NAK = nakRetryCount + 1). U4. */
    val nakRetryCount: Int = 3,
    /** Reinvii su ACK mancante: solo con RETRANSMIT o per i comandi senza usesFinancialAckPolicy. U4. */
    val ackTimeoutRetransmitCount: Int = 3,
    val retryDelayMs: Long = 200,
    /** Silenzio che chiude il drain dopo l'esito quando NON si attendono righe S. */
    val postResultDrainMs: Long = 100,
    /** Silenzio che chiude il drain quando si attendono righe S (ExchangeSpec.expectReceipt). */
    val receiptDrainMs: Long = 1_500,
    /** Invia E1 prima dei comandi con wantsEcrPrintingSetup e attende le righe S. */
    val ecrPrinting: Boolean = false,
    /** Dopo una connessione nuova: attesa breve per raccogliere byte spontanei (orfani) prima di inviare. */
    val preSendQuietMs: Long = 50,
    val writeTimeoutMs: Long = 5_000,
    /** Un replay di s/T/G/E dopo una caduta (A§5.3). Mai per gli altri comandi. */
    val autoReconnectSafeCommands: Boolean = true,
) {
    /** Finestra di silenzio del drain: receiptDrainMs se si attendono righe S, altrimenti postResultDrainMs. */
    fun drainWindowMs(expectReceipt: Boolean): Long = if (expectReceipt) receiptDrainMs else postResultDrainMs

    /**
     * Solo coerenza STRUTTURALE; lista vuota = valide.
     *
     * Volutamente larga: i timeout > 0, le attese ≥ 0, i reinvii 0..[MAX_RETRY_COUNT] (tetto anti-ciclo). I range
     * dell'operatore (ACK ≥ 100 ms, esito ≥ 5 s…) stanno in OptionRanges di ConfigResolver: così le configurazioni
     * rapide dei test (ACK 40 ms, esito 200 ms) restano valide per il protocollo (§3.16, CL-26).
     */
    fun validate(): List<ConfigProblem> = buildList {
        fun positive(key: String, value: Long) {
            if (value <= 0) add(ConfigProblem(key, "must be greater than 0 (was $value)"))
        }
        fun nonNegative(key: String, value: Long) {
            if (value < 0) add(ConfigProblem(key, "must be 0 or greater (was $value)"))
        }
        fun retryCount(key: String, value: Int) {
            if (value !in 0..MAX_RETRY_COUNT) add(ConfigProblem(key, "must be in 0..$MAX_RETRY_COUNT (was $value)"))
        }
        positive("connectTimeoutMs", connectTimeoutMs)
        positive("ackTimeoutMs", ackTimeoutMs)
        positive("responseTimeoutMs", responseTimeoutMs)
        positive("writeTimeoutMs", writeTimeoutMs)
        nonNegative("retryDelayMs", retryDelayMs)
        nonNegative("postResultDrainMs", postResultDrainMs)
        nonNegative("receiptDrainMs", receiptDrainMs)
        nonNegative("preSendQuietMs", preSendQuietMs)
        retryCount("nakRetryCount", nakRetryCount)
        retryCount("ackTimeoutRetransmitCount", ackTimeoutRetransmitCount)
    }

    /** Riepilogo compatto per log e journal, es. "stx/WIRE rx=TOLERANT/ANY ack=IMPLICIT_GO_AHEAD conn=PER_COMMAND to=2000/120000/5000 nak=3 ackRetx=3 delay=200 drain=100/1500 print=off". */
    fun summary(): String {
        val appRx = when (appLrcRx) {
            AppLrcRxPolicy.ACCEPT_ANY_MODE -> "ANY"
            AppLrcRxPolicy.STRICT -> "STRICT"
        }
        return "${appLrcMode.wireName}/${controlLrcTx.name}" +
            " rx=${controlLrcRx.name}/$appRx" +
            " ack=${financialAckPolicy.name}" +
            " conn=${connectionMode.name}" +
            " to=$ackTimeoutMs/$responseTimeoutMs/$connectTimeoutMs" +
            " nak=$nakRetryCount ackRetx=$ackTimeoutRetransmitCount" +
            " delay=$retryDelayMs" +
            " drain=$postResultDrainMs/$receiptDrainMs" +
            " print=${if (ecrPrinting) "on" else "off"}"
    }
}

/** Configurazione completa di un comando. */
data class Ecr17Config(
    val profile: TerminalProfileId,
    val endpoint: Endpoint,
    val ids: TerminalIds,
    val options: ProtocolOptions,
) {
    /** options.validate() + host non vuoto + porta 1..65535. */
    fun validate(): List<ConfigProblem> = buildList {
        addAll(options.validate())
        if (endpoint.host.isBlank()) add(ConfigProblem("host", "is required"))
        when {
            endpoint.port == 0 -> add(ConfigProblem("port", "is required"))
            endpoint.port !in 1..65_535 -> add(ConfigProblem("port", "must be in 1..65535 (was ${endpoint.port})"))
        }
    }
}
