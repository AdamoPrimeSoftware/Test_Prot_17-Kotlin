package it.prime.protocollo17.ecr17.message

import it.prime.protocollo17.ecr17.model.CardType
import it.prime.protocollo17.ecr17.model.CloseSessionResponse
import it.prime.protocollo17.ecr17.model.CommandCode
import it.prime.protocollo17.ecr17.model.DccInfo
import it.prime.protocollo17.ecr17.model.EntryMode
import it.prime.protocollo17.ecr17.model.FieldId
import it.prime.protocollo17.ecr17.model.IncrementalAuthResponse
import it.prime.protocollo17.ecr17.model.PaymentLayout
import it.prime.protocollo17.ecr17.model.PaymentResponse
import it.prime.protocollo17.ecr17.model.PreAuthClosureResponse
import it.prime.protocollo17.ecr17.model.PreAuthResponse
import it.prime.protocollo17.ecr17.model.ResponseField
import it.prime.protocollo17.ecr17.model.StatusResponse
import it.prime.protocollo17.ecr17.model.TotalsResponse
import it.prime.protocollo17.ecr17.model.Verdict
import it.prime.protocollo17.ecr17.testing.Frames
import it.prime.protocollo17.ecr17.wire.LrcMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * Parser delle risposte contro i vettori dei repo (A§9.5, A§9.7) e quelli derivati dai layout che i repo
 * sbagliavano (L§13.6: `i` 75 B, `e` come risposta a `G`, `v` 109 B, storno 84 B, `U` dal GT).
 *
 * Protegge la lettura alle posizioni giuste (un campo spostato cambia STAN e online id usati per riconciliare un
 * pagamento), i rami asimmetrici della spec, la decodifica ISO-8859-1 (A§8 D6) e il fatto che un payload
 * malformato non lancia mai. I casi con `maskPan = true` usano PanMasker (WP1) e girano dopo il suo merge.
 */
class ResponseParserTest {

    private fun p(s: String) = s.toByteArray(Charsets.ISO_8859_1)

    private fun payment(s: String, sent: CommandCode = CommandCode.PAYMENT) =
        assertInstanceOf(PaymentResponse::class.java, ResponseParser.parse(p(s), sent, maskPan = false))

    private fun field(fields: List<ResponseField>, id: FieldId): ResponseField? = fields.firstOrNull { it.id == id }

    // ─── Vettori (A§9.5, A§9.7, L§13.6) ────────────────────────────────────────

    private val paymentPositive = "123456780E000000000004111111111ICCABC12321115202ACQ        000042000099"
    private val paymentNegative = "123456780E01CARTA RIFIUTATA         000000000003AC2        000007000003"
    private val paymentDcc =
        "123456780V000000000004111111111ICCABC12321115202ACQ        00004200009900000000650100012345USD0000000006502"
    private val status = "123456780s000000000001022515302V1.2.3"
    private val totals = "123456780T000000000000123456000000"
    private val closePositive = "123456780C0000000000000010000000000000001000"
    private val closeNegative = "123456780C01SBILANCIO          100"
    private val preAuthPositive = "123456780e000000000004111111111CLIAUTH01000500000000001230002111520"
    private val preAuthEndingIn1 = "123456780e000000000004111111111CLIAUTH01000500010000001230002111520"
    private val preAuthNegative =
        "123456780e01NEGATO                  10000000000300000000000000000000000ACQ        000055000066"
    private val payApproved = "123456780E000000000000000000000ICCAUTH0121115202ACQ        000042000099"
    private val i75 = "123456780i000000000004111111111CLIACQ        AUTH01000042000099211152000000"
    private val e106 = "123456780e000000000004111111111CLIAUTH01000500000000001230002111520" +
        "000" + "0" + "ACQ        " + "000042" + "000099" + "000000000000"
    /** 'c' 74 B: pos 1-71 come la 'E' di pagamento più action code 72/3 (A§4.5.5). */
    private val c74 = "123456780c" + paymentPositive.substring(10) + "000"
    private val v109 = "123456780v000000000004111111111ICCABC12321115202ACQ        000042000099" +
        "000" + "1" + "00012345" + "USD" + "000000000650" + "2" + "0000000000"
    private val s84 = "123456780E000000000004111111111ICC0000002111520" +
        "2" + "ACQ        " + "000042" + "000099" + "000" + "0000000000"

    /** Sintetico: 'E' 92 B del pagamento esteso, action code 72/3 e importo host 75/8 (A§4.5.2). */
    private val x92 = paymentPositive + "005" + "00000777" + "0000000000"

