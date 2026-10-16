package it.prime.protocollo17.ecr17.wire

/**
 * Mascheramento del PAN (A§11 punto 17, invariante M9).
 *
 * Dove si applica: `ProtocolEvent.Wire` RX ⇒ [maskFrame]; `ProtocolEvent.Discarded` ⇒ [maskDigitRuns];
 * righe S con `maskPan = true` ⇒ [maskDigitRuns] su raw e text; `SimEvent.FrameOut` ⇒ [maskFrame].
 *
 * In tutta la rete di sicurezza lo 0x7F (doppia altezza delle righe S) è trasparente: il terminale può inserirlo in
 * mezzo al PAN, e `41111111<7F>11111111` non deve contare come due sequenze da 8 cifre, sotto la soglia.
 *
 * Perché due strategie: sugli esiti la posizione del PAN è nota (pos 13, len 19) e mascherare solo lì lascia
 * leggibili acquirer, STAN e online id, che sono proprio le sequenze di cifre lunghe da confrontare con lo
 * scontrino. Dove la posizione non è nota (righe di scontrino, frammenti scartati, codici inattesi) si usa la
 * rete di sicurezza [maskDigitRuns]. Il terminale Ingenico maschera già il PAN, ma altri (Nexi, A§9.9) no.
 *
 * Tutte le funzioni restituiscono una copia e conservano la lunghezza: posizioni e offset restano validi.
 */
object PanMasker {

    /** Offset 0-based (pos 13) e lunghezza del PAN negli esiti E V e i c v. */
    private const val PAN_OFFSET = 12
    private const val PAN_LENGTH = 19

    /** Offset del codice messaggio (pos 10) e dell'esito (pos 11-12). */
    private const val CODE_OFFSET = 9
    private const val RESULT_OFFSET = 10

    /** Codici ASCII delle cifre, confrontati sul byte senza segno. */
    private const val DIGIT_0 = 0x30
    private const val DIGIT_9 = 0x39

    /** Cifre lasciate visibili: le ultime 4, come sullo scontrino. */
    private const val VISIBLE_DIGITS = 4

    /** Sequenza minima di cifre trattata come possibile PAN da [maskDigitRuns] (i PAN hanno 13..19 cifre). */
    private const val MIN_PAN_RUN = 13

    private const val MASK = '*'
    private const val MASK_BYTE = '*'.code.toByte()

    /** Esiti con il PAN in pos 13/19 solo nel ramo diverso da KO ("01"). */
    private val PAN_IF_NOT_KO = setOf('E', 'V', 'e', 'c', 'v')

    /** Risposte senza PAN: le loro lunghe sequenze di cifre (totali, data/ora, release) vanno lasciate intatte. */
    private val NO_PAN = setOf('s', 'T', 'C')

    /** Vista UI (semantica rust maskPan): solo cifre, ultime 4 visibili; ≤ 4 cifre ⇒ "****". */
    fun maskForDisplay(pan: String): String {
        val digits = pan.filter { it.isAsciiDigit() }
        if (digits.length <= VISIBLE_DIGITS) return MASK.toString().repeat(VISIBLE_DIGITS)
        return MASK.toString().repeat(digits.length - VISIBLE_DIGITS) + digits.takeLast(VISIBLE_DIGITS)
    }

    /**
     * Larghezza fissa: ogni cifra tranne le ultime 4 diventa '*', gli altri caratteri restano.
     *
     * Con 4 cifre o meno non cambia nulla: non c'è niente oltre le 4 cifre che si possono comunque mostrare.
     */
    fun maskFixedWidth(field: String): String {
        val chars = field.toCharArray()
        maskDigitsExceptLast(
            size = chars.size,
            isDigit = { chars[it].isAsciiDigit() },
            mask = { chars[it] = MASK },
        )
        return String(chars)
    }

