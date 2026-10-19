package it.prime.protocollo17.presentation.model

import it.prime.protocollo17.domain.model.command.FieldKey
import it.prime.protocollo17.ecr17.model.CommandCode

// Modelli di vista degli esiti, condivisi da Console, Journal e dialog di risoluzione: stanno fuori da
// screen/console perché li usano tre sezioni (skill project-structure: la parte condivisa va fuori).

/** Tono di un esito: decide colore del pallino e del titolo (verde, rosso, ambra, primario, neutro). */
enum class ResultTone { SUCCESS, DANGER, WARNING, INFO, NEUTRAL }

/** Riga etichetta / valore leggibile / posizione nel payload ("13/19" = pos 13, lunghezza 19). */
data class FieldRow(val label: String, val value: String, val position: String? = null)

/** Riga della scheda Cronologia (§6.7). */
data class HistoryRowView(
    val id: Long, val time: String, val letter: Char, val danger: Boolean,
    val headline: String, val tone: ResultTone, val amount: String?,
)

/** Azione rapida (§6.6): copia value nel campo key del form di target. */
data class QuickAction(val label: String, val target: CommandCode, val key: FieldKey, val value: String)

/**
 * Esito di un comando pronto da mostrare: titolo, campi decodificati con posizione, dettagli dello scambio, payload
 * grezzo, scontrino e avvisi. Costruito da ResultViewMapper (puro e testato), così il pannello non contiene logica
 * e la stessa resa vale per la scheda Esito, la Cronologia e il dialog dopo `G`.
 */
data class ResultView(
    val command: CommandCode,
    val tone: ResultTone,
    val headline: String,
    val detail: String?,
    /** Da ResponseField + FieldLabels, valori leggibili (PAN mascherato). */
    val fields: List<FieldRow>,
    /** Invii, NAK, reinvii per timeout, modalità ACK, ACK tardivo, codice ricevuto/attesi, durata, profilo, opzioni. */
    val exchange: List<FieldRow>,
    /** Fasi con orario. */
    val timeline: List<FieldRow>,
    /** RawPayload.text (ISO-8859-1, PAN mascherato) reso con Hex.printable (<STX>, <xx>). */
    val rawPayload: String?,
    /** Righe di stampa ricomposte dalle righe S (ReceiptText), non una per frame. */
    val receipt: List<String>,
    val warnings: List<String>,
    /** Avvisi di revisione (POSSIBLE_DUPLICATE, EARLY_RESULT) in evidenza sotto il titolo. */
    val reviewNotice: String?,
    val quickActions: List<QuickAction>,
    val journalError: String?,
    val finishedAt: Long,
)