    /** Sintetico: 'E' KO della verifica carta, action code 37/3 (A§4.5.2). */
    private val hKo = "123456780E01" + "CARTA NON VALIDA".padEnd(24) + "100" + "00000000" + "3" +
        "ACQ".padEnd(11) + "000042" + "000099"

    /** I vettori hanno le lunghezze dichiarate: un errore di copia sposterebbe tutti gli attesi. */
    @Test
    fun `i vettori hanno le lunghezze dei layout`() {
        mapOf(
            paymentPositive to 71, paymentNegative to 71, paymentDcc to 107, status to 37, totals to 34,
            closePositive to 44, closeNegative to 34, preAuthPositive to 67, preAuthNegative to 94,
            payApproved to 71, i75 to 75, e106 to 106, c74 to 74, v109 to 109, s84 to 84, x92 to 92, hKo to 71,
        ).forEach { (payload, length) -> assertEquals(length, payload.length, payload) }
    }

    /** LRC derivati dei vettori L§13.6 e A§9.5/9.7: confermano che i payload sono quelli delle analisi. */
    @Test
    fun `LRC derivati dei vettori`() {
        assertEquals(0x5D, LrcMode.STD.compute(p(paymentPositive)))
        assertEquals(0x5C, LrcMode.STX.compute(p(paymentPositive)))
        assertEquals(0x21, LrcMode.STD.compute(p(payApproved)))
        assertEquals(0x05, LrcMode.STD.compute(p(i75)))
        assertEquals(0x04, LrcMode.STX.compute(p(i75)))
        assertEquals(0x3C, LrcMode.STD.compute(p(e106)))
        assertEquals(0x3D, LrcMode.STX.compute(p(e106)))
        assertEquals(0x1D, LrcMode.STD.compute(p(v109)))
        assertEquals(0x1C, LrcMode.STX.compute(p(v109)))
        assertEquals(0x1D, LrcMode.STD.compute(p(s84)))
        assertEquals(0x1C, LrcMode.STX.compute(p(s84)))
        assertEquals(0x65, LrcMode.STD.compute(p(Frames.U34)))
    }

    // ─── E / V ──────────────────────────────────────────────────────────────────

    /** A§9.5 payment_positive: ramo OK completo, nessun DCC. */
    @Test
    fun `pagamento approvato`() {
        val r = payment(paymentPositive)
        assertEquals("12345678", r.terminalId)
        assertEquals('E', r.replyCode)
        assertEquals(PaymentLayout.BASE_71, r.layout)
        assertEquals("00", r.resultCode)
        assertEquals(Verdict.APPROVED, r.verdict)
        assertEquals("0000000004111111111", r.pan)
        assertEquals("ICC", r.entryModeRaw)
        assertEquals(EntryMode.ICC, r.entryMode)
        assertEquals("ABC123", r.authCode)
        assertEquals("2111520", r.hostDateTimeRaw)
        assertEquals("2", r.cardTypeRaw)
        assertEquals(CardType.CREDIT, r.cardType)
        assertEquals("ACQ", r.acquirerId)
        assertEquals("000042", r.stan)
        assertEquals("000099", r.onlineId)
        assertNull(r.errorDescription)
        assertNull(r.actionCode)
        assertNull(r.dcc)
        assertEquals(paymentPositive, r.raw.text)
        assertEquals('E', r.raw.replyCode)
    }

    /** I campi letti finiscono in `fields` con pos/len e grezzo come sul filo, in ordine di posizione. */
    @Test
    fun `campi con posizione e valore grezzo`() {
        val r = payment(paymentPositive)
        assertEquals(ResponseField(FieldId.TERMINAL_ID, 1, 8, "12345678"), r.fields.first())
        assertEquals(ResponseField(FieldId.PAN, 13, 19, "0000000004111111111"), field(r.fields, FieldId.PAN))
        assertEquals(ResponseField(FieldId.ACQUIRER_ID, 49, 11, "ACQ        "), field(r.fields, FieldId.ACQUIRER_ID))
        assertEquals(ResponseField(FieldId.ONLINE_ID, 66, 6, "000099"), r.fields.last())
        assertEquals(r.fields.sortedBy { it.pos }, r.fields)
        assertNull(field(r.fields, FieldId.ERROR_DESCRIPTION), "il ramo KO non è letto")
    }