    /**
     * Copia del payload applicativo mascherato secondo il codice in pos 10:
     * - 'i': PAN (pos 13 len 19) SEMPRE mascherato (la risposta 75 B non ha ramo KO, A§4.5.4);
     * - E, V, e, c, v: PAN (pos 13 len 19) mascherato se l'esito (pos 11-12) è diverso da "01";
     * - 'S' (riga di scontrino) e codici fuori dalle risposte v1 (U, K, altro): maskDigitRuns dalla pos 11 (con lo
     *   0x7F trasparente);
     * - s, T, C: copia identica (nessun PAN; contengono lunghe sequenze di cifre che NON vanno toccate).
     *
     * Un payload troncato maschera la parte del campo PAN che c'è; un esito mancante (payload < 12 byte) conta
     * come "diverso da 01", perché nel dubbio si maschera.
     *
     * "Dalla pos 11" in pratica: le sequenze si contano su TUTTO il payload. Con un'intestazione ben formata il
     * risultato è identico, perché il codice in pos 10 non è una cifra e interrompe la sequenza, e TID + '0'
     * sono solo 9 cifre (sotto la soglia di 13): il TID non viene mai toccato. Cambia solo quando la pos 10 è
     * una cifra (intestazione spostata, frammento di esito dopo uno STX spurio): prima una sequenza iniziata
     * prima della pos 11 veniva contata solo dalla pos 11 e un PAN poteva restare in chiaro (M9).
     */
    fun maskApplicationPayload(payload: ByteArray): ByteArray {
        val out = payload.copyOf()
        val code = if (out.size > CODE_OFFSET) (out[CODE_OFFSET].toInt() and 0xFF).toChar() else null
        when {
            code == 'i' -> maskPanField(out)
            code in PAN_IF_NOT_KO -> if (!isKo(out)) maskPanField(out)
            code in NO_PAN -> Unit
            // 'S', U, K, codici sconosciuti o numerici, payload corti
            else -> maskDigitRunsInPlace(out, from = 0)
        }
        return out
    }

    /**
     * Come sopra ma su un frame STX…ETX LRC completo; l'LRC resta quello originale.
     *
     * Il payload va dallo STX al primo ETX (o alla fine, se l'ETX manca: un frame troncato resta posizionale).
     * ETX e LRC restano identici; eventuali byte oltre l'LRC passano dalla rete di sicurezza. Un frame che non
     * inizia con STX (controllo, progress) passa interamente da [maskDigitRuns]: ACK e NAK non cambiano.
     */
    fun maskFrame(raw: ByteArray): ByteArray {
        if (raw.isEmpty() || (raw[0].toInt() and 0xFF) != ControlChars.STX) return maskDigitRuns(raw)
        var etx = raw.size
        for (i in 1 until raw.size) {
            if ((raw[i].toInt() and 0xFF) == ControlChars.ETX) {
                etx = i
                break
            }
        }
        val out = raw.copyOf()
        maskApplicationPayload(raw.copyOfRange(1, etx)).copyInto(out, destinationOffset = 1)
        val afterLrc = etx + 2
        if (afterLrc < out.size) maskDigitRunsInPlace(out, from = afterLrc)
        return out
    }

    /**
     * Rete di sicurezza senza posizioni: ogni sequenza di ≥ 13 cifre ASCII consecutive diventa '*' tranne le
     * ultime 4. Per byte scartati (ProtocolEvent.Discarded), testo delle righe S, byte non applicativi del
     * log del simulatore. Può mascherare anche STAN/online in un frammento scartato: accettato.
     *
     * Lo 0x7F è trasparente: le sequenze si contano come se non ci fosse e resta al suo posto. Prima solo il ramo S
     * di [maskApplicationPayload] lo saltava, e un frammento scartato di una riga S con `41111111<7F>11111111`
     * finiva in chiaro nel log traffico e nel log del simulatore (M9). I due overload danno lo stesso risultato.
     */
    fun maskDigitRuns(bytes: ByteArray): ByteArray {
        val out = bytes.copyOf()
        maskDigitRunsInPlace(out, from = 0)
        return out
    }

