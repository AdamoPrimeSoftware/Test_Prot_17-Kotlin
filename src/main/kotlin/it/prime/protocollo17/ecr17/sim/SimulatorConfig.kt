package it.prime.protocollo17.ecr17.sim

import it.prime.protocollo17.ecr17.wire.ControlLrcRule
import it.prime.protocollo17.ecr17.wire.LrcMode

// File completo di WP0 (§4.2): contiene anche SimEvent e SimState, perché WP5 (SimulatorControllerImpl)
// compila contro di essi prima che WP4 implementi il simulatore.

/** Decisione del simulatore sulle transazioni finanziarie. */
enum class SimDecision { APPROVE, DECLINE, CARD_NOT_PRESENT }

/**
 * Guasti riproducibili dal simulatore: ognuno corrisponde a un caso reale o temuto del terminale e alla
 * reazione attesa dell'ECR documentata in §4.5. Servono a provare la money-safety senza hardware.
 */
enum class SimFault {
    NONE,
    NO_ACK,                          // non invia l'ACK della richiesta, ma elabora ed invia l'esito
    PROGRESS_NO_ACK,                 // nessun ACK, progress a min(progressDelayMs, ritardo dell'esito) ed esito regolare
    SILENT,                          // né ACK né esito
    NAK_ONCE,                        // NAK al primo invio, ACK alla ritrasmissione
    NAK_ALWAYS,                      // NAK a ogni invio
    BARE_NAK,                        // un 0x15 nudo subito, poi (senza ACK) l'esito regolare
    DROP_AFTER_REQUEST,              // chiude il socket appena letta la richiesta
    DROP_AFTER_ACK,                  // ACK, poi chiude prima dell'esito
    DROP_AFTER_RESULT,               // invia l'esito e chiude subito, senza attendere l'ACK dell'ECR
    NO_RESULT,                       // ACK + progress, poi nulla
    UNEXPECTED_FRAME_BEFORE_RESULT,  // prima dell'esito invia un 'U' stantio (L§13.6 U34)
    BAD_LRC_RESULT_ONCE,             // primo esito con LRC corrotto; dopo il NAK lo reinvia corretto
    BARE_ACK,                        // ACK di 1 byte (06) seguito subito dall'esito
    STALE_RESULT_ON_CONNECT,         // appena connesso invia l'ultimo esito (frame stantio)
    LATE_RESULT_AFTER_NEXT_SEND,     // ACK ma esito trattenuto; lo invia subito dopo la richiesta SUCCESSIVA (anche su un'altra connessione), poi serve quella normalmente
}

/**
 * Configurazione del simulatore. I default riproducono il filo Ingenico osservato (LRC stx, ACK 06 03 7A,
 * un progress "OPERAZIONE IN CORSO ", esito E da 71 B) e l'ascolto solo su loopback (nessun prompt del firewall).
 */
data class SimulatorConfig(
    val bindHost: String = "127.0.0.1",
    val port: Int = 0,                                   // 0 = porta libera
    val terminalId: String = "02575733",
    /** LRC dei frame applicativi inviati E atteso sulle richieste: una richiesta con LRC errato in questa modalità riceve NAK. */
    val appLrcMode: LrcMode = LrcMode.STX,
    /** Regola dell'ACK/NAK che il SIMULATORE invia (WIRE = 06 03 7A) e che si aspetta dall'ECR. */
    val ackRule: ControlLrcRule = ControlLrcRule.WIRE,
    val ackDelayMs: Long = 40,
    val progressText: String = "OPERAZIONE IN CORSO ",   // 20 caratteri
    val progressDelayMs: Long = 400,
    val resultDelayMs: Long = 2_500,                     // dall'arrivo della richiesta
    val decision: SimDecision = SimDecision.APPROVE,
    val declineText: String = "OPERAZIONE ANNULLATA",
    val fault: SimFault = SimFault.NONE,
    /** true: il guasto vale solo per P X S p i c H C; i comandi di servizio funzionano sempre. */
    val faultOnlyFinancial: Boolean = true,
    /** Chiude il TCP dopo ogni transazione (comportamento "Nexi" della LESSON). */
    val closeAfterTransaction: Boolean = false,
    /** Se l'ACK dell'ECR all'esito manca o non rispetta ackRule entro resultRetransmitDelayMs, reinvia l'esito (max resultRetransmitMax). */
    val retransmitResultIfNoValidAck: Boolean = false,
    /** Attesa dell'ACK dell'ECR prima di reinviare l'esito. Va confrontata con il drain dell'ECR (§4.4). */
    val resultRetransmitDelayMs: Long = 1_000,
    val resultRetransmitMax: Int = 2,
    /** Con E1 armato: righe S dopo l'esito (true) o prima (false). */
    val receiptAfterResult: Boolean = true,
    val softwareRelease: String = "SIMP17 1",
    /** PAN in chiaro inviato dal simulatore (19 cifre left-pad '0'): serve a verificare il mascheramento. */
    val pan: String = "0004111111111111111",
)

/** Log del simulatore. I byte di FrameOut sono già mascherati con PanMasker.maskFrame (M9). */
sealed interface SimEvent {
    val at: Long

    data class Started(override val at: Long, val port: Int) : SimEvent
    data class Stopped(override val at: Long) : SimEvent
    data class ClientConnected(override val at: Long, val remote: String) : SimEvent
    data class ClientDisconnected(override val at: Long, val remote: String, val byPeer: Boolean) : SimEvent
    class FrameIn(override val at: Long, val bytes: ByteArray, val description: String) : SimEvent
    class FrameOut(override val at: Long, val bytes: ByteArray, val description: String) : SimEvent
    data class Note(override val at: Long, val text: String) : SimEvent
}

/**
 * Istantanea dei contatori del simulatore (test e finestra Simulatore). I frame applicativi ricevuti sono
 * contati per codice INCLUSE le ritrasmissioni: due `P` ricevuti per un solo pagamento sono il doppio addebito
 * che i test di money-safety devono vedere.
 */
data class SimState(
    val connections: Int,
    val applicationFramesReceived: Map<Char, Int>,   // per codice: conta anche le ritrasmissioni (doppio addebito!)
    val controlFramesReceived: List<String>,         // hex degli ACK/NAK ricevuti dall'ECR
    val requestsRejectedForLrc: Int,                 // richieste con LRC errato in appLrcMode: NAK, non elaborate
    val lastResultPayload: String?,
    val stan: Int,
    val onlineId: Int,
    val posTotalCents: Long,
    val ecrPrintingArmed: Boolean,
)
