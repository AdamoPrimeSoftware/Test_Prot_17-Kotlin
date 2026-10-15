package it.prime.protocollo17.ecr17.wire

/**
 * Modalità di calcolo dell'LRC dei frame APPLICATIVI (base 0x7F, XOR puro, A§3.3).
 * Il terminale Ingenico dell'utente richiede STX (cattura A§9.8).
 *
 * Si mantengono i valori serializzati dei repo (`"std"`, `"stx"`, `"noext"`, `"stx_noext"`), ma la
 * semantica è espressa da due booleani: [foldStx] = lo STX entra nello XOR, [foldEtx] = l'ETX entra
 * nello XOR. Il nome `noext` inganna: significa "no STX, con ETX". Relazioni con `std`:
 * `stx = std ^ 0x01`, `noext = std ^ 0x03`, `stx_noext = std ^ 0x02`.
 */
enum class LrcMode(val wireName: String, val foldStx: Boolean, val foldEtx: Boolean) {
    STD("std", foldStx = false, foldEtx = false),
    STX("stx", foldStx = true, foldEtx = true),
    NOEXT("noext", foldStx = false, foldEtx = true),
    STX_NOEXT("stx_noext", foldStx = true, foldEtx = false);

    /** LRC del payload [from, to) (byte tra STX ed ETX esclusi). Risultato 0..255. */
    fun compute(payload: ByteArray, from: Int = 0, to: Int = payload.size): Int {
        var lrc = ControlChars.LRC_BASE
        if (foldStx) lrc = lrc xor ControlChars.STX
        for (i in from until to) lrc = lrc xor (payload[i].toInt() and 0xFF)
        if (foldEtx) lrc = lrc xor ControlChars.ETX
        return lrc and 0xFF
    }

    companion object {
        /**
         * Parsing RIGOROSO (L§9.2 L1): trim + case-insensitive sui 4 wireName; null se sconosciuto.
         * Mai un fallback silenzioso su STD: chi riceve null deve segnalare l'errore.
         */
        fun fromWireName(value: String): LrcMode? =
            entries.firstOrNull { it.wireName.equals(value.trim(), ignoreCase = true) }
    }
}