    /** A§9.5 payment_negative: ramo KO solo con "01", descrizione con trim, tipo carta "3". */
    @Test
    fun `pagamento rifiutato`() {
        val r = payment(paymentNegative)
        assertEquals("01", r.resultCode)
        assertEquals(Verdict.DECLINED, r.verdict)
        assertEquals("CARTA RIFIUTATA", r.errorDescription)
        assertEquals(
            ResponseField(FieldId.ERROR_DESCRIPTION, 13, 24, "CARTA RIFIUTATA" + " ".repeat(9)),
            field(r.fields, FieldId.ERROR_DESCRIPTION),
        )
        assertEquals("3", r.cardTypeRaw)
        assertEquals(CardType.OTHER, r.cardType)
        assertEquals("AC2", r.acquirerId)
        assertEquals("000007", r.stan)
        assertEquals("000003", r.onlineId)
        assertNull(r.pan)
        assertNull(r.authCode)
        assertNull(r.entryMode)
        assertNull(field(r.fields, FieldId.PAN), "il PAN non esiste nel ramo KO")
    }

    /** A§9.5 payment_with_currency_exchange: 'V' ⇒ DCC; tasso con 4 decimali impliciti, precisione dell'importo. */
    @Test
    fun `pagamento con DCC`() {
        val r = payment(paymentDcc)
        assertEquals(PaymentLayout.DCC_117, r.layout)
        assertEquals(Verdict.APPROVED, r.verdict)
        assertEquals("000", r.actionCode)
        assertEquals(650L, r.originalAmountCents)
        val dcc = assertInstanceOf(DccInfo::class.java, r.dcc)
        assertTrue(dcc.applied)
        assertEquals("00012345", dcc.rateRaw)
        assertEquals(BigDecimal("1.2345"), dcc.rate)
        assertEquals("USD", dcc.currencyCode)
        assertEquals("000000000650", dcc.amountRaw)
        assertEquals(650L, dcc.amountMinor)
        assertEquals("2", dcc.precisionRaw)
        assertEquals(2, dcc.precision)
        assertEquals("000042", r.stan, "pos 1-71 come la E")
    }

    /** A§9.7: pagamento approvato del client rust. */
    @Test
    fun `pagamento approvato del client`() {
        val r = payment(payApproved)
        assertEquals("00", r.resultCode)
        assertEquals(EntryMode.ICC, r.entryMode)
        assertEquals(CardType.CREDIT, r.cardType)
        assertEquals("AUTH01", r.authCode)
        assertEquals("000042", r.stan)
    }

    /** rn test_response: 00/01/05/09/zz ⇒ approvato, rifiutato, carta non presente, tag sconosciuto, non riconosciuto. */
    @Test
    fun `mappatura degli esiti`() {
        mapOf(
            "00" to Verdict.APPROVED, "01" to Verdict.DECLINED, "05" to Verdict.CARD_NOT_PRESENT,
            "09" to Verdict.UNKNOWN_TAG, "zz" to Verdict.UNRECOGNIZED,
        ).forEach { (code, verdict) ->
            val r = payment("123456780E" + code + paymentPositive.substring(12))
            assertEquals(code, r.resultCode)
            assertEquals(verdict, r.verdict, code)
        }
    }

    /** Con esiti diversi da "01" (05, 09, sconosciuti) si legge il layout positivo, come i repo (A§4.5.1). */
    @Test
    fun `esito 05 legge il ramo positivo`() {
        val r = payment("123456780E05" + paymentPositive.substring(12))
        assertEquals("0000000004111111111", r.pan)
        assertNull(r.errorDescription)
    }

    /** Sintetico X 92: action code 72/3 e importo host 75/8, ignorati dai repo. */
    @Test
    fun `pagamento esteso 92 byte`() {
        val r = payment(x92, CommandCode.PAYMENT_EXTENDED)
        assertEquals(PaymentLayout.EXTENDED_92, r.layout)
        assertEquals("005", r.actionCode)
        assertEquals(777L, r.hostAmountCents)
        assertEquals("ABC123", r.authCode)
        assertNull(r.originalAmountCents)
        assertNull(r.dcc)
    }

    /** D10: un importo non composto solo da cifre è null (C++ restituiva un numero parziale). */
    @Test
    fun `importo host malformato e null`() {
        val r = payment(paymentPositive + "005" + "00006AB0" + "0000000000", CommandCode.PAYMENT_EXTENDED)
        assertNull(r.hostAmountCents)
        assertEquals(ResponseField(FieldId.HOST_AMOUNT, 75, 8, "00006AB0"), field(r.fields, FieldId.HOST_AMOUNT))
    }

    /** L§13.6 S84: storno OK senza codice autorizzazione (35-40 riservate), action code 72/3. */
    @Test
    fun `storno 84 byte`() {
        val r = payment(s84, CommandCode.REVERSAL)
        assertEquals(PaymentLayout.REVERSAL_84, r.layout)
        assertEquals(Verdict.APPROVED, r.verdict)
        assertNull(r.authCode, "pos 35-40 riservate: laravel le leggeva come autorizzazione \"000000\"")
        assertNull(field(r.fields, FieldId.AUTH_CODE))
        assertEquals("000", r.actionCode)
        assertEquals("0000000004111111111", r.pan)
        assertEquals(EntryMode.ICC, r.entryMode)
        assertEquals("2111520", r.hostDateTimeRaw)
        assertEquals("000042", r.stan)
        assertEquals("000099", r.onlineId)
    }

