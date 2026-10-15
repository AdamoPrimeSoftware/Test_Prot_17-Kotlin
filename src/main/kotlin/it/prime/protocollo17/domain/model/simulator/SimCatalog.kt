package it.prime.protocollo17.domain.model.simulator

/** Voce di menu del simulatore. name = Enum.name di ecr17.sim (o wireName per l'LRC); allineamento verificato da SimCatalogTest. */
data class SimOption(val name: String, val label: String, val description: String)

/**
 * Etichette italiane per la finestra Simulatore: la presentation non può importare ecr17.sim (§2.10).
 *
 * I nomi sono scritti a mano (il dominio non può importare `ecr17.sim` né la sua tabella dei guasti): un guasto
 * aggiunto a SimFault e dimenticato qui fa fallire SimCatalogTest invece di sparire in silenzio dal menu.
 * Le descrizioni dei guasti riprendono la colonna "Cosa fa" di §4.5.
 */
object SimCatalog {

    /** Una voce per SimFault, descrizione = colonna "Cosa fa" di §4.5. */
    val faults: List<SimOption> = listOf(
        SimOption("NONE", "Nessun guasto", "Comportamento regolare: ACK, progress ed esito come il terminale Ingenico osservato."),
        SimOption("NO_ACK", "Nessun ACK", "Nessun ACK della richiesta, ma elabora e invia l'esito regolare."),
        SimOption("PROGRESS_NO_ACK", "Nessun ACK, con progress", "Nessun ACK, poi il progress dopo il ritardo impostato e l'esito regolare."),
        SimOption("SILENT", "Silenzio", "Nulla: né ACK né esito."),
        SimOption("NAK_ONCE", "NAK una volta", "NAK al primo invio, poi ACK alla ritrasmissione ed esito."),
        SimOption("NAK_ALWAYS", "NAK sempre", "NAK a ogni invio della richiesta."),
        SimOption("BARE_NAK", "NAK nudo", "Un byte 15 nudo subito, poi (senza ACK) l'esito regolare."),
        SimOption("DROP_AFTER_REQUEST", "Caduta dopo la richiesta", "Chiude subito il socket, appena letta la richiesta."),
        SimOption("DROP_AFTER_ACK", "Caduta dopo l'ACK", "Invia l'ACK, poi chiude il socket prima dell'esito."),
        SimOption("DROP_AFTER_RESULT", "Caduta dopo l'esito", "Invia l'esito e chiude subito, senza attendere l'ACK dell'ECR."),
        SimOption("NO_RESULT", "Nessun esito", "ACK e progress, poi nulla."),
        SimOption("UNEXPECTED_FRAME_BEFORE_RESULT", "Frame inatteso prima dell'esito", "Prima dell'esito invia un 'U' stantio, poi l'esito regolare."),
        SimOption("BAD_LRC_RESULT_ONCE", "LRC errato sull'esito", "Primo esito con LRC corrotto; dopo il NAK dell'ECR lo reinvia corretto."),
        SimOption("BARE_ACK", "ACK nudo", "ACK di 1 byte (06) seguito subito dall'esito."),
        SimOption("STALE_RESULT_ON_CONNECT", "Esito stantio alla connessione", "Appena connesso invia l'ultimo esito (frame vecchio)."),
        SimOption(
            "LATE_RESULT_AFTER_NEXT_SEND",
            "Esito tardivo",
            "ACK ma esito del primo comando finanziario trattenuto: lo invia subito dopo la richiesta successiva (anche su un'altra connessione), poi serve quella normalmente.",
        ),
    )

    /** APPROVE "Approva", DECLINE "Rifiuta", CARD_NOT_PRESENT "Carta non presente (05)". */
    val decisions: List<SimOption> = listOf(
        SimOption("APPROVE", "Approva", "Esito 00: transazione approvata."),
        SimOption("DECLINE", "Rifiuta", "Esito 01 con il testo di rifiuto (\"OPERAZIONE ANNULLATA\")."),
        SimOption("CARD_NOT_PRESENT", "Carta non presente (05)", "Esito 05: layout positivo con PAN e autorizzazione a zero."),
    )

    /** WIRE "06 03 7A (Ingenico)", SAME_AS_APP "06 03 78 (repo padosoft)". */
    val ackRules: List<SimOption> = listOf(
        SimOption("WIRE", "06 03 7A (Ingenico)", "LRC dell'ACK = XOR dei byte sul filo (0x7F ^ 06 ^ 03), come DLL e terminale Ingenico."),
        SimOption("SAME_AS_APP", "06 03 78 (repo padosoft)", "LRC dell'ACK calcolato con la modalità applicativa, come i repo padosoft (78 in stx)."),
    )

    /** std, stx, noext, stx_noext (name = LrcMode.wireName). */
    val lrcModes: List<SimOption> = listOf(
        SimOption("std", "std", "0x7F ^ payload: né STX né ETX nello XOR."),
        SimOption("stx", "stx", "0x7F ^ STX ^ payload ^ ETX: la modalità del terminale Ingenico (cattura reale)."),
        SimOption("noext", "noext", "0x7F ^ payload ^ ETX: senza STX, con ETX (il nome dei repo inganna)."),
        SimOption("stx_noext", "stx_noext", "0x7F ^ STX ^ payload: con STX, senza ETX."),
    )
}
