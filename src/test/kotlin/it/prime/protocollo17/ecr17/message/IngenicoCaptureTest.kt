package it.prime.protocollo17.ecr17.message

import it.prime.protocollo17.ecr17.model.CardType
import it.prime.protocollo17.ecr17.model.CommandCode
import it.prime.protocollo17.ecr17.model.EntryMode
import it.prime.protocollo17.ecr17.model.PaymentLayout
import it.prime.protocollo17.ecr17.model.PaymentResponse
import it.prime.protocollo17.ecr17.model.Verdict
import it.prime.protocollo17.ecr17.testing.Frames
import it.prime.protocollo17.ecr17.wire.LrcMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * I 6 esiti REALI del terminale Ingenico dell'utente (A§9.8, log DLL del 2026-03-18): l'oracolo d'oro.
 *
 * Protegge due cose che nessun vettore sintetico può garantire: che l'LRC dei frame applicativi del terminale
 * torni SOLO in modalità `stx` (la scelta del profilo Ingenico, U2) e che il parser legga dai frame veri gli
 * stessi valori che la DLL mostrava (STAN, online id = "Operation Number", autorizzazione, tipo carta).
 */
class IngenicoCaptureTest {

    /** Payload ricevuto e LRC catturato sul filo (byte dopo l'ETX). */
    private class Captured(val row: String, val payload: String, val lrc: Int)

    private val captures = listOf(
        Captured("[4]", "025757330E01OPERAZIONE ANNULLATA    00000000000000000000000000000000000", 0x46),
        Captured("[10]", "025757330E01OPERAZIONE ANNULLATA    00000000000200000000023000004000055", 0x41),
        Captured("[16]", "025757330E01CARTA NON VALIDA        00000000000000000000000000000000000", 0x25),
        Captured("[22]", "025757330E00000558278******4768CLIR484320770947200000000023000004000055", 0x21),
        Captured("[28]", "025757330E01CARTA SCONOSCIUTA       00000000000000000000000000000000000", 0x3A),
        Captured("[34]", "025757330E00000558278******4768CLIR802220770955200000000023000005000056", 0x23),
    )

    private fun parse(row: String, maskPan: Boolean = false): PaymentResponse {
        val c = captures.single { it.row == row }
        return assertInstanceOf(
            PaymentResponse::class.java,
            ResponseParser.parse(c.payload.toByteArray(Charsets.ISO_8859_1), CommandCode.PAYMENT, maskPan),
        )
    }

    /** Ogni esito catturato è lungo 71 byte e il suo LRC torna in `stx` e in nessun'altra modalità. */
    @Test
    fun `LRC valido solo in stx`() {
        for (c in captures) {
            val bytes = c.payload.toByteArray(Charsets.ISO_8859_1)
            assertEquals(71, bytes.size, c.row)
            assertEquals(c.lrc, LrcMode.STX.compute(bytes), "${c.row} in stx")
            for (mode in LrcMode.entries - LrcMode.STX) {
                assertNotEquals(c.lrc, mode.compute(bytes), "${c.row} non deve tornare in $mode")
            }
        }
    }

    /** Le fixture condivise E4 ed E22 sono proprio le righe [4] e [22] della cattura. */
    @Test
    fun `le fixture coincidono con la cattura`() {
        assertEquals(captures.single { it.row == "[4]" }.payload, Frames.E4)
        assertEquals(captures.single { it.row == "[22]" }.payload, Frames.E22)
    }

    /** [4]: KO annullato prima della carta, tutto a zero dopo la descrizione. */
    @Test
    fun `riga 4 operazione annullata`() {
        val r = parse("[4]")
        assertEquals("02575733", r.terminalId, "TID reale del terminale, non quello della richiesta")
        assertEquals(PaymentLayout.BASE_71, r.layout)
        assertEquals("01", r.resultCode)
        assertEquals(Verdict.DECLINED, r.verdict)
        assertEquals("OPERAZIONE ANNULLATA", r.errorDescription)
        assertEquals("OPERAZIONE ANNULLATA    ", r.fields.single { it.pos == 13 }.raw)
        assertEquals("0", r.cardTypeRaw)
        assertNull(r.cardType, "'0' non è un tipo carta")
        assertEquals("00000000000", r.acquirerId)
        assertEquals("000000", r.stan)
        assertEquals("000000", r.onlineId)
        assertNull(r.pan)
    }

    /** [10]: KO annullato DOPO la lettura della carta: tipo carta, acquirer, STAN e online valorizzati (A§4.5.1). */
    @Test
    fun `riga 10 annullata dopo la lettura della carta`() {
        val r = parse("[10]")
        assertEquals(Verdict.DECLINED, r.verdict)
        assertEquals("OPERAZIONE ANNULLATA", r.errorDescription)
        assertEquals("2", r.cardTypeRaw)
        assertEquals(CardType.CREDIT, r.cardType)
        assertEquals("00000000023", r.acquirerId)
        assertEquals("000004", r.stan)
        assertEquals("000055", r.onlineId)
    }

    /** [16] e [28]: descrizioni dei rifiuti con il trim degli spazi. */
    @Test
    fun `righe 16 e 28 carta non valida e sconosciuta`() {
        assertEquals("CARTA NON VALIDA", parse("[16]").errorDescription)
        assertEquals(Verdict.DECLINED, parse("[16]").verdict)
        assertEquals("CARTA SCONOSCIUTA", parse("[28]").errorDescription)
        assertEquals(Verdict.DECLINED, parse("[28]").verdict)
    }

    /** [22]: pagamento approvato contactless, PAN già mascherato dal terminale. */
    @Test
    fun `riga 22 approvato`() {
        val r = parse("[22]")
        assertEquals("00", r.resultCode)
        assertEquals(Verdict.APPROVED, r.verdict)
        assertEquals("000558278******4768", r.pan)
        assertEquals("CLI", r.entryModeRaw)
        assertEquals(EntryMode.CLESS_ICC, r.entryMode)
        assertEquals("R48432", r.authCode)
        assertEquals("0770947", r.hostDateTimeRaw, "giorno 077 = 18 marzo, 09:47")
        assertEquals("2", r.cardTypeRaw)
        assertEquals(CardType.CREDIT, r.cardType)
        assertEquals("00000000023", r.acquirerId)
        assertEquals("000004", r.stan)
        assertEquals("000055", r.onlineId)
        assertNull(r.errorDescription)
    }

    /** [34]: secondo approvato, STAN e online incrementati. */
    @Test
    fun `riga 34 approvato`() {
        val r = parse("[34]")
        assertEquals(Verdict.APPROVED, r.verdict)
        assertEquals("R80222", r.authCode)
        assertEquals("0770955", r.hostDateTimeRaw)
        assertEquals("000005", r.stan)
        assertEquals("000056", r.onlineId)
    }

    /** M9 (richiede PanMasker di WP1): anche un PAN già mascherato dal terminale passa da maskFixedWidth. */
    @Test
    fun `riga 22 con maskPan`() {
        val r = parse("[22]", maskPan = true)
        assertEquals("***************4768", r.pan)
        assertEquals("000004", r.stan)
        assertEquals("R48432", r.authCode)
    }
}