    /** Sintetico H KO: action code 37/3 solo per la verifica carta; lo stesso payload come P non lo legge. */
    @Test
    fun `verifica carta rifiutata con action code`() {
        val h = payment(hKo, CommandCode.CARD_VERIFICATION)
        assertEquals(PaymentLayout.CARD_VERIFICATION_71, h.layout)
        assertEquals(Verdict.DECLINED, h.verdict)
        assertEquals("CARTA NON VALIDA", h.errorDescription)
        assertEquals("100", h.actionCode)
        assertEquals(CardType.OTHER, h.cardType)
        assertEquals("000042", h.stan)
        assertEquals("000099", h.onlineId)

        val asPayment = payment(hKo, CommandCode.PAYMENT)
        assertNull(asPayment.actionCode, "nel layout base 37-47 è riservato")
    }

    /**
     * D6: una lettera accentata (0xE8) nella descrizione di un KO non sposta STAN e online id. Prima, in rust, il
     * payload passava da UTF-8 lossy e ogni campo dopo la lettera si spostava di +2.
     */
    @Test
    fun `byte E8 nella descrizione non sposta gli offset`() {
        val payload = "123456780E01" + "CARTA RèFIUTATA".padEnd(24) + "00000000000" + "3" +
            "AC2".padEnd(11) + "000007" + "000003"
        val bytes = p(payload)
        assertEquals(71, bytes.size)
        assertEquals(0xE8, bytes[19].toInt() and 0xFF, "byte 0xE8 in pos 20")

        val r = assertInstanceOf(PaymentResponse::class.java, ResponseParser.parse(bytes, CommandCode.PAYMENT, maskPan = false))
        assertEquals("CARTA RèFIUTATA", r.errorDescription)
        assertEquals("3", r.cardTypeRaw)
        assertEquals("AC2", r.acquirerId)
        assertEquals("000007", r.stan)
        assertEquals("000003", r.onlineId)
        assertEquals(71, r.raw.length)
    }

    // ─── e i c v ────────────────────────────────────────────────────────────────

    /** A§9.5 pre_auth_positive: importo, codice preautorizzazione, action code e data/ora alle pos della 'e'. */
    @Test
    fun `preautorizzazione approvata`() {
        val r = assertInstanceOf(PreAuthResponse::class.java, ResponseParser.parse(p(preAuthPositive), CommandCode.PRE_AUTH, maskPan = false))
        assertEquals(Verdict.APPROVED, r.verdict)
        assertEquals("0000000004111111111", r.pan)
        assertEquals(EntryMode.CLESS_ICC, r.entryMode)
        assertEquals("AUTH01", r.authCode)
        assertEquals("00050000", r.preAuthorizedAmountRaw)
        assertEquals(50_000L, r.preAuthorizedAmountCents)
        assertEquals("000000123", r.preAuthCode)
        assertEquals("000", r.actionCode)
        assertEquals("2111520", r.hostDateTimeRaw)
        assertNull(r.cardType)
    }

    /**
     * Regressione (rust response.rs:479): nel ramo OK la pos 48 è l'ultima cifra dell'importo, non il tipo carta.
     * Leggerla come tipo carta trasformerebbe un importo che finisce in 1 in "carta di debito".
     */
    @Test
    fun `preautorizzazione con importo che finisce in 1 non ha tipo carta`() {
        val r = ResponseParser.parsePreAuth(p(preAuthEndingIn1), maskPan = false)
        assertEquals("00050001", r.preAuthorizedAmountRaw)
        assertNull(r.cardTypeRaw)
        assertNull(r.cardType)
        assertNull(field(r.fields, FieldId.CARD_TYPE))
    }

    /** A§9.5 pre_auth_negative: descrizione, action 37/3, tipo carta 48/1, acquirer/STAN/online da pos 72. */
    @Test
    fun `preautorizzazione rifiutata`() {
        val r = ResponseParser.parsePreAuth(p(preAuthNegative), maskPan = false)
        assertEquals(Verdict.DECLINED, r.verdict)
        assertEquals("NEGATO", r.errorDescription)
        assertEquals("100", r.actionCode)
        assertEquals("3", r.cardTypeRaw)
        assertEquals(CardType.OTHER, r.cardType)
        assertEquals("ACQ", r.acquirerId)
        assertEquals("000055", r.stan)
        assertEquals("000066", r.onlineId)
        assertNull(r.pan)
        assertNull(r.preAuthorizedAmountCents)
    }

