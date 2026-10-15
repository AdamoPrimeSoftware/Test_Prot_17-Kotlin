package it.prime.protocollo17.ecr17.message

import it.prime.protocollo17.ecr17.error.Ecr17ValidationException
import it.prime.protocollo17.ecr17.error.RequestField
import it.prime.protocollo17.ecr17.error.ValidationError

// Helper interni su byte per i campi a larghezza fissa del Protocollo 17 (§3.11).
//
// Perché non String.padStart: non segnala l'overflow (un valore troppo lungo passa intero e sposta tutti i
// campi successivi) e conta unità UTF-16, non byte (A§3.4). Qui ogni larghezza è in byte ASCII, un valore
// troppo lungo è sempre un errore e mai un troncamento (A§4.4), e il testo in uscita è solo 0x20..0x7E.

/** Primo e ultimo carattere ASCII stampabile: l'unico intervallo che può finire in un campo di richiesta. */
private const val PRINTABLE_FIRST = 0x20
private const val PRINTABLE_LAST = 0x7E

/** Larghezza del testo scontrino nelle richieste P/X/p/i/c (A§4.3.1-4.3.2). */
internal const val RECEIPT_TEXT_WIDTH = 128

/** Importo massimo rappresentabile in 8 cifre (centesimi). */
internal const val MAX_AMOUNT_CENTS = 99_999_999L

/** Larghezza del campo importo. */
internal const val AMOUNT_WIDTH = 8

private fun Char.isPrintableAscii(): Boolean = code in PRINTABLE_FIRST..PRINTABLE_LAST

private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

/**
 * Scrittore dei campi di una richiesta. Raccoglie TUTTI gli errori di validazione invece di fermarsi al primo:
 * la UI li mostra insieme (§3.12) e `RequestEncoder.validate` li restituisce senza costruire eccezioni.
 *
 * Un campo non valido viene comunque scritto con il riempitivo alla sua larghezza, così il layout resta coerente
 * e i campi successivi si validano normalmente; [toByteArray] poi rifiuta il risultato.
 *
 * [capacity] è la lunghezza ESATTA del payload del comando: [toByteArray] verifica che il layout l'abbia
 * riempita, perché un campo dimenticato o di larghezza sbagliata è un bug del builder, non un input errato.
 */
internal class FieldWriter(private val capacity: Int) {
    private var buffer = ByteArray(capacity)
    private var size = 0
    private val collected = mutableListOf<ValidationError>()

    /** Errori raccolti finora, nell'ordine dei campi del layout. */
    val errors: List<ValidationError> get() = collected.toList()

    /** Un carattere di layout: solo ASCII 0x20..0x7E, altrimenti è un bug del builder (IllegalArgumentException). */
    fun char(c: Char) {
        require(c.isPrintableAscii()) { "ECR17: carattere di layout non ASCII stampabile: 0x%04X".format(c.code) }
        put(c.code)
    }

    /** Costanti di layout ("00", "0000000"…): stesse regole di [char]. */
    fun literal(s: String) {
        for (c in s) char(c)
    }

    /**
     * Allineato a destra, riempito a sinistra con [pad]. Overflow ⇒ ValidationError.FieldOverflow; carattere fuori
     * da 0x20..0x7E ⇒ ValidationError.NonAscii con l'indice del primo carattere non valido.
     */
    fun padStart(field: RequestField, value: String, width: Int, pad: Char) {
        require(pad.isPrintableAscii()) { "ECR17: riempitivo non ASCII stampabile" }
        var valid = true
        val badIndex = value.indexOfFirst { !it.isPrintableAscii() }
        if (badIndex >= 0) {
            collected += ValidationError.NonAscii(field, badIndex)
            valid = false
        }
        // Con caratteri non ASCII la lunghezza in byte non è definita: si contano i code point, così un emoji
        // (2 unità UTF-16) non produce un overflow fittizio oltre all'errore NonAscii già segnalato.
        if (value.codePointCount(0, value.length) > width) {
            collected += ValidationError.FieldOverflow(field, value, width)
            valid = false
        }
        if (!valid) {
            repeat(width) { put(pad.code) }
            return
        }
        repeat(width - value.length) { put(pad.code) }
        for (c in value) put(c.code)
    }

