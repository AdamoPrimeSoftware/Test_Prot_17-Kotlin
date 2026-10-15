package it.prime.protocollo17.ecr17.message

import it.prime.protocollo17.ecr17.error.Ecr17ValidationException
import it.prime.protocollo17.ecr17.error.RequestField
import it.prime.protocollo17.ecr17.error.ValidationError
import it.prime.protocollo17.ecr17.model.CardVerificationRequest
import it.prime.protocollo17.ecr17.model.CloseSessionRequest
import it.prime.protocollo17.ecr17.model.CommandCode
import it.prime.protocollo17.ecr17.model.Ecr17Request
import it.prime.protocollo17.ecr17.model.EcrPrintingRequest
import it.prime.protocollo17.ecr17.model.IncrementalAuthRequest
import it.prime.protocollo17.ecr17.model.LastResultRequest
import it.prime.protocollo17.ecr17.model.PaymentRequest
import it.prime.protocollo17.ecr17.model.PaymentType
import it.prime.protocollo17.ecr17.model.PreAuthClosureRequest
import it.prime.protocollo17.ecr17.model.PreAuthRequest
import it.prime.protocollo17.ecr17.model.ReprintRequest
import it.prime.protocollo17.ecr17.model.ReprintTarget
import it.prime.protocollo17.ecr17.model.ReversalRequest
import it.prime.protocollo17.ecr17.model.StatusRequest
import it.prime.protocollo17.ecr17.model.TerminalIds
import it.prime.protocollo17.ecr17.model.TicketType
import it.prime.protocollo17.ecr17.model.TotalsRequest
import it.prime.protocollo17.ecr17.testing.Frames
import it.prime.protocollo17.ecr17.wire.LrcMode
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Builder delle richieste contro i vettori di A§9.4 (repo) e A§9.8 (cattura Ingenico, LRC `stx`).
 *
 * Protegge il layout byte per byte (un campo spostato di uno fa rifiutare o, peggio, fraintendere il comando dal
 * terminale), la regola "errore e mai troncamento" e le regole aggiunte rispetto ai repo: solo cifre negli
 * identificativi, solo ASCII stampabile nel testo, mai un byte NUL (la DLL lo inviava nella ristampa, A§4.3.7).
 */
class RequestEncoderTest {

    private val t = "12345678"
    private val c = "87654321"
    private val zeroIds = TerminalIds()

    private fun ascii(bytes: ByteArray) = String(bytes, Charsets.US_ASCII)

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    /** Le 4 LRC del payload nell'ordine std, stx, noext, stx_noext. */
    private fun lrcs(payload: ByteArray) = listOf(LrcMode.STD, LrcMode.STX, LrcMode.NOEXT, LrcMode.STX_NOEXT)
        .map { it.compute(payload) }

    private fun validationErrors(block: () -> Unit): List<ValidationError> =
        assertThrows(Ecr17ValidationException::class.java) { block() }.errors

    // ─── s ──────────────────────────────────────────────────────────────────────

    /** A§9.4 status: TID in 1-8, '0', 's' minuscola; frame std 02 … 03 34 (A§3.6a). */
    @Test
    fun `status con TID completo e frame std`() {
        val payload = RequestEncoder.buildStatus(t)
        assertEquals("123456780s", ascii(payload))
        assertArrayEquals(
            bytes(0x02, 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38, 0x30, 0x73, 0x03, 0x34),
            Frames.app(ascii(payload), LrcMode.STD),
        )
    }

    /** A§9.4: un TID corto si allinea a destra riempito di '0'. */
    @Test
    fun `status con TID corto riempito a sinistra`() {
        val payload = RequestEncoder.buildStatus("42")
        assertEquals("000000420s", ascii(payload))
        assertEquals(10, payload.size)
        assertEquals(0x3A, LrcMode.STD.compute(payload), "LRC std")
        assertEquals(0x3B, LrcMode.STX.compute(payload), "LRC stx")
    }

