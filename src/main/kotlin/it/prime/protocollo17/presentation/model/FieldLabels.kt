package it.prime.protocollo17.presentation.model

import it.prime.protocollo17.domain.util.formatCents
import it.prime.protocollo17.ecr17.model.EntryMode
import it.prime.protocollo17.ecr17.model.FieldId
import it.prime.protocollo17.ecr17.model.ResponseField
import java.math.BigDecimal

/**
 * Etichette italiane dei campi delle risposte e resa leggibile dei valori grezzi.
 *
 * Il valore leggibile tiene SEMPRE accanto il grezzo tra parentesi ("Credito (2)", "Contactless chip (CLI)"):
 * è un'app di test, e chi confronta con lo scontrino o con il log della DLL deve poter vedere il byte vero.
 * La tabella degli stati di `s` è quella Nexi 0..6 (A§4.6), con la cifra grezza sempre visibile (§9.3).
 */
object FieldLabels {

    fun label(id: FieldId): String = when (id) {
        FieldId.TERMINAL_ID -> "Terminal ID"
        FieldId.REPLY_CODE -> "Codice risposta"
        FieldId.RESULT_CODE -> "Esito"
        FieldId.PAN -> "PAN"
        FieldId.ENTRY_MODE -> "Modalità lettura"
        FieldId.AUTH_CODE -> "Codice autorizzazione"
        FieldId.HOST_DATE_TIME -> "Data/ora host"
        FieldId.ERROR_DESCRIPTION -> "Descrizione errore"
        FieldId.CARD_TYPE -> "Tipo carta"
        FieldId.ACQUIRER_ID -> "Acquirer"
        FieldId.STAN -> "STAN"
        FieldId.ONLINE_ID -> "Online id (Operation Number)"
        FieldId.ACTION_CODE -> "Action code"
        FieldId.HOST_AMOUNT -> "Importo host"
        FieldId.ORIGINAL_AMOUNT -> "Importo originale"
        FieldId.DCC_APPLIED -> "DCC applicato"
        FieldId.DCC_RATE -> "Tasso DCC"
        FieldId.DCC_CURRENCY -> "Valuta DCC"
        FieldId.DCC_AMOUNT -> "Importo DCC"
        FieldId.DCC_PRECISION -> "Decimali importo DCC"
        FieldId.PRE_AUTH_AMOUNT -> "Importo preautorizzato"
        FieldId.PRE_AUTH_CODE -> "Codice preautorizzazione"
        FieldId.TERMINAL_DATE_TIME -> "Data/ora terminale"
        FieldId.STATUS -> "Stato"
        FieldId.SOFTWARE_RELEASE -> "Release software"
        FieldId.POS_TOTAL -> "Totale POS"
        FieldId.HOST_TOTAL -> "Totale host"
    }

    /** Riga della tabella: etichetta, valore leggibile, posizione "pos/len". */
    fun row(field: ResponseField): FieldRow = FieldRow(label(field.id), value(field.id, field.raw), "${field.pos}/${field.len}")

    /** Valore leggibile dal grezzo (già mascherato dal parser; il PAN viene comunque rimascherato, M9). */
    fun value(id: FieldId, raw: String): String {
        val trimmed = raw.trimEnd()
        if (trimmed.isEmpty() && id != FieldId.PAN) return "(vuoto)"
        return when (id) {
            FieldId.PAN -> maskPan(raw)
            FieldId.RESULT_CODE -> when (raw) {
                "00" -> "00 · approvato"
                "01" -> "01 · rifiutato"
                "05" -> "05 · carta non presente"
                "09" -> "09 · tag sconosciuto"
                else -> "$raw · non riconosciuto"
            }
            FieldId.ENTRY_MODE -> entryMode(trimmed)
            FieldId.CARD_TYPE -> when (trimmed) {
                "1" -> "Debito (1)"
                "2" -> "Credito (2)"
                "3" -> "Altro (3)"
                else -> trimmed
            }
            FieldId.HOST_DATE_TIME -> hostDateTime(trimmed)
            FieldId.HOST_AMOUNT, FieldId.ORIGINAL_AMOUNT, FieldId.PRE_AUTH_AMOUNT, FieldId.POS_TOTAL, FieldId.HOST_TOTAL ->
                cents(trimmed)
            FieldId.DCC_RATE -> rate(trimmed)
            FieldId.DCC_APPLIED -> if (trimmed == "1") "Sì (1)" else "No ($trimmed)"
            FieldId.TERMINAL_DATE_TIME -> terminalDateTime(trimmed)
            FieldId.STATUS -> trimmed.singleOrNull()?.digitToIntOrNull()?.let { statusText(it) } ?: trimmed
            else -> trimmed
        }
    }