    /** L§13.6 e106 diretta: i campi di coda (acquirer, STAN, online) alle pos 72/83/89. */
    @Test
    fun `preautorizzazione 106 byte diretta`() {
        val r = ResponseParser.parsePreAuth(p(e106), maskPan = false)
        assertEquals("00050000", r.preAuthorizedAmountRaw)
        assertEquals("000000123", r.preAuthCode)
        assertEquals("ACQ", r.acquirerId)
        assertEquals("000042", r.stan)
        assertEquals("000099", r.onlineId)
    }

    /**
     * L§13.6 e106 come risposta a G: smistata per codice su 'e'. Prima laravel la leggeva come pagamento e
     * restituiva STAN "021115" e online "200000": un recupero con G avrebbe riconciliato la transazione sbagliata.
     */
    @Test
    fun `G che restituisce una e e una preautorizzazione`() {
        val r = assertInstanceOf(PreAuthResponse::class.java, ResponseParser.parse(p(e106), CommandCode.LAST_RESULT, maskPan = false))
        assertEquals("00050000", r.preAuthorizedAmountRaw)
        assertEquals("000000123", r.preAuthCode)
        assertEquals("ACQ", r.acquirerId)
        assertEquals("000042", r.stan)
        assertEquals("000099", r.onlineId)
    }

    /** L§13.6 i75: layout proprio. Prima entrambi i repo la leggevano come 'e' (auth "ACQ", STAN vuoto). */
    @Test
    fun `integrazione 75 byte`() {
        val r = assertInstanceOf(
            IncrementalAuthResponse::class.java,
            ResponseParser.parse(p(i75), CommandCode.INCREMENTAL_AUTH, maskPan = false),
        )
        assertEquals(Verdict.APPROVED, r.verdict)
        assertEquals("0000000004111111111", r.pan)
        assertEquals("CLI", r.entryModeRaw)
        assertEquals("ACQ", r.acquirerId)
        assertEquals("AUTH01", r.authCode)
        assertEquals("000042", r.stan)
        assertEquals("000099", r.onlineId)
        assertEquals("2111520", r.hostDateTimeRaw)
        assertEquals("000", r.actionCode)
    }

    /** L§13.6 v109: action code e DCC dalla pos 75 (non 83 come la 'V'); laravel perdeva tutto senza segnalarlo. */
    @Test
    fun `chiusura preautorizzazione con DCC 109 byte`() {
        val r = assertInstanceOf(
            PreAuthClosureResponse::class.java,
            ResponseParser.parse(p(v109), CommandCode.PRE_AUTH_CLOSURE, maskPan = false),
        )
        assertEquals('v', r.replyCode)
        assertEquals(Verdict.APPROVED, r.verdict)
        assertEquals("ABC123", r.authCode)
        assertEquals("000042", r.stan)
        assertEquals("000", r.actionCode)
        val dcc = assertInstanceOf(DccInfo::class.java, r.dcc)
        assertTrue(dcc.applied)
        assertEquals(BigDecimal("1.2345"), dcc.rate)
        assertEquals("USD", dcc.currencyCode)
        assertEquals("000000000650", dcc.amountRaw)
        assertEquals(650L, dcc.amountMinor)
        assertEquals(2, dcc.precision)
        assertEquals(ResponseField(FieldId.DCC_RATE, 76, 8, "00012345"), field(r.fields, FieldId.DCC_RATE))
    }

    /** 'c' 74 B: pos 1-71 come la 'E' più action code 72/3, nessun DCC. */
    @Test
    fun `chiusura preautorizzazione senza DCC`() {
        val r = assertInstanceOf(
            PreAuthClosureResponse::class.java,
            ResponseParser.parse(p(c74), CommandCode.PRE_AUTH_CLOSURE, maskPan = false),
        )
        assertEquals(74, r.raw.length)
        assertEquals("000", r.actionCode)
        assertEquals("ABC123", r.authCode)
        assertNull(r.dcc)
    }

    // ─── s T C ──────────────────────────────────────────────────────────────────

    /** A§9.5 status: data/ora validata (2025-02-01T15:30), stato 2, release. */
    @Test
    fun `stato terminale`() {
        val r = assertInstanceOf(StatusResponse::class.java, ResponseParser.parse(p(status), CommandCode.STATUS, maskPan = false))
        assertEquals("12345678", r.terminalId)
        assertEquals("0102251530", r.dateTimeRaw)
        assertEquals(LocalDateTime.of(2025, 2, 1, 15, 30), r.dateTime)
        assertEquals(2, r.statusDigit)
        assertEquals("V1.2.3", r.softwareRelease)
        assertEquals(ResponseField(FieldId.SOFTWARE_RELEASE, 32, 6, "V1.2.3"), field(r.fields, FieldId.SOFTWARE_RELEASE))
    }