    /** A§9.4: 9 cifre di TID sono un errore, mai un troncamento. */
    @Test
    fun `status con TID di 9 cifre e FieldOverflow`() {
        val errors = validationErrors { RequestEncoder.buildStatus("123456789") }
        assertEquals(listOf(ValidationError.FieldOverflow(RequestField.TERMINAL_ID, "123456789", 8)), errors)
    }

    // ─── P X p ──────────────────────────────────────────────────────────────────

    /** A§9.4: un pagamento è sempre lungo 167 byte, anche con identificativi corti. */
    @Test
    fun `pagamento lungo 167 byte`() {
        val payload = RequestEncoder.buildPaymentLike('P', "1", "2", 650, PaymentType.AUTO, false, false, "")
        assertEquals(167, payload.size)
    }

    /** A§9.4: layout completo del pagamento e le 4 LRC derivate (2C/2D/2F/2E). */
    @Test
    fun `pagamento layout completo e LRC nelle 4 modalita`() {
        val payload = RequestEncoder.buildPaymentLike('P', t, c, 650, PaymentType.AUTO, false, false, "")
        val s = ascii(payload)
        assertEquals("12345678", s.substring(0, 8))
        assertEquals('0', s[8])
        assertEquals('P', s[9])
        assertEquals("87654321", s.substring(10, 18))
        assertEquals('0', s[18], "flag GT")
        assertEquals("00", s.substring(19, 21))
        assertEquals('0', s[21], "carta già presente")
        assertEquals('0', s[22], "tipo pagamento")
        assertEquals("00000650", s.substring(23, 31))
        assertEquals(" ".repeat(128), s.substring(31, 159))
        assertEquals("00000000", s.substring(159, 167))
        assertEquals(listOf(0x2C, 0x2D, 0x2F, 0x2E), lrcs(payload))
    }

    /** A§9.4: l'importo massimo occupa tutte e 8 le cifre. */
    @Test
    fun `importo massimo 99999999`() {
        val payload = RequestEncoder.buildPaymentLike('P', t, c, 99_999_999, PaymentType.AUTO, false, false, "")
        assertEquals("99999999", ascii(payload).substring(23, 31))
    }

    /** A§9.4: un importo negativo è NegativeAmount. */
    @Test
    fun `importo negativo e NegativeAmount`() {
        val errors = validationErrors {
            RequestEncoder.buildPaymentLike('P', t, c, -1, PaymentType.AUTO, false, false, "")
        }
        assertEquals(listOf(ValidationError.NegativeAmount(RequestField.AMOUNT)), errors)
    }

    /** A§9.4: 9 cifre di importo sono FieldOverflow (mai le ultime 8). */
    @Test
    fun `importo di 9 cifre e FieldOverflow`() {
        val errors = validationErrors {
            RequestEncoder.buildPaymentLike('P', t, c, 100_000_000, PaymentType.AUTO, false, false, "")
        }
        assertEquals(listOf(ValidationError.FieldOverflow(RequestField.AMOUNT, "100000000", 8)), errors)
    }

    /** A§9.4: TID troppo lungo anche nel pagamento. */
    @Test
    fun `pagamento con TID di 9 cifre e FieldOverflow`() {
        val errors = validationErrors {
            RequestEncoder.buildPaymentLike('P', "123456789", c, 650, PaymentType.AUTO, false, false, "")
        }
        assertEquals(listOf(ValidationError.FieldOverflow(RequestField.TERMINAL_ID, "123456789", 8)), errors)
    }

    /** A§9.4: pagamento esteso con flag GT, carta presente, credito e testo (flag GT solo dal builder di basso livello). */
    @Test
    fun `pagamento esteso con tutti i flag`() {
        val payload = RequestEncoder.buildPaymentLike('X', t, c, 650, PaymentType.CREDIT, true, true, "ABC")
        val s = ascii(payload)
        assertEquals(167, payload.size)
        assertEquals('X', s[9])
        assertEquals('1', s[18], "flag GT")
        assertEquals("00", s.substring(19, 21))
        assertEquals('1', s[21], "carta già presente")
        assertEquals('2', s[22], "credito")
        assertEquals("00000650", s.substring(23, 31))
        assertEquals(" ".repeat(125), s.substring(31, 156))
        assertEquals("ABC", s.substring(156, 159))
        assertEquals("00000000", s.substring(159, 167))
    }

