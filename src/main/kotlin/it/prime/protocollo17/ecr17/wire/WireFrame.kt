package it.prime.protocollo17.ecr17.wire

/** Tipo di frame di controllo riconosciuto dallo splitter. */
enum class ControlKind { ACK, NAK }

/**
 * Come l'LRC di un ACK/NAK ricevuto si confronta con le due convenzioni.
 * Asimmetria voluta (U3): un ACK accettato per errore è innocuo, un NAK accettato per errore provoca un
 * reinvio. Per questo MISMATCH e BARE esistono SOLO per l'ACK: un NAK con LRC di nessuna convenzione o
 * senza ETX non diventa mai Control ma Discarded(AMBIGUOUS_NAK) (§3.7).
 */
enum class ControlLrcMatch {
    WIRE,          // 0x7F^ctrl^ETX (preferito quando coincide con SAME_AS_APP, es. in NOEXT)
    SAME_AS_APP,   // appMode.compute([ctrl])
    MISMATCH,      // solo ACK: nessuna delle due convenzioni, accettato con ControlLrcRxPolicy.TOLERANT (+ warning)
    BARE,          // solo ACK: 06 senza ETX o 06 03 senza LRC, seguito da un inizio frame (02/01), accettato con warning
}

/**
 * Verifica dell'LRC di un frame applicativo ricevuto, in tutte e 4 le modalità.
 *
 * Si calcolano sempre tutte le modalità, non solo quella configurata, perché la differenza tra
 * "LRC sbagliato" e "LRC valido in un'altra modalità" decide tra un NAK e un warning (§3.21 `lrcAccepted`)
 * ed è l'informazione che serve all'operatore per scoprire la modalità del suo terminale.
 */
data class AppLrcCheck(val received: Int, val configuredMode: LrcMode, val matchingModes: Set<LrcMode>) {
    val validInConfiguredMode: Boolean get() = configuredMode in matchingModes
    val validInSomeMode: Boolean get() = matchingModes.isNotEmpty()
}

/**
 * Frame completo estratto dallo splitter. `raw` = byte esatti sul filo (copia, PAN NON mascherato).
 *
 * Le classi con `ByteArray` NON sono `data class`: l'equals generato confronterebbe gli array per
 * riferimento dando l'illusione di un confronto di contenuto. I test usano `contentEquals`.
 */
sealed interface WireFrame {
    val raw: ByteArray

    class Control(override val raw: ByteArray, val kind: ControlKind, val lrc: Int?, val match: ControlLrcMatch) : WireFrame

    class Application(override val raw: ByteArray, val payload: ByteArray, val lrc: Int, val check: AppLrcCheck) : WireFrame {
        /** Codice messaggio in pos 10, null se il payload ha meno di 10 byte. */
        val replyCode: Char? get() = if (payload.size >= 10) (payload[9].toInt() and 0xFF).toChar() else null
        /** Riga di scontrino POS→ECR (A§4.5.9). */
        val isReceiptLine: Boolean get() = replyCode == 'S'
    }

    /** SOH testo EOT: nessun LRC, mai ACKato (A§3.2). `text` = byte interni (di norma 20). */
    class Progress(override val raw: ByteArray, val text: ByteArray) : WireFrame
}

/** Motivo per cui lo splitter ha scartato dei byte (evento `ProtocolEvent.Discarded`, log traffico). */
enum class DiscardReason {
    NOISE,                     // byte di testa non riconosciuto (resync di 1 byte, consecutivi accorpati)
    CONTROL_WITHOUT_ETX,       // 06/15 seguito da un byte che non è ETX né STX/SOH
    CONTROL_LRC_STRICT,        // ACK con LRC MISMATCH in modalità STRICT
    AMBIGUOUS_NAK,             // 15 nudo, 15 03 senza LRC, 15 03 con LRC di nessuna convenzione: MAI un reinvio
    RESYNC_ON_NEW_START,       // STX/SOH annidato prima di ETX/EOT: scartati i byte fino al nuovo inizio
    OVERSIZED,                 // nessun ETX/EOT entro maxFrameBytes
}

/** Elemento prodotto da `FrameSplitter.next()`: un frame riconosciuto oppure byte scartati con il motivo. */
sealed interface SplitItem {
    class Parsed(val frame: WireFrame) : SplitItem
    class Discarded(val bytes: ByteArray, val reason: DiscardReason) : SplitItem
}