    /** D11: una data impossibile resta solo grezza (C++ la normalizzava in silenzio); release con spazi interni. */
    @Test
    fun `stato con data non valida e release con spazi`() {
        val r = ResponseParser.parseStatus(p("123456780s0000000000" + "3102251530" + "X" + "SYSEMV  204   "))
        assertEquals("3102251530", r.dateTimeRaw)
        assertNull(r.dateTime, "31 febbraio non esiste")
        assertEquals(-1, r.statusDigit)
        assertEquals("SYSEMV  204", r.softwareRelease)
    }

    /** A§9.5 totals. */
    @Test
    fun `totali`() {
        val r = assertInstanceOf(TotalsResponse::class.java, ResponseParser.parse(p(totals), CommandCode.TOTALS, maskPan = false))
        assertEquals(Verdict.APPROVED, r.verdict)
        assertEquals("0000000000123456", r.posTotalRaw)
        assertEquals(123_456L, r.posTotalCents)
        assertEquals(150L, ResponseParser.parseTotals(p(Frames.T34)).posTotalCents)
    }

    /** A§4.5.7: il totale POS della 'T' si legge con qualsiasi esito. */
    @Test
    fun `totali rifiutati leggono comunque il totale`() {
        val r = ResponseParser.parseTotals(p("123456780T01" + "0000000000000150" + "000000"))
        assertEquals(Verdict.DECLINED, r.verdict)
        assertEquals(150L, r.posTotalCents)
    }

    /** A§9.5 close_positive: totali POS e host solo con "00". */
    @Test
    fun `chiusura contabile positiva`() {
        val r = assertInstanceOf(CloseSessionResponse::class.java, ResponseParser.parse(p(closePositive), CommandCode.CLOSE_SESSION, maskPan = false))
        assertEquals(Verdict.APPROVED, r.verdict)
        assertEquals("0000000000001000", r.posTotalRaw)
        assertEquals(1000L, r.posTotalCents)
        assertEquals("0000000000001000", r.hostTotalRaw)
        assertEquals(1000L, r.hostTotalCents)
        assertNull(r.errorDescription)
    }

    /** A§9.5 close_negative: descrizione 13/19 con trim e action code 32/3. */
    @Test
    fun `chiusura contabile negativa`() {
        val r = ResponseParser.parseCloseSession(p(closeNegative))
        assertEquals(Verdict.DECLINED, r.verdict)
        assertEquals("SBILANCIO", r.errorDescription)
        assertEquals("100", r.actionCode)
        assertNull(r.posTotalRaw)
        assertNull(r.hostTotalCents)
    }

    /** Asimmetria A§4.5.7: per la 'C' un esito "09" NON è positivo (al contrario dei pagamenti). */
    @Test
    fun `chiusura contabile con esito 09 legge il ramo negativo`() {
        val r = ResponseParser.parseCloseSession(p("123456780C09" + "ERRORE".padEnd(19) + "200" + "0".repeat(10)))
        assertEquals(Verdict.UNKNOWN_TAG, r.verdict)
        assertEquals("ERRORE", r.errorDescription)
        assertEquals("200", r.actionCode)
        assertNull(r.posTotalCents)
    }

    // ─── Smistamento e layout ───────────────────────────────────────────────────

    /** L§13.6 U34: un 'U' dal GT non è mai un esito; neppure 'S', 'K' o un payload senza codice. */
    @Test
    fun `codici senza parser v1 restituiscono null`() {
        for (sent in CommandCode.entries) {
            assertNull(ResponseParser.parse(p(Frames.U34), sent, maskPan = false), "U con inviato $sent")
            assertNull(ResponseParser.parse(p(Frames.RECEIPT), sent, maskPan = false), "S con inviato $sent")
            assertNull(ResponseParser.parse(p("123456780K0000000100000000<x/>"), sent, maskPan = false), "K")
            assertNull(ResponseParser.parse(p("123456780"), sent, maskPan = false), "senza pos 10")
        }
    }

