package it.prime.protocollo17.ecr17.wire

/**
 * Caratteri di controllo ECR17 (A§3.1). Valori Int 0..255, mai Byte con segno.
 *
 * Perché Int e non Byte: in Kotlin `0x80..0xFF` come `Byte` sono negativi, e un confronto
 * `b == 0x7F.toByte()` scritto in fretta su un valore letto con segno sbaglia in silenzio.
 * Tutto il protocollo ragiona su `b.toInt() and 0xFF` (§2.12), quindi le costanti sono già Int.
 */
object ControlChars {
    const val SOH = 0x01
    const val STX = 0x02
    const val ETX = 0x03
    const val EOT = 0x04
    const val ACK = 0x06
    const val NAK = 0x15
    const val ESC = 0x1B              // separatore TAG / fine scontrino
    const val LRC_BASE = 0x7F
    const val RECEIPT_NEWLINE = 0x7D  // dentro le righe S: a capo + reset
    const val RECEIPT_DOUBLE = 0x7F   // dentro le righe S: doppia altezza grassetto
    const val RECEIPT_DOUBLE_ALT = 0x7E  // righe S a 24 colonne (Ingenico 2025, catture PosMock): a capo + doppia altezza
    const val ZERO = 0x30             // riempitivo '0'
    const val PROGRESS_TEXT_LENGTH = 20

    /** Nome simbolico per il log ("STX", "ACK"…), null se non è un carattere di controllo noto. */
    fun nameOf(b: Int): String? = when (b and 0xFF) {
        SOH -> "SOH"; STX -> "STX"; ETX -> "ETX"; EOT -> "EOT"; ACK -> "ACK"; NAK -> "NAK"; ESC -> "ESC"
        else -> null
    }
}
