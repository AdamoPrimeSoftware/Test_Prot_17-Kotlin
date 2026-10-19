package it.prime.protocollo17.ecr17.message

import it.prime.protocollo17.ecr17.model.CardType
import it.prime.protocollo17.ecr17.model.CommandCode
import it.prime.protocollo17.ecr17.model.EcrPrintingRequest
import it.prime.protocollo17.ecr17.model.EntryMode
import it.prime.protocollo17.ecr17.model.PaymentLayout
import it.prime.protocollo17.ecr17.model.PaymentRequest
import it.prime.protocollo17.ecr17.model.PaymentResponse
import it.prime.protocollo17.ecr17.model.StatusRequest
import it.prime.protocollo17.ecr17.model.StatusResponse
import it.prime.protocollo17.ecr17.model.TerminalIds
import it.prime.protocollo17.ecr17.model.Verdict
import it.prime.protocollo17.ecr17.testing.Frames
import it.prime.protocollo17.ecr17.testing.PosMockReceipts.PAGAMENTI_117
import it.prime.protocollo17.ecr17.testing.PosMockReceipts.PAGAMENTI_120
import it.prime.protocollo17.ecr17.testing.PosMockReceipts.PAGAMENTI_123
import it.prime.protocollo17.ecr17.testing.PosMockReceipts.PAGAMENTI_126
import it.prime.protocollo17.ecr17.testing.PosMockReceipts.PAGAMENTI_140
import it.prime.protocollo17.ecr17.testing.PosMockReceipts.PAGAMENTI_160
import it.prime.protocollo17.ecr17.testing.PosMockReceipts.PAGAMENTI_83
import it.prime.protocollo17.ecr17.testing.PosMockReceipts.PAGAMENTI_86
import it.prime.protocollo17.ecr17.testing.PosMockReceipts.PAGAMENTI_89
import it.prime.protocollo17.ecr17.testing.PosMockReceipts.PAGAMENTI_92
import it.prime.protocollo17.ecr17.testing.PosMockReceipts.PAGAMENTI_95
import it.prime.protocollo17.ecr17.wire.LrcMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/**
 * I 6 esiti REALI del terminale Ingenico dell'utente (A§9.8, log DLL del 2026-03-18): l'oracolo d'oro. Accanto, i
 * frame reali di altri due terminali Ingenico (2025) e la risposta 't' dello stesso DESK3200, dalle catture IAE37 del
 * progetto PosMock (A§9.11).
 *
 * Protegge due cose che nessun vettore sintetico può garantire: che l'LRC dei frame applicativi del terminale
 * torni SOLO in modalità `stx` (la scelta del profilo Ingenico, U2) e che il parser legga dai frame veri gli
 * stessi valori che la DLL mostrava (STAN, online id = "Operation Number", autorizzazione, tipo carta). Le catture
 * PosMock hanno corretto tre letture: lo stato 's' testuale, la fine dello scontrino con l'ESC finale e 0x7E come
 * doppia altezza; e confermano l'encoder di P, X, E1 e s byte per byte.
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

    // ─── Catture del progetto PosMock (doc/campioni, log DLL IAE37) ─────────────
    //
    // Altri terminali Ingenico reali: TID 10002912 (08/07/2025, build B2807) e 10002001 (23/07/2025, build B2827),
    // più la risposta 't' del DESK3200 dell'utente (12/08/2026). Il riferimento è file:riga nella cartella
    // PosMock/doc/campioni (iaedll-pagamenti-20250708, iaedll-annulli-20250723, iaedll-status-20260812).

    private val posMockCaptures = listOf(
        Captured("pagamenti:10 P", "000000000P000000000000000000005" + " ".repeat(95) + "Testo contratto preautorizzazione00000000", 0x5D),
        Captured("pagamenti:28 E", "100029120E00000************1017CLE38066 1871119288105100004000002000038", 0x58),
        Captured("pagamenti:31 X", "000000000X000000000000000000005" + " ".repeat(112) + "CONTRATTO #1234500000000", 0x5B),
        Captured("pagamenti:46 E", "100029120E00000************9993CLE40384 1871120288105100003000003000039000000000050000000000", 0x6D),
        Captured("pagamenti:49 E1", "000000000E1", 0x3A),
        Captured("pagamenti:83 S", PAGAMENTI_83, 0x61),
        Captured("pagamenti:86 S", PAGAMENTI_86, 0x0B),
        Captured("pagamenti:89 S", PAGAMENTI_89, 0x36),
        Captured("pagamenti:92 S", PAGAMENTI_92, 0x55),
        Captured("pagamenti:95 S", PAGAMENTI_95, 0x0F),
        Captured("pagamenti:98 s", "000000000s", 0x3D),
        Captured("pagamenti:100 s", "100029120s00000000000807251125OPERATIVE01.00.14_B2807_2807", 0x3A),
        Captured("pagamenti:106 E", "100029120E00000438746******9993CLI37708 1891226288105100003000002000038000", 0x65),
        Captured("pagamenti:117 S", PAGAMENTI_117, 0x7B),
        Captured("pagamenti:120 S", PAGAMENTI_120, 0x48),
        Captured("pagamenti:123 S", PAGAMENTI_123, 0x0D),
        Captured("pagamenti:126 S", PAGAMENTI_126, 0x4B),
        Captured("pagamenti:140 S", PAGAMENTI_140, 0x39),
        Captured("pagamenti:160 S", PAGAMENTI_160, 0x32),
        Captured("annulli:5 E", "100020010E01ABORTED_MANUALLY        000000000002           000005000034", 0x33),
        Captured("annulli:17 s", "100020010s00000000002307251534OPERATIVE01.00.14_B2827_2827", 0x3C),
        Captured("annulli:26 E", "100020010E01CARD_SEARCH_TIMEOUT     000000000000           000000      ", 0x49),
        Captured("status:3 t", STATUS_3, 0x4D),
    )

    private fun posMock(row: String): ByteArray =
        posMockCaptures.single { it.row == row }.payload.toByteArray(Charsets.ISO_8859_1)

    /** Ogni frame applicativo delle catture PosMock, in entrambe le direzioni, torna SOLO in stx (come A§9.8). */
    @Test
    fun `catture PosMock LRC valido solo in stx`() {
        for (c in posMockCaptures) {
            val bytes = c.payload.toByteArray(Charsets.ISO_8859_1)
            assertEquals(c.lrc, LrcMode.STX.compute(bytes), "${c.row} in stx")
            for (mode in LrcMode.entries - LrcMode.STX) {
                assertNotEquals(c.lrc, mode.compute(bytes), "${c.row} non deve tornare in $mode")
            }
        }
    }

    /**
     * Le richieste della DLL escono identiche dal nostro encoder: layout 167 B di P/X con l'importo in 24-31 e il
     * testo allineato a destra in 32-159, E1 da 11 byte, s da 10 (prima solo P a testo vuoto era confermato, A§9.8).
     */
    @Test
    fun `l'encoder riproduce le richieste reali`() {
        val ids = TerminalIds()
        val payment = PaymentRequest(amountCents = 5, receiptText = "Testo contratto preautorizzazione")
        assertEquals(String(posMock("pagamenti:10 P"), Charsets.ISO_8859_1), String(RequestEncoder.encode(payment, ids), Charsets.ISO_8859_1))
        val extended = PaymentRequest(amountCents = 5, receiptText = "CONTRATTO #12345", extended = true)
        assertEquals(String(posMock("pagamenti:31 X"), Charsets.ISO_8859_1), String(RequestEncoder.encode(extended, ids), Charsets.ISO_8859_1))
        assertEquals("000000000E1", String(RequestEncoder.encode(EcrPrintingRequest(enabled = true), ids), Charsets.ISO_8859_1))
        assertEquals("000000000s", String(RequestEncoder.encode(StatusRequest, ids), Charsets.ISO_8859_1))
    }

    /** Stato testuale reale su due terminali e due build: prima "sconosciuto" con release "PERATIVE…" (§3.14). */
    @Test
    fun `stato s testuale reale`() {
        val first = assertInstanceOf(StatusResponse::class.java, ResponseParser.parse(posMock("pagamenti:100 s"), CommandCode.STATUS))
        assertEquals("10002912", first.terminalId)
        assertEquals("OPERATIVE", first.statusWord)
        assertEquals("01.00.14_B2807_2807", first.softwareRelease)
        assertEquals(LocalDateTime.of(2025, 7, 8, 11, 25), first.dateTime)
        val second = ResponseParser.parseStatus(posMock("annulli:17 s"))
        assertEquals("OPERATIVE", second.statusWord)
        assertEquals("01.00.14_B2827_2827", second.softwareRelease)
    }

    /** X reale da 92 byte: action code in 72/3 e importo host in 75/8 uguale a quello richiesto (R4, §9.2 punto 7). */
    @Test
    fun `X da 92 byte reale`() {
        val r = assertInstanceOf(PaymentResponse::class.java, ResponseParser.parse(posMock("pagamenti:46 E"), CommandCode.PAYMENT_EXTENDED, maskPan = false))
        assertEquals(PaymentLayout.EXTENDED_92, r.layout)
        assertEquals(Verdict.APPROVED, r.verdict)
        assertEquals("000************9993", r.pan)
        assertEquals(EntryMode.CLESS_CLE, r.entryMode)
        assertEquals("40384", r.authCode)
        assertEquals("1871120", r.hostDateTimeRaw)
        assertEquals(CardType.CREDIT, r.cardType)
        assertEquals("88105100003", r.acquirerId)
        assertEquals("000003", r.stan)
        assertEquals("000039", r.onlineId)
        assertEquals("000", r.actionCode)
        assertEquals(5L, r.hostAmountCents, "importo richiesto: 0,05 €")
    }

    /** P reale da 71 byte con entry mode CLE, e la variante da 74 con "000" in coda, letta come BASE_71. */
    @Test
    fun `P reali da 71 e 74 byte`() {
        val p71 = parsePosMock("pagamenti:28 E")
        assertEquals(PaymentLayout.BASE_71, p71.layout)
        assertEquals("CLE", p71.entryModeRaw)
        assertEquals(EntryMode.CLESS_CLE, p71.entryMode)
        assertEquals("38066", p71.authCode)
        assertEquals("000002", p71.stan)
        assertEquals("000038", p71.onlineId)

        val p74 = parsePosMock("pagamenti:106 E")
        assertEquals(74, p74.raw.length)
        assertEquals(PaymentLayout.BASE_71, p74.layout)
        assertEquals(EntryMode.CLESS_ICC, p74.entryMode)
        assertEquals("37708", p74.authCode)
        assertEquals("1891226", p74.hostDateTimeRaw)
        assertNull(p74.actionCode, "BASE_71 non legge la coda")
    }

    /** M9: il BIN che il terminale lascia visibile ("438746") viene mascherato anche lui, restano le ultime 4. */
    @Test
    fun `P da 74 con maskPan maschera anche il BIN`() {
        val r = parsePosMock("pagamenti:106 E", maskPan = true)
        assertEquals("***************9993", r.pan)
        assertFalse(r.raw.text.contains("438746"), r.raw.text)
    }

    /**
     * Rifiuti reali in inglese con underscore (abort locali del terminale): con CARD_SEARCH_TIMEOUT tipo carta "0",
     * STAN a zero e online id di spazi. Quest'ultimo è arrivato 128,7 s dopo il P (timeout esito, §9.2 punto 8).
     */
    @Test
    fun `rifiuti reali ABORTED_MANUALLY e CARD_SEARCH_TIMEOUT`() {
        val aborted = parsePosMock("annulli:5 E")
        assertEquals(Verdict.DECLINED, aborted.verdict)
        assertEquals("ABORTED_MANUALLY", aborted.errorDescription)
        assertEquals(CardType.CREDIT, aborted.cardType)
        assertNull(aborted.acquirerId, "11 spazi")
        assertEquals("000005", aborted.stan)
        assertEquals("000034", aborted.onlineId)

        val timeout = parsePosMock("annulli:26 E")
        assertEquals("CARD_SEARCH_TIMEOUT", timeout.errorDescription)
        assertEquals("0", timeout.cardTypeRaw)
        assertNull(timeout.cardType)
        assertEquals("000000", timeout.stan)
        assertNull(timeout.onlineId, "6 spazi")
    }

    /**
     * Righe S reali: nel formato a righe da 24 colonne separate da 0x7D lo 0x7E è a capo + doppia altezza (prima
     * "MCCHIP" e "PAGAMENTO" finivano sulla stessa riga di 48 caratteri), righe intermedie da 210 byte che non
     * chiudono, e le due forme reali dell'ultima riga (avanzamenti + ESC, "}}" + ESC) che ora chiudono.
     */
    @Test
    fun `righe S reali`() {
        val first = ResponseParser.parseReceiptLine(posMock("pagamenti:83 S"), maskPan = true)
        assertTrue(first.doubleHeight, "0x7E prima di PAGAMENTO")
        assertFalse(first.isEnd)
        val rows = first.text.split('\n').drop(1)
        assertTrue(rows.all { it.length == 24 }, "righe da 24 colonne: $rows")
        assertTrue("         MCCHIP         " in rows && "        PAGAMENTO       " in rows, rows.toString())

        val feedOnly = ResponseParser.parseReceiptLine(posMock("pagamenti:95 S"), maskPan = true)
        assertTrue(feedOnly.isEnd)
        assertEquals("", feedOnly.text)

        val last = ResponseParser.parseReceiptLine(posMock("pagamenti:126 S"), maskPan = true)
        assertTrue(last.isEnd)
        assertTrue(last.doubleHeight, "0x7F prima di IMPORTO EUR")
        assertTrue(last.text.endsWith("CONTRATTO #12345"), last.text)
    }

    /**
     * M9: sullo scontrino i terminali Ingenico stampano il PAN troncato con il BIN in chiaro. Prima maskDigitRuns
     * contava solo le cifre e quel BIN restava in chiaro nel testo e nel grezzo (quindi nel log traffico), mentre
     * sull'esito 'E' lo stesso PAN usciva mascherato. Ora resta visibile solo le ultime 4, come sull'esito.
     */
    @Test
    fun `righe S reali con il PAN troncato`() {
        val lines = mapOf(
            PAGAMENTI_120 to ("438746" to "************9993"),
            PAGAMENTI_140 to ("518619" to "************1017"),
            PAGAMENTI_160 to ("679999" to "***************3224"),
        )
        for ((payload, expected) in lines) {
            val (bin, masked) = expected
            val line = ResponseParser.parseReceiptLine(payload.toByteArray(Charsets.ISO_8859_1), maskPan = true)
            assertFalse(bin in line.text || bin in line.raw, "BIN in chiaro: ${line.raw}")
            assertTrue(masked in line.text && masked in line.raw, line.text)
            assertTrue("STAN 00000" in line.text, "le altre cifre restano leggibili: ${line.text}")
        }
    }

    /**
     * 't' (stato esteso, 225 B) del DESK3200 dell'utente, TID 02575733: è lo `StatoPos` della DLL (A§10.3), fuori dai
     * comandi v1, quindi nessun parser. Il frame documenta modello, seriale e l'IP che il terminale aveva il 12/08/2026.
     */
    @Test
    fun `stato esteso t del DESK3200`() {
        val bytes = posMock("status:3 t")
        assertEquals(225, bytes.size)
        assertNull(ResponseParser.parse(bytes, CommandCode.STATUS))
        val text = String(bytes, Charsets.ISO_8859_1)
        assertTrue(text.startsWith("025757330t"))
        assertTrue(text.contains("P/NDESK3200EM4") && text.contains("192168001106"), text)
    }

    private fun parsePosMock(row: String, maskPan: Boolean = false): PaymentResponse =
        assertInstanceOf(PaymentResponse::class.java, ResponseParser.parse(posMock(row), CommandCode.PAYMENT, maskPan))

    // Le righe S reali (PAGAMENTI_83 … PAGAMENTI_160) stanno in PosMockReceipts: le usano anche i test del
    // mascheramento a cavallo dei frame e della vista dello scontrino ricomposto.
    private companion object {
        /** Risposta 't' del DESK3200 TID 02575733 (12/08/2026). */
        const val STATUS_3 = "025757330t00000000001208261050P/NDESK3200EM4                   S/N182592207401594" +
            "MAN16.08EMV3242AREVFt___ECR47.00SSL31.71EXT35.62EDL39.00                        " +
            "192168001106192168001001010257573388006PRIME SOFTWARE SRL      2"
    }
}