    /** §3.14: 'V' sempre DCC; 'E' secondo il comando inviato; con G (o altro) per lunghezza. */
    @Test
    fun `layout della E secondo il comando inviato`() {
        for (sent in CommandCode.entries) {
            assertEquals(PaymentLayout.DCC_117, ResponseParser.paymentLayoutFor(sent, 'V', 71))
        }
        assertEquals(PaymentLayout.BASE_71, ResponseParser.paymentLayoutFor(CommandCode.PAYMENT, 'E', 92))
        assertEquals(PaymentLayout.EXTENDED_92, ResponseParser.paymentLayoutFor(CommandCode.PAYMENT_EXTENDED, 'E', 71))
        assertEquals(PaymentLayout.REVERSAL_84, ResponseParser.paymentLayoutFor(CommandCode.REVERSAL, 'E', 71))
        assertEquals(PaymentLayout.CARD_VERIFICATION_71, ResponseParser.paymentLayoutFor(CommandCode.CARD_VERIFICATION, 'E', 71))
        assertEquals(PaymentLayout.BASE_71, ResponseParser.paymentLayoutFor(CommandCode.LAST_RESULT, 'E', 71))
        assertEquals(PaymentLayout.REVERSAL_84, ResponseParser.paymentLayoutFor(CommandCode.LAST_RESULT, 'E', 84))
        assertEquals(PaymentLayout.EXTENDED_92, ResponseParser.paymentLayoutFor(CommandCode.LAST_RESULT, 'E', 92))
        assertEquals(PaymentLayout.EXTENDED_92, ResponseParser.paymentLayoutFor(CommandCode.LAST_RESULT, 'E', 100))
        assertEquals(PaymentLayout.BASE_71, ResponseParser.paymentLayoutFor(CommandCode.LAST_RESULT, 'E', 80))
    }

    /** G dopo uno storno: la 'E' da 84 B è letta come storno (niente autorizzazione, action code 72/3). */
    @Test
    fun `G che restituisce uno storno`() {
        val r = payment(s84, CommandCode.LAST_RESULT)
        assertEquals(PaymentLayout.REVERSAL_84, r.layout)
        assertNull(r.authCode)
        assertEquals("000", r.actionCode)
    }

    // ─── Difensivi ──────────────────────────────────────────────────────────────

    /** A§9.5 difensivi: payload vuoto o corto ⇒ nessuna eccezione, esito non riconosciuto, stato -1. */
    @Test
    fun `payload vuoti o corti non lanciano`() {
        val empty = ResponseParser.parsePayment(ByteArray(0), PaymentLayout.BASE_71, maskPan = false)
        assertEquals("", empty.resultCode)
        assertEquals(Verdict.UNRECOGNIZED, empty.verdict)
        assertNull(empty.pan)
        assertTrue(empty.fields.isEmpty())
        assertEquals("", empty.terminalId)

        val shortStatus = ResponseParser.parseStatus(p("123"))
        assertEquals(-1, shortStatus.statusDigit)
        assertNull(shortStatus.dateTime)
        assertEquals("", shortStatus.softwareRelease)
        assertEquals("123", shortStatus.terminalId)
    }

    /**
     * Vettori della proprietà "nessun troncamento lancia": anche quelli con PAN in chiaro (E22_CLEAR, I75_KO_CLEAR,
     * riga S con PAN intero o spezzato da 0x7F), così con maskPan = true il ramo di mascheramento gira su ogni
     * prefisso, compresi quelli che tagliano il campo PAN 13/19 a metà.
     */
    private val truncationVectors = listOf(
        paymentPositive, paymentNegative, paymentDcc, status, totals, closePositive, closeNegative,
        preAuthPositive, preAuthNegative, i75, e106, c74, v109, s84, x92, hKo, Frames.T34, Frames.E4,
        Frames.E22_CLEAR, Frames.I75_KO_CLEAR, Frames.RECEIPT_PAN, "123456780SPAN 41111111\u007F11111111",
    )

    /** Ogni prefisso di ogni vettore, con ogni comando inviato, passa da tutti i parser senza eccezioni. */
    private fun decodeEveryTruncation(maskPan: Boolean) {
        for (vector in truncationVectors) for (length in 0..vector.length) for (sent in CommandCode.entries) {
            val prefix = p(vector.substring(0, length))
            ResponseParser.parse(prefix, sent, maskPan = maskPan)
            ResponseParser.parsePayment(prefix, PaymentLayout.DCC_117, maskPan = maskPan)
            ResponseParser.parsePreAuth(prefix, maskPan = maskPan)
            ResponseParser.parseIncrementalAuth(prefix, maskPan = maskPan)
            ResponseParser.parsePreAuthClosure(prefix, maskPan = maskPan)
            ResponseParser.parseStatus(prefix)
            ResponseParser.parseTotals(prefix)
            ResponseParser.parseCloseSession(prefix)
            ResponseParser.parseReceiptLine(prefix, maskPan = maskPan)
        }
    }

    /** Proprietà: ogni prefisso di ogni vettore, con ogni comando inviato, si decodifica senza eccezioni. */
    @Test
    fun `ogni troncamento di ogni vettore si decodifica senza eccezioni`() {
        decodeEveryTruncation(maskPan = false)
    }

