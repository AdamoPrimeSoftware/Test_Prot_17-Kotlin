package it.prime.protocollo17.ecr17.wire

/**
 * Mascheramento del PAN (A§11 punto 17, invariante M9).
 *
 * Dove si applica: `ProtocolEvent.Wire` RX ⇒ [maskFrame]; `ProtocolEvent.Discarded` ⇒ [maskFragment];
 * righe S con `maskPan = true` ⇒ [maskReceiptPayload] su tutto il payload, prima della conversione del testo;
 * `SimEvent.FrameOut` ⇒ [maskFrame].
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
 * A cavallo dei frame. Prima ogni frame era mascherato da solo: nel formato a colonne senza separatori la DLL taglia
 * lo scontrino in righe S da 200 caratteri anche a metà di un campo (A§9.11), e un PAN tagliato lì (`…438746` |
 * `******9993…`) non contava come una sequenza e restava in chiaro nelle due righe. Il frame con la prima metà esce
 * (evento Wire RX, riga S, log traffico) prima che arrivi il successivo, e non si può trattenere senza rimandare l'ACK
 * o riordinare il log: quindi la coda si maschera per intero quando la riga può continuare ([receiptCarryAfter]), e la
 * sessione passa la lunghezza di quella coda alla riga dopo, che maschera la propria testa come seguito della stessa
 * sequenza (ultime 4 visibili). I frammenti scartati senza STX, tagliati dove capita, mascherano sempre testa e coda.
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

    /** Offset del testo delle righe S (pos 11): le regole dei bordi non toccano mai l'intestazione (TID). */
    private const val TEXT_OFFSET = 10

    /** Codici ASCII delle cifre, confrontati sul byte senza segno. */
    private const val DIGIT_0 = 0x30
    private const val DIGIT_9 = 0x39

    /** Cifre lasciate visibili: le ultime 4, come sullo scontrino. */
    private const val VISIBLE_DIGITS = 4

    /** Sequenza minima di cifre trattata come possibile PAN da [maskDigitRuns] (i PAN hanno 13..19 cifre). */
    private const val MIN_PAN_RUN = 13

    private const val MASK = '*'
    private const val MASK_BYTE = '*'.code.toByte()

    private const val RECEIPT_CODE = 'S'

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
     * - 'S' (riga di scontrino): [maskReceiptPayload] con [receiptCarry];
     * - codici fuori dalle risposte v1 (U, K, altro): maskDigitRuns dalla pos 11 (con lo 0x7F trasparente);
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
    fun maskApplicationPayload(payload: ByteArray, receiptCarry: Int = 0): ByteArray {
        val out = payload.copyOf()
        maskPayloadInPlace(out, receiptCarry = receiptCarry, truncated = false)
        return out
    }

    /**
     * Come sopra ma su un frame STX…ETX LRC completo; l'LRC resta quello originale.
     *
     * Il payload va dallo STX al primo ETX (o alla fine, se l'ETX manca: un frame troncato resta posizionale).
     * ETX e LRC restano identici. Un frame che non inizia con STX (controllo, progress) passa interamente da
     * [maskDigitRuns]: ACK e NAK non cambiano.
     *
     * Senza ETX il frame è tagliato: in una riga S o in un codice sconosciuto la sequenza in coda può continuare nei
     * byte che mancano, e le sue cifre si mascherano tutte. [receiptCarry] vale solo per le righe S.
     *
     * Byte oltre l'LRC (residuo a fine scambio, che può contenere più frame): se ricomincia uno STX è un altro frame,
     * mascherato allo stesso modo e con la coda lasciata dalla riga S prima; altrimenti è un frammento ([maskFragment]
     * senza STX). Prima passavano solo dalla rete di sicurezza: in `<frame S corto>` + `<STX>…PAN 41111111` le 8
     * cifre in coda al frame troncato restavano in chiaro, mentre lo stesso frame da solo le mascherava.
     */
    fun maskFrame(raw: ByteArray, receiptCarry: Int = 0): ByteArray {
        if (raw.isEmpty() || (raw[0].toInt() and 0xFF) != ControlChars.STX) return maskDigitRuns(raw)
        val out = raw.copyOf()
        var start = 0
        var carry = receiptCarry
        while (true) {
            var etx = raw.size
            for (i in start + 1 until raw.size) {
                if ((raw[i].toInt() and 0xFF) == ControlChars.ETX) {
                    etx = i
                    break
                }
            }
            val payload = raw.copyOfRange(start + 1, etx)
            carry = maskPayloadInPlace(payload, receiptCarry = carry, truncated = etx == raw.size)
            payload.copyInto(out, destinationOffset = start + 1)
            val next = etx + 2
            if (next >= raw.size) break
            if ((raw[next].toInt() and 0xFF) != ControlChars.STX) {
                val rest = raw.copyOfRange(next, raw.size)
                maskLooseInPlace(rest)
                rest.copyInto(out, destinationOffset = next)
                break
            }
            start = next
        }
        return out
    }

    /**
     * Byte scartati dallo splitter o rimasti a fine scambio (ProtocolEvent.Discarded): un frammento che inizia con
     * STX è un frame troncato e passa da [maskFrame] (posizionale: PAN in 13/19 mascherato, esito, STAN e online id
     * leggibili), il resto da [maskDigitRuns] più le regole dei bordi.
     *
     * Prima ogni frammento passava dalla rete di sicurezza. Con le '*' nelle sequenze, un esito approvato troncato
     * (`<STX>100029120E00000************1017CLE…`) perdeva anche l'esito "00", che è proprio la prova da leggere per
     * risolvere un esito sconosciuto (M6); e un PAN tagliato dopo il BIN (11 cifre) restava in chiaro.
     *
     * Bordi di un frammento senza STX: è un pezzo del flusso tagliato dove capita, quindi la sequenza in coda (che può
     * continuare nel pezzo dopo) perde tutte le cifre, e quella in testa (che può chiudere un PAN iniziato nel pezzo
     * prima) tutte tranne le ultime 4. Prima `4111111` | `111111111` restavano in chiaro, 7 e 9 cifre sotto la
     * soglia. Anche la sequenza subito prima di un ETX è una coda: lì finiva un frame, che la riga dopo può
     * continuare. Mascherare uno STAN che tocca un bordo è il costo accettato: sono byte scartati, non un esito.
     */
    fun maskFragment(bytes: ByteArray, receiptCarry: Int = 0): ByteArray {
        if (bytes.isNotEmpty() && (bytes[0].toInt() and 0xFF) == ControlChars.STX) return maskFrame(bytes, receiptCarry)
        val out = bytes.copyOf()
        maskLooseInPlace(out)
        return out
    }

    /**
     * Coda da usare quando quella vera non si conosce (connessione riusata, byte scartati prima della riga): con
     * 12 la testa di qualunque sequenza arriva a 13 e perde tutte le cifre tranne le ultime 4, quindi un PAN iniziato
     * in un pezzo che non si è visto non esce mai in chiaro. Il costo è solo sulla testa di quella riga.
     */
    const val UNKNOWN_RECEIPT_CARRY = MIN_PAN_RUN - 1

    /**
     * Riga di scontrino 'S' (payload intero, intestazione compresa): [maskDigitRuns] su tutto il payload, più le due
     * regole dei bordi del testo (dalla pos 11, mai l'intestazione):
     * - **coda**: se la riga può continuare nella successiva (vedi [receiptCarryAfter]), la sequenza di cifre e '*' che
     *   tocca la fine perde TUTTE le cifre: le ultime 4 del pezzo non sono le ultime 4 del PAN;
     * - **testa**: con [carry] > 0 (la coda lasciata aperta dalla riga S precedente), la sequenza che tocca l'inizio
     *   continua quella coda; se insieme fanno almeno 13 caratteri perde tutte le cifre tranne le ultime 4.
     *
     * È la funzione del ramo 'S' di [maskApplicationPayload], esposta perché il parser la applichi qualunque sia la
     * pos 10: grezzo, testo ed evento Wire RX della stessa riga escono con la stessa maschera.
     */
    fun maskReceiptPayload(payload: ByteArray, carry: Int = 0): ByteArray {
        val out = payload.copyOf()
        maskReceiptInPlace(out, carry = carry, truncated = false)
        return out
    }

    /**
     * Lunghezza della sequenza (cifre e '*', 0x7F esclusi) che la riga S [payload] lascia aperta alla riga dopo, da
     * passare come `carry` alla riga S successiva; 0 se la riga non può continuare. [carry] è quella ricevuta dalla
     * riga precedente: conta solo se tutto il testo è una sequenza.
     *
     * Una riga può continuare se il suo testo è pieno (200 caratteri, [ControlChars.RECEIPT_TEXT_MAX]: il terminale
     * ha tagliato il flusso per dimensione) e non è fatto solo di righe intere (testo che inizia con un separatore
     * 0x7D/0x7E, almeno due separatori, righe tutte larghe uguali, l'ultima compresa): è il formato a righe da 24
     * colonne, dove il frame finisce sempre a fine riga e il PAN non scavalca mai una riga. Così le righe una per frame (simulatore,
     * spec Nexi), più corte del massimo, e il formato a righe non perdono le ultime 4 cifre di una sequenza in coda;
     * il prezzo resta sul formato a colonne, dove una cifra al taglio sparisce (`Data 08` | `/07/25` ⇒ `Data **`).
     */
    fun receiptCarryAfter(payload: ByteArray, carry: Int = 0): Int =
        maskReceiptInPlace(payload.copyOf(), carry = carry, truncated = false)

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
     * Smistamento per codice in pos 10, sul posto. [truncated]: payload senza ETX (frame o frammento tagliato).
     * Restituisce la coda per la riga S successiva: quella della riga, se è una riga S; altrimenti [receiptCarry],
     * perché un frame di altro tipo in mezzo (un esito, un orfano) non interrompe lo scontrino.
     */
    private fun maskPayloadInPlace(out: ByteArray, receiptCarry: Int, truncated: Boolean): Int {
        val code = if (out.size > CODE_OFFSET) (out[CODE_OFFSET].toInt() and 0xFF).toChar() else null
        when {
            code == 'i' -> maskPanField(out)
            code in PAN_IF_NOT_KO -> if (!isKo(out)) maskPanField(out)
            code in NO_PAN -> Unit
            code == RECEIPT_CODE -> return maskReceiptInPlace(out, carry = receiptCarry, truncated = truncated)
            // U, K, codici sconosciuti o numerici, payload corti
            else -> {
                maskDigitRunsInPlace(out, from = 0)
                if (truncated) maskTailRun(out, kept = keptIndices(out, from = 0))
            }
        }
        return receiptCarry
    }

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

    // ─── Bordi: sequenze tagliate tra un pezzo e l'altro ─────────────────────

    /**
     * Riga S sul posto: rete di sicurezza, poi coda e testa del testo (vedi [maskReceiptPayload]). Restituisce la coda
     * lasciata aperta alla riga dopo (vedi [receiptCarryAfter]). Con [truncated] (frame senza ETX) la riga continua
     * sempre: i byte che mancano possono essere il resto del PAN.
     */
    private fun maskReceiptInPlace(out: ByteArray, carry: Int, truncated: Boolean): Int {
        maskDigitRunsInPlace(out, from = 0)
        val kept = keptIndices(out, from = TEXT_OFFSET)
        val head = headRun(out, kept)
        val tail = tailRun(out, kept)
        val whole = head == kept.size
        val open = tail > 0 && (truncated || mayContinue(out))
        if (open) maskAllDigits(out, kept = kept, from = kept.size - tail, to = kept.size)
        // Testa e coda sono la stessa sequenza: se la riga resta aperta la coda l'ha già mascherata tutta.
        if (carry > 0 && head > 0 && carry + head >= MIN_PAN_RUN && !(open && whole)) {
            maskAllButLast(out, kept = kept, from = 0, to = head)
        }
        return when {
            !open -> 0
            whole -> carry + tail
            else -> tail
        }
    }

    /** Testo pieno e non chiuso da una riga di stampa intera: il terminale ha tagliato il flusso per dimensione. */
    private fun mayContinue(payload: ByteArray): Boolean =
        payload.size - TEXT_OFFSET >= ControlChars.RECEIPT_TEXT_MAX && !endsWithCompleteLine(payload)

    /**
     * true se il testo (dalla pos 11) è fatto solo di righe intere: inizia con un separatore 0x7D/0x7E, ne ha almeno
     * due, e tutte le righe hanno la stessa larghezza (0x7F escluso), l'ultima compresa. È il formato a righe da 24
     * colonne, 8 righe da 1 + 24 per frame. Prima bastavano due righe uguali dopo un separatore qualsiasi, e un testo
     * come `A…A}` + 12 B + `}CARTA 411111` (righe accorciate, larghe uguali per caso) passava per intero e lasciava il
     * PAN della riga dopo in chiaro: con il testo che inizia con una riga a metà (il seguito del frame prima) o con
     * righe di larghezze diverse il frame non prova nulla.
     */
    private fun endsWithCompleteLine(payload: ByteArray): Boolean {
        var separators = 0
        var width = -1
        var current = -1   // -1 = prima del primo separatore
        for (i in TEXT_OFFSET until payload.size) {
            val b = payload[i].toInt() and 0xFF
            when {
                b == ControlChars.RECEIPT_NEWLINE || b == ControlChars.RECEIPT_DOUBLE_ALT -> {
                    if (current >= 0) {
                        if (width >= 0 && width != current) return false
                        width = current
                    }
                    separators++
                    current = 0
                }
                isReceiptFormat(b) -> Unit
                current < 0 -> return false   // testo prima del primo separatore: il seguito di una riga
                else -> current++
            }
        }
        return separators >= 2 && current == width
    }

    /** Rete di sicurezza e bordi di un pezzo senza STX, sul posto (frammenti e byte oltre l'LRC di un frame). */
    private fun maskLooseInPlace(out: ByteArray) {
        maskDigitRunsInPlace(out, from = 0)
        maskCutEdges(out)
    }

    /**
     * Pezzo senza STX sul posto: una sequenza che finisce il pezzo, o che finisce subito prima di un ETX (fine di un
     * frame), perde tutte le cifre; quella in testa tutte tranne le ultime 4; una sequenza che è insieme testa e coda
     * le perde tutte.
     */
    private fun maskCutEdges(out: ByteArray) {
        val kept = keptIndices(out, from = 0)
        var k = 0
        while (k < kept.size) {
            if (!isPanChar(out[kept[k]])) {
                k++
                continue
            }
            val start = k
            while (k < kept.size && isPanChar(out[kept[k]])) k++
            val endsPiece = k == kept.size || (out[kept[k]].toInt() and 0xFF) == ControlChars.ETX
            when {
                endsPiece -> maskAllDigits(out, kept = kept, from = start, to = k)
                start == 0 -> maskAllButLast(out, kept = kept, from = 0, to = k)
            }
        }
    }

    private fun maskTailRun(out: ByteArray, kept: IntArray) {
        val tail = tailRun(out, kept)
        maskAllDigits(out, kept = kept, from = kept.size - tail, to = kept.size)
    }

    /** Indici da [from] in poi senza i byte di formato (0x7F): la proiezione su cui si contano le sequenze. */
    private fun keptIndices(bytes: ByteArray, from: Int): IntArray {
        val kept = IntArray((bytes.size - from).coerceAtLeast(0))
        var count = 0
        for (i in from until bytes.size) if (!isReceiptFormat(bytes[i].toInt() and 0xFF)) kept[count++] = i
        return kept.copyOf(count)
    }

    private fun isPanChar(b: Byte): Boolean = isAsciiDigit(b) || b == MASK_BYTE

    /** Caratteri candidati PAN (cifre e '*') all'inizio della proiezione [kept]. */
    private fun headRun(bytes: ByteArray, kept: IntArray): Int {
        var n = 0
        while (n < kept.size && isPanChar(bytes[kept[n]])) n++
        return n
    }

    /** Caratteri candidati PAN (cifre e '*') alla fine della proiezione [kept]. */
    private fun tailRun(bytes: ByteArray, kept: IntArray): Int {
        var n = 0
        while (n < kept.size && isPanChar(bytes[kept[kept.size - 1 - n]])) n++
        return n
    }

    /** Tutte le cifre di kept[from, to). */
    private fun maskAllDigits(bytes: ByteArray, kept: IntArray, from: Int, to: Int) {
        for (k in from until to) if (isAsciiDigit(bytes[kept[k]])) bytes[kept[k]] = MASK_BYTE
    }

    /** Le cifre di kept[from, to) tranne le ultime 4. */
    private fun maskAllButLast(bytes: ByteArray, kept: IntArray, from: Int, to: Int) {
        maskDigitsExceptLast(
            size = to - from,
            isDigit = { isAsciiDigit(bytes[kept[from + it]]) },
            mask = { bytes[kept[from + it]] = MASK_BYTE },
        )
    }
}