    /**
     * Il testo dello scontrino è ALLINEATO A DESTRA (spec: "right-justified, space-filled on the left").
     * Prima di correggerlo i repo avevano rischiato il padEnd: il terminale avrebbe stampato un testo diverso.
     */
    @Test
    fun `testo scontrino allineato a destra`() {
        val s = ascii(RequestEncoder.buildPaymentLike('P', t, c, 650, PaymentType.AUTO, false, false, "ABC"))
        assertEquals(" ".repeat(125), s.substring(31, 156))
        assertEquals("ABC", s.substring(156, 159))
    }

    /** Testo di 128 byte esatti: entra; 129: FieldOverflow e nessun troncamento. */
    @Test
    fun `testo scontrino al limite e oltre`() {
        val full = "x".repeat(128)
        val s = ascii(RequestEncoder.buildPaymentLike('P', t, c, 1, PaymentType.AUTO, false, false, full))
        assertEquals(full, s.substring(31, 159))

        val errors = validationErrors {
            RequestEncoder.buildPaymentLike('P', t, c, 1, PaymentType.AUTO, false, false, full + "y")
        }
        assertEquals(listOf(ValidationError.FieldOverflow(RequestField.RECEIPT_TEXT, full + "y", 128)), errors)
    }

    /** A§9.4: la preautorizzazione usa lo stesso layout di P con 'p' minuscola. */
    @Test
    fun `preautorizzazione con codice p`() {
        val payload = RequestEncoder.buildPaymentLike('p', t, c, 1000, PaymentType.AUTO, false, false, "")
        val s = ascii(payload)
        assertEquals(167, payload.size)
        assertEquals('p', s[9])
        assertEquals("00001000", s.substring(23, 31))
    }

    /** A§9.4 flusso C++: buildPaymentMessage("12345678","00000001",650) ⇒ LRC std 25. */
    @Test
    fun `flusso C++ pagamento con LRC std 25`() {
        val payload = RequestEncoder.buildPaymentLike('P', "12345678", "00000001", 650, PaymentType.AUTO, false, false, "")
        assertEquals(0x25, LrcMode.STD.compute(payload))
    }

