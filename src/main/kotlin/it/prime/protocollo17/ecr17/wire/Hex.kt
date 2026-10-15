package it.prime.protocollo17.ecr17.wire

/**
 * Rese esadecimali dei byte sul filo. Unica implementazione condivisa da log traffico, UI e test,
 * così un frame si legge allo stesso modo ovunque (e il minuscolo con spazio finale combacia col log DLL).
 *
 * Prima ogni punto dell'app WPF formattava i byte a modo suo (maiuscolo in UI, minuscolo nel log della DLL):
 * confrontare una traccia con il log `iaedll` richiedeva di normalizzare a mano. Ora c'è un solo formattatore,
 * e la forma "DLL" (minuscolo, ogni byte seguito da uno spazio) è un'opzione esplicita.
 */
object Hex {

    private val UPPER_DIGITS = "0123456789ABCDEF".toCharArray()
    private val LOWER_DIGITS = "0123456789abcdef".toCharArray()

    /**
     * Caratteri di controllo resi per nome da [printable]: SOLO quelli che delimitano i frame.
     * ESC (0x1B) resta `<1B>` di proposito: non delimita un frame ma è un dato in banda (fine scontrino,
     * separatore TAG di `U`), e nel testo di una riga S il suo valore numerico è più utile di un nome.
     */
    private val FRAMING_CONTROLS: Set<Int> = setOf(
        ControlChars.SOH, ControlChars.STX, ControlChars.ETX, ControlChars.EOT, ControlChars.ACK, ControlChars.NAK,
    )

    /** "02 30 7A" (upper) o "02 30 7a " (lower + spazio finale, stile DLL). */
    fun spaced(bytes: ByteArray, upper: Boolean = true, trailingSpace: Boolean = false): String {
        if (bytes.isEmpty()) return ""
        val digits = if (upper) UPPER_DIGITS else LOWER_DIGITS
        val sb = StringBuilder(bytes.size * 3)
        for ((i, b) in bytes.withIndex()) {
            if (i > 0) sb.append(' ')
            val v = b.toInt() and 0xFF
            sb.append(digits[v ushr 4]).append(digits[v and 0x0F])
        }
        if (trailingSpace) sb.append(' ')
        return sb.toString()
    }

    /**
     * Inverso tollerante a spazi e maiuscole/minuscole; usato dai test e dal simulatore.
     *
     * Ogni spazio bianco (spazi, tab, a capo) viene ignorato, poi le cifre si leggono a coppie: `"02 30 7a "`,
     * `"02307A"` e `"0230\n7A"` danno lo stesso risultato. Un carattere non esadecimale o un numero dispari di
     * cifre è un errore esplicito ([IllegalArgumentException]): un vettore di test scritto male non deve
     * diventare in silenzio un frame diverso.
     */
    fun parse(hex: String): ByteArray {
        val digits = StringBuilder(hex.length)
        for ((index, c) in hex.withIndex()) {
            if (c.isWhitespace()) continue
            require(Character.digit(c, 16) >= 0) { "Carattere non esadecimale '$c' all'indice $index in \"$hex\"" }
            digits.append(c)
        }
        require(digits.length % 2 == 0) { "Numero dispari di cifre esadecimali (${digits.length}) in \"$hex\"" }
        return ByteArray(digits.length / 2) { i ->
            val hi = Character.digit(digits[2 * i], 16)
            val lo = Character.digit(digits[2 * i + 1], 16)
            ((hi shl 4) or lo).toByte()
        }
    }

    /**
     * Resa leggibile: ASCII stampabile così com'è, controlli come <STX>, altri byte come <xx>.
     *
     * Stampabile = 0x20..0x7E. I controlli che delimitano i frame (SOH STX ETX EOT ACK NAK) compaiono per nome;
     * ogni altro byte, compresi ESC, 0x7F e i byte ≥ 0x80, compare come `<xx>` esadecimale maiuscolo, così un
     * byte inatteso nel testo non si confonde mai con un carattere.
     */
    fun printable(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size + 8)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            when {
                v in 0x20..0x7E -> sb.append(v.toChar())
                v in FRAMING_CONTROLS -> sb.append('<').append(ControlChars.nameOf(v)).append('>')
                else -> sb.append('<').append(UPPER_DIGITS[v ushr 4]).append(UPPER_DIGITS[v and 0x0F]).append('>')
            }
        }
        return sb.toString()
    }
}