    /** Solo cifre 0-9, left-pad '0'. ⇒ NotDigits / FieldOverflow (anche entrambi). */
    fun digits(field: RequestField, value: String, width: Int) {
        var valid = true
        if (!value.all { it.isAsciiDigit() }) {
            collected += ValidationError.NotDigits(field, value)
            valid = false
        }
        if (value.codePointCount(0, value.length) > width) {
            collected += ValidationError.FieldOverflow(field, value, width)
            valid = false
        }
        if (!valid) {
            repeat(width) { put('0'.code) }
            return
        }
        repeat(width - value.length) { put('0'.code) }
        for (c in value) put(c.code)
    }

    /**
     * Campo obbligatorio mancante: registra ValidationError.Required e scrive [width] riempitivi, così il layout
     * resta della lunghezza giusta. È il chiamante a decidere che cosa significa "mancante" per quel campo.
     */
    fun required(field: RequestField, width: Int, pad: Char = '0') {
        collected += ValidationError.Required(field)
        repeat(width) { put(pad.code) }
    }

    /** 8 cifre in centesimi: < 0 ⇒ NegativeAmount; > 99_999_999 ⇒ FieldOverflow. Lo 0 è accettato (A§4.3.1). */
    fun amount(cents: Long) {
        when {
            cents < 0 -> {
                collected += ValidationError.NegativeAmount(RequestField.AMOUNT)
                repeat(AMOUNT_WIDTH) { put('0'.code) }
            }
            cents > MAX_AMOUNT_CENTS -> {
                collected += ValidationError.FieldOverflow(RequestField.AMOUNT, cents.toString(), width = AMOUNT_WIDTH)
                repeat(AMOUNT_WIDTH) { put('0'.code) }
            }
            else -> digits(RequestField.AMOUNT, cents.toString(), width = AMOUNT_WIDTH)
        }
    }

    /**
     * Testo libero dello scontrino: solo 0x20..0x7E (NonAscii con indice), larghezza 128, ALLINEATO A DESTRA.
     * L'allineamento a destra è della spec ("right-justified, space-filled on the left", A§4.3.1): il terminale
     * stampa solo i caratteri validi. Allinearlo a sinistra come farebbe istintivamente un padEnd è un bug noto.
     */
    fun receiptText(value: String) {
        padStart(RequestField.RECEIPT_TEXT, value, width = RECEIPT_TEXT_WIDTH, pad = ' ')
    }

    /**
     * Payload finale. Lancia Ecr17ValidationException se ci sono errori di validazione; IllegalStateException se il
     * layout non ha riempito esattamente [capacity] byte (bug del builder).
     */
    fun toByteArray(): ByteArray {
        if (collected.isNotEmpty()) throw Ecr17ValidationException(errors)
        check(size == capacity) { "ECR17: layout di $size byte invece di $capacity" }
        return buffer.copyOf(size)
    }

    private fun put(code: Int) {
        if (size == buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, 16))
        buffer[size++] = code.toByte()
    }
}

/**
 * Lettore dei campi di una risposta, con posizioni 1-based come la spec (pos 10 = codice messaggio).
 *
 * Tollerante per costruzione: un payload corto o troncato non lancia mai, i campi mancanti tornano vuoti e il
 * chiamante decide (di norma `null` nei campi opzionali). Decodifica ISO-8859-1 (1 byte = 1 char) e mai UTF-8:
 * un byte ≥ 0x80 decodificato in UTF-8 diventa U+FFFD e sposta di +2 tutti gli offset successivi (A§8 D6).
 */
internal class FieldReader(private val payload: ByteArray) {
    /** Lunghezza del payload in byte. */
    val length: Int get() = payload.size

    /** 1-based, tollerante: "" se pos1 < 1 o oltre la fine; lunghezza troncata al disponibile. ISO-8859-1. */
    fun at(pos1: Int, len: Int): String {
        if (pos1 < 1 || len <= 0 || pos1 > payload.size) return ""
        val from = pos1 - 1
        val count = minOf(len, payload.size - from)
        return String(payload, from, count, Charsets.ISO_8859_1)
    }

    /** Come [at] senza gli spazi a destra (i campi di testo sono allineati a sinistra e riempiti di spazi). */
    fun trimmedAt(pos1: Int, len: Int): String = at(pos1, len).trimEnd(' ')

    /** Carattere alla posizione 1-based, null se fuori dal payload. */
    fun charAt(pos1: Int): Char? =
        if (pos1 < 1 || pos1 > payload.size) null else (payload[pos1 - 1].toInt() and 0xFF).toChar()

    /** Dal pos1 alla fine ("" se oltre la fine). */
    fun restFrom(pos1: Int): String {
        if (pos1 < 1 || pos1 > payload.size) return ""
        return String(payload, pos1 - 1, payload.size - (pos1 - 1), Charsets.ISO_8859_1)
    }
}