    /** Un importo troncato non diventa un importo più piccolo: è null, il grezzo parziale resta visibile. */
    @Test
    fun `importo troncato e null`() {
        val r = ResponseParser.parseTotals(p(totals.substring(0, 20)))
        assertEquals("00000000", r.posTotalRaw)
        assertNull(r.posTotalCents)
    }

    // ─── maskPan = true (richiedono PanMasker di WP1) ───────────────────────────

    /** M9: con maskPan il PAN è mascherato in `pan`, in `raw` e in `fields`, e il resto resta intatto. */
    @Test
    fun `maskPan maschera il PAN in pan raw e fields`() {
        val r = assertInstanceOf(PaymentResponse::class.java, ResponseParser.parse(p(Frames.E22_CLEAR), CommandCode.PAYMENT))
        assertEquals("***************1111", r.pan)
        assertFalse(r.raw.text.contains("411111111111"), r.raw.text)
        assertTrue(r.fields.none { it.raw.contains("411111111111") }, r.fields.toString())
        assertEquals("***************1111", field(r.fields, FieldId.PAN)?.raw)
        assertEquals("000004", r.stan, "STAN e online non vanno toccati")
        assertEquals("000055", r.onlineId)
        assertEquals("R48432", r.authCode)
    }

    /** M9: la 'i' non ha ramo KO, quindi il PAN si maschera anche con esito "01" (A§4.5.4). */
    @Test
    fun `maskPan maschera il PAN di una i rifiutata`() {
        val r = assertInstanceOf(
            IncrementalAuthResponse::class.java,
            ResponseParser.parse(p(Frames.I75_KO_CLEAR), CommandCode.INCREMENTAL_AUTH, maskPan = true),
        )
        assertEquals(Verdict.DECLINED, r.verdict)
        assertEquals("***************1111", r.pan)
        assertFalse(r.raw.text.contains("4111111111"), r.raw.text)
        assertTrue(r.fields.none { it.raw.contains("4111111111") }, r.fields.toString())
        assertEquals("000042", r.stan)
    }

    /**
     * M9: e, c, v approvate mascherate in `pan`, in `raw` e nella lista `fields` (§7.2); un KO della E resta identico
     * (nessun PAN, descrizione intatta). Controllare solo `pan` non basterebbe: una regressione che leggesse i campi
     * dall'array non mascherato lascerebbe il PAN in chiaro nel grezzo e nella vista chiave/valore.
     */
    @Test
    fun `maskPan su e c v e su un rifiuto`() {
        val e = ResponseParser.parsePreAuth(p(e106), maskPan = true)
        assertPanMasked(pan = e.pan, rawText = e.raw.text, fields = e.fields, label = "e 106")
        assertEquals("000000123", e.preAuthCode, "il codice di preautorizzazione non è un PAN")

        val c = assertInstanceOf(
            PreAuthClosureResponse::class.java,
            ResponseParser.parse(p(c74), CommandCode.PRE_AUTH_CLOSURE, maskPan = true),
        )
        assertPanMasked(pan = c.pan, rawText = c.raw.text, fields = c.fields, label = "c 74")
        assertEquals("ABC123", c.authCode)
        assertEquals("000042", c.stan, "STAN e online non vanno toccati")
        assertEquals("000099", c.onlineId)

        val v = assertInstanceOf(
            PreAuthClosureResponse::class.java,
            ResponseParser.parse(p(v109), CommandCode.PRE_AUTH_CLOSURE, maskPan = true),
        )
        assertPanMasked(pan = v.pan, rawText = v.raw.text, fields = v.fields, label = "v 109")
        assertEquals("000000000650", v.dcc?.amountRaw)

        val ko = ResponseParser.parsePayment(p(Frames.E4), PaymentLayout.BASE_71, maskPan = true)
        assertEquals(Frames.E4, ko.raw.text)
        assertEquals("OPERAZIONE ANNULLATA", ko.errorDescription)
    }

    /** Proprietà con maskPan = true, il percorso del client (§3.23): il mascheramento non lancia su nessun prefisso. */
    @Test
    fun `ogni troncamento si decodifica senza eccezioni anche con maskPan`() {
        decodeEveryTruncation(maskPan = true)
    }

    /** PAN 0000000004111111111 mascherato allo stesso modo nel campo tipizzato, nel grezzo e in `fields` (M9). */
    private fun assertPanMasked(pan: String?, rawText: String, fields: List<ResponseField>, label: String) {
        assertEquals("***************1111", pan, label)
        assertFalse(rawText.contains("4111111111"), "$label raw: $rawText")
        assertTrue(fields.none { it.raw.contains("4111111111") }, "$label fields: $fields")
        assertEquals("***************1111", field(fields, FieldId.PAN)?.raw, label)
    }
}