    /** Un codice fuori da {P, X, p} è un bug del chiamante, non un errore di validazione dell'utente. */
    @Test
    fun `codice non di pagamento e IllegalArgumentException`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            RequestEncoder.buildPaymentLike('Z', t, c, 1, PaymentType.AUTO, false, false, "")
        }
        assertTrue(e !is Ecr17ValidationException, "un codice sbagliato non è un errore dell'operatore")
    }

    // ─── i c ────────────────────────────────────────────────────────────────────

    /** A§9.4: integrazione 176 B con codice di preautorizzazione in 160-168. */
    @Test
    fun `integrazione 176 byte`() {
        val payload = RequestEncoder.buildIncrementalLike('i', t, c, 1000, "123456789", "")
        val s = ascii(payload)
        assertEquals(176, payload.size)
        assertEquals('i', s[9])
        assertEquals('0', s[18])
        assertEquals("0000", s.substring(19, 23))
        assertEquals("00001000", s.substring(23, 31))
        assertEquals(" ".repeat(128), s.substring(31, 159))
        assertEquals("123456789", s.substring(159, 168))
        assertEquals("00000000", s.substring(168, 176))
    }

    /** A§9.4: chiusura preautorizzazione; un codice corto si riempie di '0' a sinistra. */
    @Test
    fun `chiusura preautorizzazione con codice riempito`() {
        val payload = RequestEncoder.buildIncrementalLike('c', t, c, 500, "000000042", "")
        assertEquals(176, payload.size)
        assertEquals('c', ascii(payload)[9])
        assertEquals("000000042", ascii(payload).substring(159, 168))

        val short = RequestEncoder.buildIncrementalLike('c', t, c, 500, "42", "")
        assertArrayEquals(payload, short, "\"42\" e \"000000042\" producono lo stesso payload")
    }

    /** A§9.4: codice di preautorizzazione di 10 cifre ⇒ FieldOverflow. */
    @Test
    fun `codice preautorizzazione di 10 cifre e FieldOverflow`() {
        val errors = validationErrors { RequestEncoder.buildIncrementalLike('i', t, c, 100, "1234567890", "") }
        assertEquals(listOf(ValidationError.FieldOverflow(RequestField.PRE_AUTH_CODE, "1234567890", 9)), errors)
    }

    // ─── H S C T G E R ──────────────────────────────────────────────────────────

    /** A§9.4: verifica carta 39 B con tipo verifica '0' e tipo pagamento '1'. */
    @Test
    fun `verifica carta 39 byte`() {
        val payload = RequestEncoder.buildCardVerification(t, c, PaymentType.DEBIT, false)
        assertEquals("123456780H87654321" + "0" + "00" + "0" + "1" + "0000000000000000", ascii(payload))
        assertEquals(39, payload.size)
    }

    /** A§9.4: storno con STAN esplicito. */
    @Test
    fun `storno con STAN`() {
        assertEquals("123456780S8765432100012300", ascii(RequestEncoder.buildReversal(t, c, "000123")))
        assertEquals("123456780S8765432100012300", ascii(RequestEncoder.buildReversal(t, c, "123")), "STAN corto riempito")
    }

    /** A§4.3.9: STAN null o vuoto ⇒ "000000" (ultima transazione); LRC std 1C, stx 1D. */
    @Test
    fun `storno senza STAN storna l'ultima transazione`() {
        val expected = "123456780S8765432100000000"
        val payload = RequestEncoder.buildReversal(t, c, null)
        assertEquals(expected, ascii(payload))
        assertEquals(expected, ascii(RequestEncoder.buildReversal(t, c, "")))
        assertEquals(expected, ascii(RequestEncoder.buildReversal(t, c, "000000")))
        assertEquals(0x1C, LrcMode.STD.compute(payload))
        assertEquals(0x1D, LrcMode.STX.compute(payload))
    }

    /** A§9.4: STAN di 7 cifre ⇒ FieldOverflow. */
    @Test
    fun `STAN di 7 cifre e FieldOverflow`() {
        val errors = validationErrors { RequestEncoder.buildReversal(t, c, "1234567") }
        assertEquals(listOf(ValidationError.FieldOverflow(RequestField.STAN, "1234567", 6)), errors)
    }

    /** A§9.4: chiusura contabile 26 B, LRC std 0C, stx 0D. */
    @Test
    fun `chiusura contabile`() {
        val payload = RequestEncoder.buildCloseOrTotals('C', t, c)
        assertEquals("123456780C8765432100000000", ascii(payload))
        assertEquals(0x0C, LrcMode.STD.compute(payload))
        assertEquals(0x0D, LrcMode.STX.compute(payload))
    }

    /** A§9.4: totali 26 B, LRC std 1B, stx 1A. */
    @Test
    fun `totali`() {
        val payload = RequestEncoder.buildCloseOrTotals('T', t, c)
        assertEquals("123456780T8765432100000000", ascii(payload))
        assertEquals(0x1B, LrcMode.STD.compute(payload))
        assertEquals(0x1A, LrcMode.STX.compute(payload))
    }

    /** A§9.4: ultimo esito 22 B, LRC std 08, stx 09. */
    @Test
    fun `ultimo esito G`() {
        val payload = RequestEncoder.buildSendLastResult(t, c)
        assertEquals("123456780G876543210000", ascii(payload))
        assertEquals(0x08, LrcMode.STD.compute(payload))
        assertEquals(0x09, LrcMode.STX.compute(payload))
    }

    /** A§9.4: stampa su ECR E1/E0 con le 4 LRC e il frame std di E1. */
    @Test
    fun `stampa su ECR E1 ed E0`() {
        val e1 = RequestEncoder.buildEcrPrinting(t, true)
        val e0 = RequestEncoder.buildEcrPrinting(t, false)
        assertEquals("123456780E1", ascii(e1))
        assertEquals("123456780E0", ascii(e0))
        assertEquals(listOf(0x33, 0x32, 0x30, 0x31), lrcs(e1))
        assertEquals(listOf(0x32, 0x33, 0x31, 0x30), lrcs(e0))
        assertArrayEquals(
            bytes(0x02, 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38, 0x30, 0x45, 0x31, 0x03, 0x33),
            Frames.app(ascii(e1), LrcMode.STD),
        )
    }

    /** A§9.4: ristampa verso ECR del ticket finanziario. */
    @Test
    fun `ristampa verso ECR`() {
        val payload = RequestEncoder.buildReprint(t, ReprintTarget.ECR, TicketType.LAST_FINANCIAL)
        assertEquals("123456780R10" + "0".repeat(10), ascii(payload))
        assertEquals(22, payload.size)
        assertEquals(
            "123456780R01" + "0".repeat(10),
            ascii(RequestEncoder.buildReprint(t, ReprintTarget.TERMINAL, TicketType.LAST_SERVICE)),
        )
    }

    // ─── A§9.8: cattura Ingenico (stx, TID ed ECR 00000000) ─────────────────────

    /** Riga [1]: P importo 1 tipo 0 coincide byte per byte con il frame catturato (LRC stx 2F). */
    @Test
    fun `pagamento reale riga 1 coincide con la cattura`() {
        val payload = RequestEncoder.encode(PaymentRequest(amountCents = 1), zeroIds)
        assertEquals(Frames.P1, ascii(payload))
        assertEquals(167, payload.size)
        assertEquals(0x2F, LrcMode.STX.compute(payload))
        assertArrayEquals(Frames.app(Frames.P1, LrcMode.STX), Frames.app(ascii(payload), LrcMode.STX))
    }

    /** Riga [13]: P importo 10 tipo debito, LRC stx 2E. */
    @Test
    fun `pagamento reale riga 13 debito`() {
        val payload = RequestEncoder.encode(PaymentRequest(amountCents = 10, paymentType = PaymentType.DEBIT), zeroIds)
        assertEquals("000000000P000000000000100000010" + " ".repeat(128) + "00000000", ascii(payload))
        assertEquals(0x2E, LrcMode.STX.compute(payload))
    }

    /** Vettori derivati per il terminale reale: s 3D, E1 3A, G 09, T 1A, C 0D, R 1C/1D. */
    @Test
    fun `comandi di servizio con TID zero in stx`() {
        fun stx(request: Ecr17Request) = LrcMode.STX.compute(RequestEncoder.encode(request, zeroIds))

        assertEquals(0x3D, stx(StatusRequest))
        assertArrayEquals(
            bytes(0x02, 0x30, 0x30, 0x30, 0x30, 0x30, 0x30, 0x30, 0x30, 0x30, 0x73, 0x03, 0x3D),
            Frames.app(ascii(RequestEncoder.encode(StatusRequest, zeroIds)), LrcMode.STX),
        )
        assertEquals(0x3A, stx(EcrPrintingRequest(enabled = true)))
        assertEquals(0x09, stx(LastResultRequest()))
        assertEquals(0x1A, stx(TotalsRequest()))
        assertEquals(0x0D, stx(CloseSessionRequest()))
        assertEquals(0x1C, stx(ReprintRequest(ReprintTarget.TERMINAL, TicketType.LAST_FINANCIAL)))
        assertEquals(0x1D, stx(ReprintRequest(ReprintTarget.ECR, TicketType.LAST_FINANCIAL)))
    }

    // ─── API tipizzata ──────────────────────────────────────────────────────────

    /** encode usa TID e cash register della config, l'override della richiesta vince sul cash register. */
    @Test
    fun `encode usa gli id della config e l'override del cash register`() {
        val ids = TerminalIds(terminalId = t, cashRegisterId = c)
        assertEquals("123456780T8765432100000000", ascii(RequestEncoder.encode(TotalsRequest(), ids)))
        assertEquals("123456780T0000004200000000", ascii(RequestEncoder.encode(TotalsRequest(cashRegisterId = "42"), ids)))
    }

    /** Il client v1 non invia mai il flag GT: un '1' farebbe attendere al terminale un messaggio U mai inviato. */
    @Test
    fun `encode non imposta mai il flag GT`() {
        val ids = TerminalIds(terminalId = t, cashRegisterId = c)
        val x = ascii(RequestEncoder.encode(PaymentRequest(amountCents = 650, extended = true), ids))
        assertEquals('X', x[9])
        assertEquals('0', x[18])
        val h = ascii(RequestEncoder.encode(CardVerificationRequest(PaymentType.CREDIT), ids))
        assertEquals('0', h[18])
        assertEquals('2', h[22])
    }

    /** Ogni richiesta tipizzata produce lo stesso payload del builder di basso livello corrispondente. */
    @Test
    fun `encode coincide con i builder`() {
        val ids = TerminalIds(terminalId = t, cashRegisterId = c)
        assertArrayEquals(
            RequestEncoder.buildPaymentLike('p', t, c, 1000, PaymentType.OTHER, true, false, "TXT"),
            RequestEncoder.encode(PreAuthRequest(1000, PaymentType.OTHER, cardAlreadyPresent = true, receiptText = "TXT"), ids),
        )
        assertArrayEquals(
            RequestEncoder.buildIncrementalLike('i', t, c, 1000, "123456789", "A"),
            RequestEncoder.encode(IncrementalAuthRequest(1000, "123456789", receiptText = "A"), ids),
        )
        assertArrayEquals(
            RequestEncoder.buildIncrementalLike('c', t, c, 500, "42", ""),
            RequestEncoder.encode(PreAuthClosureRequest(500, "42"), ids),
        )
        assertArrayEquals(RequestEncoder.buildReversal(t, c, "123"), RequestEncoder.encode(ReversalRequest("123"), ids))
        assertArrayEquals(RequestEncoder.buildSendLastResult(t, c), RequestEncoder.encode(LastResultRequest(), ids))
        assertArrayEquals(RequestEncoder.buildCloseOrTotals('C', t, c), RequestEncoder.encode(CloseSessionRequest(), ids))
        assertArrayEquals(RequestEncoder.buildEcrPrinting(t, false), RequestEncoder.encode(EcrPrintingRequest(false), ids))
    }

    // ─── Validazione ────────────────────────────────────────────────────────────

    /** Un TID con lettere è NotDigits: i repo lo inviavano e il terminale rispondeva NAK (A§4.4). */
    @Test
    fun `TID con lettere e NotDigits`() {
        val errors = RequestEncoder.validate(StatusRequest, TerminalIds(terminalId = "12AB"))
        assertEquals(listOf(ValidationError.NotDigits(RequestField.TERMINAL_ID, "12AB")), errors)
    }

    /** Una lettera accentata non è ASCII: errore con l'indice, mai una traslitterazione silenziosa (A§3.4). */
    @Test
    fun `testo con lettera accentata e NonAscii con indice`() {
        val errors = RequestEncoder.validate(PaymentRequest(amountCents = 1, receiptText = "caffè"), zeroIds)
        assertEquals(listOf(ValidationError.NonAscii(RequestField.RECEIPT_TEXT, 4)), errors)
    }

    /** Anche i caratteri di controllo (STX, ETX) sono fuori da 0x20..0x7E: romperebbero il framing. */
    @Test
    fun `testo con carattere di controllo e NonAscii`() {
        val errors = RequestEncoder.validate(PaymentRequest(amountCents = 1, receiptText = "A\u0003B"), zeroIds)
        assertEquals(listOf(ValidationError.NonAscii(RequestField.RECEIPT_TEXT, 1)), errors)
    }

    /** Un codice di preautorizzazione vuoto è Required: altrimenti diventerebbe "000000000", una richiesta valida. */
    @Test
    fun `integrazione con codice vuoto e Required`() {
        assertEquals(
            listOf(ValidationError.Required(RequestField.PRE_AUTH_CODE)),
            RequestEncoder.validate(IncrementalAuthRequest(100, ""), zeroIds),
        )
        assertEquals(
            listOf(ValidationError.Required(RequestField.PRE_AUTH_CODE)),
            RequestEncoder.validate(PreAuthClosureRequest(100, "   "), zeroIds),
        )
        val errors = validationErrors { RequestEncoder.buildIncrementalLike('i', t, c, 100, "", "") }
        assertEquals(listOf(ValidationError.Required(RequestField.PRE_AUTH_CODE)), errors)
    }

    /** Tutte le violazioni insieme, nell'ordine dei campi: la UI le mostra in un colpo solo. */
    @Test
    fun `piu errori restituiti insieme`() {
        val request = PaymentRequest(amountCents = -5, receiptText = "è", cashRegisterId = "12AB")
        val errors = RequestEncoder.validate(request, TerminalIds(terminalId = "123456789"))
        assertEquals(
            listOf(
                ValidationError.FieldOverflow(RequestField.TERMINAL_ID, "123456789", 8),
                ValidationError.NotDigits(RequestField.CASH_REGISTER_ID, "12AB"),
                ValidationError.NegativeAmount(RequestField.AMOUNT),
                ValidationError.NonAscii(RequestField.RECEIPT_TEXT, 0),
            ),
            errors,
        )
        val thrown = validationErrors { RequestEncoder.encode(request, TerminalIds(terminalId = "123456789")) }
        assertEquals(errors, thrown, "encode lancia con gli stessi errori di validate")
    }

    /** STAN con lettere ⇒ NotDigits; cifre non ASCII (es. arabo-indiane) non sono cifre del protocollo. */
    @Test
    fun `STAN non numerico e NotDigits`() {
        assertEquals(
            listOf(ValidationError.NotDigits(RequestField.STAN, "12a")),
            RequestEncoder.validate(ReversalRequest(stan = "12a"), zeroIds),
        )
        assertEquals(
            listOf(ValidationError.NotDigits(RequestField.STAN, "١٢")),
            RequestEncoder.validate(ReversalRequest(stan = "١٢"), zeroIds),
        )
    }

    /** Lo 0 è accettato dal protocollo (la UI lo rifiuta a parte, A§4.3.1). */
    @Test
    fun `importo zero accettato dal protocollo`() {
        assertTrue(RequestEncoder.validate(PaymentRequest(amountCents = 0), zeroIds).isEmpty())
        assertEquals("00000000", ascii(RequestEncoder.encode(PaymentRequest(amountCents = 0), zeroIds)).substring(23, 31))
    }

    /** Proprietà: nessun builder produce un NUL o un byte fuori da 0x20..0x7E (la DLL inviava 0x00, A§4.3.7). */
    @Test
    fun `nessun byte NUL o non stampabile in nessuna richiesta`() {
        val ids = TerminalIds(terminalId = "1", cashRegisterId = "")
        val requests = buildList {
            add(StatusRequest)
            for (type in PaymentType.entries) for (present in listOf(false, true)) for (extended in listOf(false, true)) {
                add(PaymentRequest(0, type, present, "", extended = extended))
                add(PaymentRequest(MAX_AMOUNT_CENTS, type, present, "~ !", "9", extended))
            }
            for (type in PaymentType.entries) add(PreAuthRequest(1, type, receiptText = "x"))
            add(ReversalRequest()); add(ReversalRequest("")); add(ReversalRequest("1"))
            add(IncrementalAuthRequest(1, "1")); add(PreAuthClosureRequest(1, "999999999"))
            for (type in PaymentType.entries) add(CardVerificationRequest(type))
            add(CloseSessionRequest()); add(TotalsRequest()); add(LastResultRequest("7"))
            add(EcrPrintingRequest(true)); add(EcrPrintingRequest(false))
            for (target in ReprintTarget.entries) for (ticket in TicketType.entries) add(ReprintRequest(target, ticket))
        }
        for (request in requests) {
            val payload = RequestEncoder.encode(request, ids)
            val bad = payload.indexOfFirst { (it.toInt() and 0xFF) !in 0x20..0x7E }
            assertEquals(-1, bad, "$request: byte non stampabile all'indice $bad")
        }
        assertEquals(CommandCode.entries.toSet(), requests.map { it.command }.toSet(), "tutti i 13 comandi coperti")
    }
}
