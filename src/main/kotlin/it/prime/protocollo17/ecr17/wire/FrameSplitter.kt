package it.prime.protocollo17.ecr17.wire

import it.prime.protocollo17.ecr17.profile.ControlLrcRxPolicy

/**
 * Splitter di stream **puro e con stato** (buffer interno), alimentato con chunk qualsiasi (A§8 D1, L§13.3).
 *
 * Non conosce il comando inviato: la classificazione esito/orfano/scontrino è della sessione. Dare i byte
 * in un solo chunk o uno alla volta deve produrre la stessa sequenza di elementi.
 *
 * Prima (repo padosoft) i due splitter sbagliavano in direzioni opposte: rust in `stx` scartava l'ACK reale
 * `06 03 7A` dell'Ingenico (LRC confrontato solo con la convenzione applicativa) e faceva ritrasmettere un
 * pagamento; C++ prendeva qualsiasi terna `06 xx xx`, e un ACK nudo seguito da un esito si mangiava i primi
 * due byte dell'esito. Ora (§3.7):
 * - l'ETX è sempre obbligatorio e l'LRC di un ACK si confronta con entrambe le convenzioni ([ControlLrcRule]);
 * - `01`/`02` non sono mai un LRC valido di un controllo, quindi dopo `06`/`15` o `06 03`/`15 03` sono l'inizio
 *   del frame successivo e non vengono consumati;
 * - asimmetria voluta (U3): un ACK dubbio è innocuo e viene accettato con un match esplicito (`MISMATCH`/`BARE`),
 *   un NAK dubbio provocherebbe un reinvio e viene SEMPRE scartato come `AMBIGUOUS_NAK`;
 * - il byte dopo l'ETX di un frame applicativo è l'LRC e non viene mai interpretato (può valere 02/03/06/15).
 *
 * **Determinismo rispetto ai chunk.** Ogni decisione dipende solo dal prefisso già ricevuto, mai da quanti byte
 * in più ci sono nel buffer: per questo i byte non riconosciuti (rumore) restano in attesa finché non arriva un
 * inizio frame che chiude la sequenza (o finché la sequenza raggiunge `maxFrameBytes`), e il limite di lunghezza
 * di un frame vale anche quando l'ETX è già arrivato. Chi chiude lo scambio recupera l'eventuale rumore in coda
 * con [clear] per il log.
 *
 * Non thread-safe: lo usa una sola sessione alla volta.
 */
