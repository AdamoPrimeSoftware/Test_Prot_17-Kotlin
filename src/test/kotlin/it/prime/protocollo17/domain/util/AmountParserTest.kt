package it.prime.protocollo17.domain.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Importi dei form (A§9.10): l'importo inviato al terminale deve essere esattamente quello scritto. Un parser via
 * Double o tollerante sui separatori trasformerebbe "1.234,56" in 1,23 € o "6.50" in 649 centesimi.
 */
class AmountParserTest {

    private fun cents(text: String): Long {
        val parsed = AmountParser.parseEuroToCents(text)
        assertTrue(parsed is AmountParse.Ok, "'$text' doveva essere valido, invece: $parsed")
        return (parsed as AmountParse.Ok).cents
    }

    private fun assertInvalid(text: String) {
        val parsed = AmountParser.parseEuroToCents(text)
        assertTrue(parsed is AmountParse.Invalid, "'$text' doveva essere rifiutato, invece: $parsed")
        assertTrue((parsed as AmountParse.Invalid).message.isNotBlank(), "il rifiuto di '$text' deve spiegare perché")
    }

    /** Punto e virgola valgono entrambi come separatore decimale (A§9.10: "6.50" ⇒ 650). */
    @Test
    fun `punto e virgola decimali danno gli stessi centesimi`() {
        assertEquals(650L, cents("6.50"))
        assertEquals(650L, cents("6,5"))
        assertEquals(650L, cents(" 6,50 "), "gli spazi attorno non contano")
    }

    /** Un intero è in euro, non in centesimi: "1" è 1 €. */
    @Test
    fun `un intero vale in euro`() {
        assertEquals(100L, cents("1"))
        assertEquals(1L, cents("0,01"))
        assertEquals(99_999_999L, cents("999999,99"), "il massimo del campo a 8 cifre")
    }

    /** Zero, negativi, tre decimali, testo e separatori delle migliaia sono rifiutati (A§9.10). */
    @Test
    fun `importi non validi sono rifiutati con un messaggio`() {
        listOf("0", "-5", "0.004", "", "abc", "1.234,56", "1000000", "0,00", "6.", ",5").forEach(::assertInvalid)
    }
}
