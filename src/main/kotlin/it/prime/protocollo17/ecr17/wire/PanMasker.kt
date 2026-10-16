package it.prime.protocollo17.ecr17.wire

/**
 * Mascheramento del PAN (A§11 punto 17, invariante M9).
 *
 * Dove si applica: `ProtocolEvent.Wire` RX ⇒ [maskFrame]; `ProtocolEvent.Discarded` ⇒ [maskFragment];
 * righe S con `maskPan = true` ⇒ [maskDigitRuns] su raw e corpo; `SimEvent.FrameOut` ⇒ [maskFrame].
 *
 * In tutta la rete di sicurezza lo 0x7F (doppia altezza delle righe S) è trasparente: il terminale può inserirlo in
 * mezzo al PAN, e `41111111<7F>11111111` non deve contare come due sequenze da 8 cifre, sotto la soglia. Lo 0x7E invece
 * no, come lo 0x7D: nel formato a righe da 24 colonne dei terminali Ingenico del 2025 è il separatore della riga in
 * doppia altezza, e un PAN non scavalca mai una riga. Trattarlo come trasparente univa righe vicine e mascherava STAN
 * e acquirer, o spostava le "ultime 4" del PAN su cifre della riga dopo.
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
     * Un payload troncato maschera TUTTE le cifre della parte del campo PAN che c'è: le ultime 4 del pezzo non sono le
     * ultime 4 del PAN, e prima un frammento come `…E0000438746123` lasciava in chiaro cifre centrali. Un esito mancante
     * (payload < 12 byte) conta come "diverso da 01", perché nel dubbio si maschera.
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
     * Byte scartati dallo splitter o rimasti a fine scambio (ProtocolEvent.Discarded): un frammento che inizia con
     * STX è un frame troncato e passa da [maskFrame] (posizionale: PAN in 13/19 mascherato, esito, STAN e online id
     * leggibili), il resto da [maskDigitRuns].
     *
     * Prima ogni frammento passava dalla rete di sicurezza. Con le '*' nelle sequenze, un esito approvato troncato
     * (`<STX>100029120E00000************1017CLE…`) perdeva anche l'esito "00", che è proprio la prova da leggere per
     * risolvere un esito sconosciuto (M6); e un PAN tagliato dopo il BIN (11 cifre) restava in chiaro.
     */
    fun maskFragment(bytes: ByteArray): ByteArray =
        if (bytes.isNotEmpty() && (bytes[0].toInt() and 0xFF) == ControlChars.STX) maskFrame(bytes) else maskDigitRuns(bytes)

    /**
     * Rete di sicurezza senza posizioni: ogni sequenza di ≥ 13 caratteri fatta di cifre ASCII e '*' (con almeno una
     * cifra) diventa '*' tranne le ultime 4 cifre. Per byte scartati (ProtocolEvent.Discarded), testo delle righe S,
     * byte non applicativi del log del simulatore. Può mascherare anche STAN/online in un frammento scartato: accettato.
     *
     * Le '*' contano perché i terminali Ingenico stampano sullo scontrino il PAN già troncato, con il BIN in chiaro
     * (`438746******9993`, catture PosMock): prima si contavano solo le cifre, quel BIN restava in chiaro nelle righe S
     * e nel log traffico, mentre sull'esito 'E' lo stesso PAN usciva come `***************9993`. Una sequenza di sole
     * '*' non ha cifre da mascherare e resta com'è.
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
            isDouble = { isReceiptFormat(chars[it].code) },
            isDigit = { chars[it].isAsciiDigit() },
            isMask = { chars[it] == MASK },
            mask = { chars[it] = MASK },
        )
        return String(chars)
    }

    // ─── Dettagli ────────────────────────────────────────────────────────────

    /** Solo '0'..'9': `Char.isDigit()` accetterebbe anche cifre Unicode che sul filo ASCII non esistono. */
    private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

    private fun isAsciiDigit(b: Byte): Boolean = (b.toInt() and 0xFF) in DIGIT_0..DIGIT_9

    /** Byte di formato trasparente nel conteggio delle cifre: solo lo 0x7F (0x7E separa le righe, vedi sopra). */
    private fun isReceiptFormat(code: Int): Boolean = code == ControlChars.RECEIPT_DOUBLE

    /** Esito "01" in pos 11-12 = ramo KO: lì in pos 13 c'è la descrizione dell'errore, non il PAN. */
    private fun isKo(payload: ByteArray): Boolean =
        payload.size >= RESULT_OFFSET + 2 &&
            (payload[RESULT_OFFSET].toInt() and 0xFF) == '0'.code &&
            (payload[RESULT_OFFSET + 1].toInt() and 0xFF) == '1'.code

    /**
     * maskFixedWidth sul campo PAN (pos 13 len 19). Se il payload è troncato dentro il campo, tutte le cifre presenti:
     * le ultime 4 del pezzo non sono quelle del PAN.
     */
    private fun maskPanField(payload: ByteArray) {
        val from = PAN_OFFSET
        val to = minOf(payload.size, PAN_OFFSET + PAN_LENGTH)
        if (from >= to) return
        if (to < PAN_OFFSET + PAN_LENGTH) {
            for (i in from until to) if (isAsciiDigit(payload[i])) payload[i] = MASK_BYTE
            return
        }
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
            isDouble = { isReceiptFormat(bytes[it].toInt() and 0xFF) },
            isDigit = { isAsciiDigit(bytes[it]) },
            isMask = { bytes[it] == MASK_BYTE },
            mask = { bytes[it] = MASK_BYTE },
        )
    }

    /**
     * Sequenze di ≥ [MIN_PAN_RUN] caratteri tra cifre e '*' da [from] in poi, con i byte di formato ([isDouble])
     * trasparenti: si contano sulla proiezione senza di loro (indici in `kept`) e in ogni sequenza si mascherano tutte
     * le cifre tranne le ultime 4. Lunghezza e posizione dei byte di formato restano quelle del filo.
     */
    private inline fun maskRunsSkippingDouble(
        size: Int,
        from: Int,
        isDouble: (Int) -> Boolean,
        isDigit: (Int) -> Boolean,
        isMask: (Int) -> Boolean,
        mask: (Int) -> Unit,
    ) {
        val kept = IntArray((size - from).coerceAtLeast(0))
        var count = 0
        for (i in from until size) if (!isDouble(i)) kept[count++] = i
        forEachPanRun(size = count, isPanChar = { isDigit(kept[it]) || isMask(kept[it]) }) { runStart, runEnd ->
            maskDigitsExceptLast(
                size = runEnd - runStart,
                isDigit = { isDigit(kept[runStart + it]) },
                mask = { mask(kept[runStart + it]) },
            )
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

    /** Chiama [onRun] per ogni sequenza [runStart, runEnd) di almeno [MIN_PAN_RUN] caratteri [isPanChar]. */
    private inline fun forEachPanRun(size: Int, isPanChar: (Int) -> Boolean, onRun: (Int, Int) -> Unit) {
        var i = 0
        while (i < size) {
            if (!isPanChar(i)) {
                i++
                continue
            }
            val runStart = i
            while (i < size && isPanChar(i)) i++
            if (i - runStart >= MIN_PAN_RUN) onRun(runStart, i)
        }
    }
}