class FrameSplitter(
    private val appMode: LrcMode,
    private val controlRxPolicy: ControlLrcRxPolicy,    // da ecr17.profile
    private val maxFrameBytes: Int = 4096,
) {
    init {
        // STX ETX LRC è il frame applicativo più corto: un limite inferiore renderebbe tutto OVERSIZED.
        require(maxFrameBytes >= MIN_FRAME_BYTES) { "maxFrameBytes deve essere almeno $MIN_FRAME_BYTES (era $maxFrameBytes)" }
    }

    /** LRC attesi di ACK e NAK nelle due convenzioni: dipendono solo da appMode, calcolati una volta. */
    private val ackWire = ControlLrcRule.WIRE.lrcFor(ControlChars.ACK, appMode)
    private val ackSameAsApp = ControlLrcRule.SAME_AS_APP.lrcFor(ControlChars.ACK, appMode)
    private val nakWire = ControlLrcRule.WIRE.lrcFor(ControlChars.NAK, appMode)
    private val nakSameAsApp = ControlLrcRule.SAME_AS_APP.lrcFor(ControlChars.NAK, appMode)

    /** Lunghezza massima (SOH…EOT compresi) di un progress: il testo è di norma 20 caratteri (§3.7). */
    private val maxProgressBytes = minOf(PROGRESS_MAX_BYTES, maxFrameBytes)

    // Buffer circolare semplice: i byte validi sono buf[start, end).
    private var buf = ByteArray(INITIAL_CAPACITY)
    private var start = 0
    private var end = 0

    /** Accoda byte ricevuti (copia). */
    fun feed(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        ensureRoom(bytes.size)
        bytes.copyInto(buf, destinationOffset = end)
        end += bytes.size
    }

    /**
     * Prossimo elemento estraibile dalla testa, oppure null se servono altri byte.
     *
     * Attenzione: null NON vuol dire buffer vuoto. Rumore non seguito da un lead (01 02 06 15), un frame senza
     * terminatore e un `06`/`06 03`/`15`/`15 03` isolati restano in [pendingSize] finché arrivano altri byte
     * (o il rumore raggiunge maxFrameBytes): solo così l'esito non dipende dai chunk. Chi chiude uno scambio
     * deve quindi chiamare [clear] e registrare il residuo come scarto (§3.21), altrimenti quei byte
     * spariscono senza traccia dal log del traffico.
     */
    fun next(): SplitItem? {
        if (pendingSize() == 0) return null
        return when (at(0)) {
            ControlChars.ACK -> nextAck()
            ControlChars.NAK -> nextNak()
            ControlChars.STX -> nextDelimited(terminator = ControlChars.ETX, maxBytes = maxFrameBytes, hasLrc = true)
            ControlChars.SOH -> nextDelimited(terminator = ControlChars.EOT, maxBytes = maxProgressBytes, hasLrc = false)
            else -> nextNoise()
        }
    }

    /** Byte in attesa nel buffer interno. */
    fun pendingSize(): Int = end - start

    /**
     * Da chiamare alla scadenza dell'attesa ACK. Buffer = SOLO [06] o [06 03] ⇒ Parsed(Control(ACK, BARE)) e svuota;
     * buffer = SOLO [15] o [15 03] ⇒ Discarded(AMBIGUOUS_NAK) e svuota; altrimenti null.
     *
     * Serve perché [next] non può decidere su un `06` o un `06 03` isolati (il byte successivo potrebbe essere
     * l'LRC): solo il silenzio dice che non arriverà altro. Il NAK isolato resta ambiguo e non fa mai reinviare.
     */
    fun flushBareControl(): SplitItem? {
        val size = pendingSize()
        if (size !in 1..2) return null
        if (size == 2 && at(1) != ControlChars.ETX) return null
        val item: SplitItem = when (at(0)) {
            ControlChars.ACK -> SplitItem.Parsed(bareAck(size = size))
            ControlChars.NAK -> SplitItem.Discarded(copy(0, size), DiscardReason.AMBIGUOUS_NAK)
            else -> return null
        }
        start = 0
        end = 0
        return item
    }

    /**
     * Svuota e restituisce i byte pendenti (per log).
     *
     * È l'unica via d'uscita del residuo che [next] trattiene (rumore in coda, frame troncato): la sessione la
     * chiama alla fine di ogni scambio e dopo lo splitter temporaneo degli orfani (§3.21). Il residuo può
     * contenere un frammento di esito, quindi va loggato solo dopo `PanMasker.maskDigitRuns` (M9).
     */
    fun clear(): ByteArray {
        val pending = copy(0, pendingSize())
        start = 0
        end = 0
        return pending
    }

    // ─── Frame di controllo ──────────────────────────────────────────────────

    /** `06`: ACK con match WIRE / SAME_AS_APP / MISMATCH (solo TOLERANT) / BARE (seguito da un inizio frame). */
    private fun nextAck(): SplitItem? {
        if (pendingSize() < 2) return null
        val b1 = at(1)
        if (isFrameStart(b1)) return parsed(1, bareAck(size = 1))
        if (b1 != ControlChars.ETX) return discard(1, DiscardReason.CONTROL_WITHOUT_ETX)
        if (pendingSize() < 3) return null
        val lrc = at(2)
        if (isFrameStart(lrc)) return parsed(2, bareAck(size = 2))
        val match = when (lrc) {
            ackWire -> ControlLrcMatch.WIRE              // preferito quando coincide con SAME_AS_APP (NOEXT)
            ackSameAsApp -> ControlLrcMatch.SAME_AS_APP
            else -> ControlLrcMatch.MISMATCH
        }
        if (match == ControlLrcMatch.MISMATCH && controlRxPolicy == ControlLrcRxPolicy.STRICT) {
            // Si scarta solo il lead: i due byte successivi ripassano dalle regole (resync di 1 byte).
            return discard(1, DiscardReason.CONTROL_LRC_STRICT)
        }
        return parsed(3, WireFrame.Control(raw = copy(0, 3), kind = ControlKind.ACK, lrc = lrc, match = match))
    }

    /** ACK nudo dei primi [size] byte (`06` o `06 03`): senza LRC, match BARE. */
    private fun bareAck(size: Int): WireFrame.Control =
        WireFrame.Control(raw = copy(0, size), kind = ControlKind.ACK, lrc = null, match = ControlLrcMatch.BARE)

    /**
     * `15`: diventa Control(NAK) SOLO con ETX e LRC di una delle due convenzioni, con qualsiasi policy.
     * Ogni altra forma è AMBIGUOUS_NAK, perché solo un Control(NAK) può far ritrasmettere (§3.21, M2).
     */
    private fun nextNak(): SplitItem? {
        if (pendingSize() < 2) return null
        val b1 = at(1)
        if (isFrameStart(b1)) return discard(1, DiscardReason.AMBIGUOUS_NAK)
        if (b1 != ControlChars.ETX) return discard(1, DiscardReason.CONTROL_WITHOUT_ETX)
        if (pendingSize() < 3) return null
        val lrc = at(2)
        if (isFrameStart(lrc)) return discard(2, DiscardReason.AMBIGUOUS_NAK)
        val match = when (lrc) {
            nakWire -> ControlLrcMatch.WIRE
            nakSameAsApp -> ControlLrcMatch.SAME_AS_APP
            else -> return discard(3, DiscardReason.AMBIGUOUS_NAK)
        }
        return parsed(3, WireFrame.Control(raw = copy(0, 3), kind = ControlKind.NAK, lrc = lrc, match = match))
    }

    // ─── Frame applicativi e progress ────────────────────────────────────────

    /**
     * STX…ETX LRC (hasLrc) oppure SOH…EOT. Cerca il primo terminatore entro `maxBytes` di frame; un STX/SOH che
     * compare prima ⇒ RESYNC_ON_NEW_START sui byte che lo precedono; nessun terminatore entro il limite ⇒
     * OVERSIZED di 1 byte (il lead) e risincronizzazione. Il limite si applica anche se il terminatore arriva
     * più avanti nello stesso chunk: così l'esito non dipende da come sono stati spezzati i byte.
     */
    private fun nextDelimited(terminator: Int, maxBytes: Int, hasLrc: Boolean): SplitItem? {
        val size = pendingSize()
        // Ultimo indice ammesso per il terminatore: il frame (terminatore e LRC compresi) sta in maxBytes.
        val lastTerminatorIndex = if (hasLrc) maxBytes - 2 else maxBytes - 1
        val scanEnd = minOf(size, lastTerminatorIndex + 1)
        for (i in 1 until scanEnd) {
            val b = at(i)
            if (b == terminator) {
                if (!hasLrc) {
                    val raw = copy(0, i + 1)
                    return parsed(i + 1, WireFrame.Progress(raw = raw, text = raw.copyOfRange(1, i)))
                }
                if (size < i + 2) return null        // manca l'LRC
                return parsed(i + 2, application(etxIndex = i))
            }
            if (isFrameStart(b)) return discard(i, DiscardReason.RESYNC_ON_NEW_START)
        }
        return if (size > lastTerminatorIndex) discard(1, DiscardReason.OVERSIZED) else null
    }

    /** Frame applicativo completo in testa: payload tra STX ed ETX, LRC = byte dopo l'ETX, verificato nelle 4 modalità. */
    private fun application(etxIndex: Int): WireFrame.Application {
        val raw = copy(0, etxIndex + 2)
        val payload = raw.copyOfRange(1, etxIndex)
        val lrc = raw[etxIndex + 1].toInt() and 0xFF
        val matching = LrcMode.entries.filterTo(mutableSetOf()) { it.compute(payload) == lrc }
        return WireFrame.Application(
            raw = raw,
            payload = payload,
            lrc = lrc,
            check = AppLrcCheck(received = lrc, configuredMode = appMode, matchingModes = matching),
        )
    }

    // ─── Rumore ──────────────────────────────────────────────────────────────

    /**
     * Byte non riconosciuti in testa: accorpati in un solo Discarded(NOISE) fino al prossimo lead (01 02 06 15).
     * Se il buffer finisce prima di un lead la sequenza resta in attesa (potrebbe continuare nel prossimo chunk),
     * salvo che abbia già raggiunto maxFrameBytes: allora si consegna un blocco di maxFrameBytes byte.
     */
    private fun nextNoise(): SplitItem? {
        val size = pendingSize()
        val limit = minOf(size, maxFrameBytes)
        var run = 0
        while (run < limit && !isLead(at(run))) run++
        return when {
            run < limit -> discard(run, DiscardReason.NOISE)          // chiusa da un lead
            run >= maxFrameBytes -> discard(run, DiscardReason.NOISE) // tetto raggiunto
            else -> null                                               // può continuare
        }
    }

    // ─── Buffer ──────────────────────────────────────────────────────────────

    private fun at(i: Int): Int = buf[start + i].toInt() and 0xFF

    private fun copy(from: Int, to: Int): ByteArray = buf.copyOfRange(start + from, start + to)

    private fun consume(n: Int) {
        start += n
        if (start == end) {
            start = 0
            end = 0
        }
    }

    private fun parsed(consumed: Int, frame: WireFrame): SplitItem {
        consume(consumed)
        return SplitItem.Parsed(frame)
    }

    private fun discard(n: Int, reason: DiscardReason): SplitItem {
        val bytes = copy(0, n)
        consume(n)
        return SplitItem.Discarded(bytes, reason)
    }

    /** Spazio per `extra` byte in coda: prima compatta (i byte consumati in testa), poi eventualmente raddoppia. */
    private fun ensureRoom(extra: Int) {
        if (end + extra <= buf.size) return
        val pending = end - start
        if (pending + extra <= buf.size) {
            buf.copyInto(buf, destinationOffset = 0, startIndex = start, endIndex = end)
        } else {
            var capacity = buf.size
            while (capacity < pending + extra) capacity *= 2
            val bigger = ByteArray(capacity)
            buf.copyInto(bigger, destinationOffset = 0, startIndex = start, endIndex = end)
            buf = bigger
        }
        start = 0
        end = pending
    }

    private companion object {
        const val MIN_FRAME_BYTES = 3
        const val PROGRESS_MAX_BYTES = 64
        const val INITIAL_CAPACITY = 512

        /** Inizio di un frame che NON può essere un LRC di controllo (i valori validi sono 68..6B e 78..7B). */
        fun isFrameStart(b: Int): Boolean = b == ControlChars.STX || b == ControlChars.SOH

        /** Byte che aprono una regola diversa dal rumore. */
        fun isLead(b: Int): Boolean =
            b == ControlChars.STX || b == ControlChars.SOH || b == ControlChars.ACK || b == ControlChars.NAK
    }
}