    fun maskDigitRuns(text: String): String {
        val chars = text.toCharArray()
        maskRunsSkippingDouble(
            size = chars.size,
            from = 0,
            isDouble = { chars[it].code == ControlChars.RECEIPT_DOUBLE },
            isDigit = { chars[it].isAsciiDigit() },
            mask = { chars[it] = MASK },
        )
        return String(chars)
    }

    // ─── Dettagli ────────────────────────────────────────────────────────────

    /** Solo '0'..'9': `Char.isDigit()` accetterebbe anche cifre Unicode che sul filo ASCII non esistono. */
    private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

    private fun isAsciiDigit(b: Byte): Boolean = (b.toInt() and 0xFF) in DIGIT_0..DIGIT_9

    /** Esito "01" in pos 11-12 = ramo KO: lì in pos 13 c'è la descrizione dell'errore, non il PAN. */
    private fun isKo(payload: ByteArray): Boolean =
        payload.size >= RESULT_OFFSET + 2 &&
            (payload[RESULT_OFFSET].toInt() and 0xFF) == '0'.code &&
            (payload[RESULT_OFFSET + 1].toInt() and 0xFF) == '1'.code

    /** maskFixedWidth sul campo PAN (pos 13 len 19), sulla parte presente se il payload è troncato. */
    private fun maskPanField(payload: ByteArray) {
        val from = PAN_OFFSET
        val to = minOf(payload.size, PAN_OFFSET + PAN_LENGTH)
        if (from >= to) return
        maskDigitsExceptLast(
            size = to - from,
            isDigit = { isAsciiDigit(payload[from + it]) },
            mask = { payload[from + it] = MASK_BYTE },
        )
    }

    private fun maskDigitRunsInPlace(bytes: ByteArray, from: Int) {
        maskRunsSkippingDouble(
            size = bytes.size,
            from = from,
            isDouble = { (bytes[it].toInt() and 0xFF) == ControlChars.RECEIPT_DOUBLE },
            isDigit = { isAsciiDigit(bytes[it]) },
            mask = { bytes[it] = MASK_BYTE },
        )
    }

    /**
     * Sequenze di ≥ [MIN_PAN_RUN] cifre da [from] in poi, con lo 0x7F ([isDouble]) trasparente: si contano sulla
     * proiezione senza 0x7F (indici in `kept`) e le cifre mascherate restano al loro posto. Lunghezza e posizione
     * degli 0x7F restano quelle del filo.
     */
    private inline fun maskRunsSkippingDouble(
        size: Int,
        from: Int,
        isDouble: (Int) -> Boolean,
        isDigit: (Int) -> Boolean,
        mask: (Int) -> Unit,
    ) {
        val kept = IntArray((size - from).coerceAtLeast(0))
        var count = 0
        for (i in from until size) if (!isDouble(i)) kept[count++] = i
        forEachDigitRun(size = count, from = 0, isDigit = { isDigit(kept[it]) }) { runStart, runEnd ->
            for (k in runStart until runEnd - VISIBLE_DIGITS) mask(kept[k])
        }
    }

    /** Ogni cifra tranne le ultime [VISIBLE_DIGITS] (contate tra le sole cifre) viene mascherata. */
    private inline fun maskDigitsExceptLast(size: Int, isDigit: (Int) -> Boolean, mask: (Int) -> Unit) {
        var digits = 0
        for (i in 0 until size) if (isDigit(i)) digits++
        var toMask = digits - VISIBLE_DIGITS
        var i = 0
        while (toMask > 0 && i < size) {
            if (isDigit(i)) {
                mask(i)
                toMask--
            }
            i++
        }
    }

    /** Chiama [onRun] per ogni sequenza [runStart, runEnd) di almeno [MIN_PAN_RUN] cifre, a partire da [from]. */
    private inline fun forEachDigitRun(size: Int, from: Int, isDigit: (Int) -> Boolean, onRun: (Int, Int) -> Unit) {
        var i = from
        while (i < size) {
            if (!isDigit(i)) {
                i++
                continue
            }
            val runStart = i
            while (i < size && isDigit(i)) i++
            if (i - runStart >= MIN_PAN_RUN) onRun(runStart, i)
        }
    }
}