    /** Stato del terminale dalla cifra di `s` (tabella Nexi, A§4.6); -1 = non era una cifra. */
    fun statusText(digit: Int): String = when (digit) {
        0 -> "Non configurato (0)"
        1 -> "Configurato, parametri non scaricati (1)"
        2 -> "Operativo (2)"
        3 -> "Non allineato: primo download richiesto (3)"
        4 -> "Chiavi KMPB/KPOS corrotte: primo download richiesto (4)"
        5 -> "Download parametri richiesto dal GT in sospeso (5)"
        6 -> "Aggiornamento software remoto in sospeso (6)"
        -1 -> "sconosciuto (non è una cifra)"
        else -> "sconosciuto ($digit)"
    }

    fun entryMode(raw: String): String = when (EntryMode.fromWire(raw)) {
        EntryMode.ICC -> "Chip (ICC)"
        EntryMode.MAG -> "Banda magnetica (MAG)"
        EntryMode.MANUAL -> "Manuale (MAN)"
        EntryMode.CLESS_MAG -> "Contactless banda (CLM)"
        EntryMode.CLESS_ICC -> "Contactless chip (CLI)"
        null -> raw
    }

    /** DDDHHMM (giorno dell'anno, senza anno, A§4.5.1): "0770947" ⇒ "giorno 077 · 09:47". */
    fun hostDateTime(raw: String): String =
        if (raw.length == 7 && raw.all { it.isDigit() }) "giorno ${raw.take(3)} · ${raw.substring(3, 5)}:${raw.substring(5, 7)}" else raw

    /**
     * Difesa in profondità (M9): il parser consegna già il PAN mascherato, ma la UI non deve mai poterne mostrare
     * uno in chiaro. Tutte le cifre tranne le ultime 4 diventano '*' (stessa forma di maskFixedWidth).
     */
    fun maskPan(raw: String): String {
        val digitsToKeep = 4
        var seenDigitsFromEnd = 0
        val chars = raw.toCharArray()
        for (i in chars.indices.reversed()) {
            if (chars[i].isDigit()) {
                if (seenDigitsFromEnd >= digitsToKeep) chars[i] = '*'
                seenDigitsFromEnd++
            }
        }
        return String(chars)
    }

    private fun cents(raw: String): String =
        raw.toLongOrNull()?.let { "${formatCents(it)} ($raw)" } ?: raw

    /** Tasso DCC: 4 decimali impliciti SEMPRE (A§4.5.1), "00012345" ⇒ "1,2345". */
    private fun rate(raw: String): String =
        raw.toBigDecimalOrNull()?.let { BigDecimal(raw).movePointLeft(4).setScale(4).toPlainString().replace('.', ',') }
            ?.let { "$it ($raw)" } ?: raw

    /** DDMMYYhhmm della risposta `s` (anno 2000+YY). */
    private fun terminalDateTime(raw: String): String =
        if (raw.length == 10 && raw.all { it.isDigit() }) {
            "${raw.substring(0, 2)}/${raw.substring(2, 4)}/20${raw.substring(4, 6)} ${raw.substring(6, 8)}:${raw.substring(8, 10)}"
        } else {
            raw
        }
}
