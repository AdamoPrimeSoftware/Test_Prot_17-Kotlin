package it.prime.protocollo17.domain.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * formatCents è l'unico formattatore di importi (§5.8): virgola decimale, punto delle migliaia, sempre 2 decimali.
 * Qui gira sul JDK completo; il runtime jlink del pacchetto, senza dati di locale, lo copre PackagedRuntimeTest.
 */
class MoneyTest {

    /** Un centesimo resta 0,01 (nessun passaggio da Double) e i decimali sono sempre due. */
    @Test
    fun `centesimi con la virgola e sempre due decimali`() {
        assertEquals("0,01 €", formatCents(1))
        assertEquals("0,10 €", formatCents(10))
        assertEquals("6,50 €", formatCents(650))
        assertEquals("0,00 €", formatCents(0))
    }

    /** Le migliaia col punto: "1,234.56" sarebbe letto da un operatore italiano come un altro importo. */
    @Test
    fun `migliaia col punto fino al massimo del campo`() {
        assertEquals("1.234,56 €", formatCents(123_456))
        assertEquals("999.999,99 €", formatCents(99_999_999))
    }
}
