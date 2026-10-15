package it.prime.protocollo17.ecr17.wire

// STUB di WP0 (§8.2): firme definitive di §3.8, corpi a carico di WP1.

/**
 * Rese esadecimali dei byte sul filo. Unica implementazione condivisa da log traffico, UI e test,
 * così un frame si legge allo stesso modo ovunque (e il minuscolo con spazio finale combacia col log DLL).
 */
object Hex {
    /** "02 30 7A" (upper) o "02 30 7a " (lower + spazio finale, stile DLL). */
    fun spaced(bytes: ByteArray, upper: Boolean = true, trailingSpace: Boolean = false): String =
        TODO("WP1: Hex.kt")

    /** Inverso tollerante a spazi e maiuscole/minuscole; usato dai test e dal simulatore. */
    fun parse(hex: String): ByteArray = TODO("WP1: Hex.kt")

    /** Resa leggibile: ASCII stampabile così com'è, controlli come <STX>, altri byte come <xx>. */
    fun printable(bytes: ByteArray): String = TODO("WP1: Hex.kt")
}
