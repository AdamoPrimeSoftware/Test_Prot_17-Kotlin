package it.prime.protocollo17.ecr17.message

import it.prime.protocollo17.ecr17.model.CardType
import it.prime.protocollo17.ecr17.model.CloseSessionResponse
import it.prime.protocollo17.ecr17.model.CommandCode
import it.prime.protocollo17.ecr17.model.DccInfo
import it.prime.protocollo17.ecr17.model.Ecr17Response
import it.prime.protocollo17.ecr17.model.EntryMode
import it.prime.protocollo17.ecr17.model.FieldId
import it.prime.protocollo17.ecr17.model.IncrementalAuthResponse
import it.prime.protocollo17.ecr17.model.PaymentLayout
import it.prime.protocollo17.ecr17.model.PaymentResponse
import it.prime.protocollo17.ecr17.model.PreAuthClosureResponse
import it.prime.protocollo17.ecr17.model.PreAuthResponse
import it.prime.protocollo17.ecr17.model.RawPayload
import it.prime.protocollo17.ecr17.model.ReceiptLine
import it.prime.protocollo17.ecr17.model.ResponseField
import it.prime.protocollo17.ecr17.model.StatusResponse
import it.prime.protocollo17.ecr17.model.TotalsResponse
import it.prime.protocollo17.ecr17.model.Verdict
import it.prime.protocollo17.ecr17.wire.ControlChars
import it.prime.protocollo17.ecr17.wire.PanMasker
import java.math.BigDecimal
import java.time.DateTimeException
import java.time.LocalDateTime

/**
 * Decodifica delle risposte applicative (A§4.5). ISO-8859-1 sui byte, letture tolleranti ai payload corti,
 * numeri convertiti solo se composti esclusivamente da cifre: un payload malformato non lancia mai.
 *
 * Prima, nei repo, un solo parser di pagamento serviva anche `i`, `c`/`v`, `G` e le varianti 92/84 di `E`: i
 * campi dopo il tipo transazione di una `i` venivano letti nelle posizioni di una `e`, il DCC di una `v` andava
 * perso e l'action code di storno e verifica carta non era mai valorizzato (A§4.5.2-4.5.5, L§13.6). Ora ogni
 * layout ha il suo parser e lo smistamento dipende dal codice in pos 10 e dal comando inviato.
 *
 * Il valore grezzo resta sempre accanto a quello tipizzato (L§5.6): [RawPayload] e la lista `fields` con pos/len
 * servono alla vista "chiave/valore con posizione" e ai confronti col terminale reale. Con `maskPan = true` il PAN
 * è mascherato PRIMA di qualsiasi lettura, quindi lo è allo stesso modo in `pan`, `raw` e `fields` (M9).
 */
object ResponseParser {

    // ─── Costanti di layout ─────────────────────────────────────────────────────

    /** Codice messaggio di ripiego quando il payload non arriva alla pos 10 (solo chiamate dirette ai parser). */
    private const val MISSING_REPLY_CODE = '?'

    private const val KO_RESULT = "01"
    private const val OK_RESULT = "00"

    private const val PAN_POS = 13
    private const val PAN_LEN = 19

    /** Coda di fine scontrino: sei 0x7D seguiti da ESC (A§4.5.9). */
    private val RECEIPT_END_SENTINEL: String =
        ControlChars.RECEIPT_NEWLINE.toChar().toString().repeat(6) + ControlChars.ESC.toChar()

    // ─── Smistamento ────────────────────────────────────────────────────────────

    /**
     * Smistamento per codice in pos 10 con il contesto del comando inviato. Restituisce null per codici
     * senza parser v1 ('S' riga scontrino, 'U', 'K', altro): la sessione li tratta prima di chiamare parse.
     * Null anche per un payload che non arriva alla pos 10 (non ha un codice).
     *
     * Non verifica che il codice sia tra quelli attesi per [sent]: è compito della sessione (U8, M4) e qui il
     * comando serve solo a scegliere il layout di una 'E'.
     */
    fun parse(payload: ByteArray, sent: CommandCode, maskPan: Boolean = true): Ecr17Response? {
        if (payload.size < 10) return null
        return when (val code = (payload[9].toInt() and 0xFF).toChar()) {
            'E', 'V' -> parsePayment(payload, paymentLayoutFor(sent, code, payload.size), maskPan)
            'e' -> parsePreAuth(payload, maskPan)
            'i' -> parseIncrementalAuth(payload, maskPan)
            'c', 'v' -> parsePreAuthClosure(payload, maskPan)
            's' -> parseStatus(payload)
            'T' -> parseTotals(payload)
            'C' -> parseCloseSession(payload)
            else -> null
        }
    }

    /**
     * Layout di una 'E'/'V' in base al comando inviato (per G: in base alla lunghezza).
     *
     * La 'E' ha la stessa lettera per P, X, S e H ma tre lunghezze e campi diversi (A§4.5.2): solo il comando
     * inviato dice quale. Con `G` il comando originale non è noto e si usa la lunghezza; per un comando che non
     * prevede 'E' (non dovrebbe arrivare qui, la sessione lo scarta come orfano) vale la stessa regola.
     */
    fun paymentLayoutFor(sent: CommandCode, replyCode: Char, length: Int): PaymentLayout {
        if (replyCode == 'V') return PaymentLayout.DCC_117
        return when (sent) {
            CommandCode.PAYMENT_EXTENDED -> PaymentLayout.EXTENDED_92
            CommandCode.REVERSAL -> PaymentLayout.REVERSAL_84
            CommandCode.CARD_VERIFICATION -> PaymentLayout.CARD_VERIFICATION_71
            CommandCode.PAYMENT -> PaymentLayout.BASE_71
            else -> when {
                length >= PaymentLayout.EXTENDED_92.nominalLength -> PaymentLayout.EXTENDED_92
                length == PaymentLayout.REVERSAL_84.nominalLength -> PaymentLayout.REVERSAL_84
                else -> PaymentLayout.BASE_71
            }
        }
    }

    // ─── Risposte di transazione ────────────────────────────────────────────────

    /**
     * Risposta 'E'/'V' (A§4.5.1-4.5.2). Ramo KO SOLO con esito "01" (con 05, 09 o sconosciuti si legge il layout
     * positivo, come i repo): l'asimmetria rispetto a 'C' è della spec (A§4.5.7).
     */
    fun parsePayment(payload: ByteArray, layout: PaymentLayout, maskPan: Boolean = true): PaymentResponse {
        val ko = resultOf(payload) == KO_RESULT
        val f = FieldCollector(source(payload, maskPan, panReadable = !ko))
        val head = f.header()
        // Storno OK: pos 35-40 riservate, nessun codice autorizzazione (A§4.5.2).
        val base = f.base71(ko, readAuthCode = layout != PaymentLayout.REVERSAL_84)

        var actionCode: String? = null
        var hostAmountCents: Long? = null
        var originalAmountCents: Long? = null
        var dcc: DccInfo? = null
        when (layout) {
            PaymentLayout.BASE_71 -> Unit
            PaymentLayout.CARD_VERIFICATION_71 ->
                if (ko) actionCode = f.raw(FieldId.ACTION_CODE, 37, 3).nullIfBlank()
            PaymentLayout.EXTENDED_92 -> {
                actionCode = f.raw(FieldId.ACTION_CODE, 72, 3).nullIfBlank()
                hostAmountCents = digitsToLong(f.raw(FieldId.HOST_AMOUNT, 75, 8), 8)
            }
            PaymentLayout.REVERSAL_84 -> actionCode = f.raw(FieldId.ACTION_CODE, 72, 3).nullIfBlank()
            PaymentLayout.DCC_117 -> {
                actionCode = f.raw(FieldId.ACTION_CODE, 72, 3).nullIfBlank()
                originalAmountCents = digitsToLong(f.raw(FieldId.ORIGINAL_AMOUNT, 75, 8), 8)
                dcc = f.dcc(flagPos = 83)
            }
        }

        return PaymentResponse(
            raw = f.rawPayload(), terminalId = head.terminalId, replyCode = head.replyCode, fields = f.fields(),
            resultCode = head.resultCode, verdict = Verdict.fromResultCode(head.resultCode),
            layout = layout,
            pan = base.pan, entryModeRaw = base.entryModeRaw, entryMode = base.entryMode,
            authCode = base.authCode, hostDateTimeRaw = base.hostDateTimeRaw,
            errorDescription = base.errorDescription,
            cardTypeRaw = base.cardTypeRaw, cardType = base.cardType,
            acquirerId = base.acquirerId, stan = base.stan, onlineId = base.onlineId,
            actionCode = actionCode,
            hostAmountCents = hostAmountCents,
            originalAmountCents = originalAmountCents,
            dcc = dcc,
        )
    }

    /**
     * Risposta 'e' alla preautorizzazione (106 B, A§4.5.3). Nel ramo OK la pos 48 è l'ultima cifra dell'importo
     * preautorizzato, NON il tipo carta: leggerla come tipo carta trasformerebbe un importo che finisce in 1 in
     * "debito" (regressione dei repo, A§9.5). Il tipo carta si legge solo nel ramo KO.
     */
    fun parsePreAuth(payload: ByteArray, maskPan: Boolean = true): PreAuthResponse {
        val ko = resultOf(payload) == KO_RESULT
        val f = FieldCollector(source(payload, maskPan, panReadable = !ko))
        val head = f.header()

        var pan: String? = null
        var entryModeRaw: String? = null
        var authCode: String? = null
        var amountRaw: String? = null
        var preAuthCode: String? = null
        val actionCode: String?
        var hostDateTimeRaw: String? = null
        var errorDescription: String? = null
        var cardTypeRaw: String? = null
        if (!ko) {
            pan = f.raw(FieldId.PAN, PAN_POS, PAN_LEN).nullIfBlank()
            entryModeRaw = f.trimmed(FieldId.ENTRY_MODE, 32, 3)
            authCode = f.trimmed(FieldId.AUTH_CODE, 35, 6)
            amountRaw = f.raw(FieldId.PRE_AUTH_AMOUNT, 41, 8).nullIfBlank()
            preAuthCode = f.raw(FieldId.PRE_AUTH_CODE, 49, 9).nullIfBlank()
            actionCode = f.raw(FieldId.ACTION_CODE, 58, 3).nullIfBlank()
            hostDateTimeRaw = f.raw(FieldId.HOST_DATE_TIME, 61, 7).nullIfBlank()
        } else {
            errorDescription = f.trimmed(FieldId.ERROR_DESCRIPTION, 13, 24)
            actionCode = f.raw(FieldId.ACTION_CODE, 37, 3).nullIfBlank()
            cardTypeRaw = f.raw(FieldId.CARD_TYPE, 48, 1).nullIfBlank()
        }
        val acquirerId = f.trimmed(FieldId.ACQUIRER_ID, 72, 11)
        val stan = f.raw(FieldId.STAN, 83, 6).nullIfBlank()
        val onlineId = f.raw(FieldId.ONLINE_ID, 89, 6).nullIfBlank()

        return PreAuthResponse(
            raw = f.rawPayload(), terminalId = head.terminalId, replyCode = head.replyCode, fields = f.fields(),
            resultCode = head.resultCode, verdict = Verdict.fromResultCode(head.resultCode),
            pan = pan, entryModeRaw = entryModeRaw, entryMode = entryModeRaw?.let(EntryMode::fromWire),
            authCode = authCode,
            preAuthorizedAmountRaw = amountRaw, preAuthorizedAmountCents = amountRaw?.let { digitsToLong(it, 8) },
            preAuthCode = preAuthCode, actionCode = actionCode,
            hostDateTimeRaw = hostDateTimeRaw, errorDescription = errorDescription,
            cardTypeRaw = cardTypeRaw, cardType = cardTypeRaw?.let(CardType::fromWire),
            acquirerId = acquirerId, stan = stan, onlineId = onlineId,
        )
    }

    /**
     * Risposta 'i' all'integrazione (75 B, A§4.5.4): layout proprio, nessun ramo KO né descrizione errore. Per
     * questo il PAN in 13/19 si legge (e si maschera) con QUALSIASI esito, anche "01".
     */
    fun parseIncrementalAuth(payload: ByteArray, maskPan: Boolean = true): IncrementalAuthResponse {
        val f = FieldCollector(source(payload, maskPan, panReadable = true))
        val head = f.header()
        val pan = f.raw(FieldId.PAN, PAN_POS, PAN_LEN).nullIfBlank()
        val entryModeRaw = f.trimmed(FieldId.ENTRY_MODE, 32, 3)
        val acquirerId = f.trimmed(FieldId.ACQUIRER_ID, 35, 11)
        val authCode = f.trimmed(FieldId.AUTH_CODE, 46, 6)
        val stan = f.raw(FieldId.STAN, 52, 6).nullIfBlank()
        val onlineId = f.raw(FieldId.ONLINE_ID, 58, 6).nullIfBlank()
        val hostDateTimeRaw = f.raw(FieldId.HOST_DATE_TIME, 64, 7).nullIfBlank()
        val actionCode = f.raw(FieldId.ACTION_CODE, 71, 3).nullIfBlank()

        return IncrementalAuthResponse(
            raw = f.rawPayload(), terminalId = head.terminalId, replyCode = head.replyCode, fields = f.fields(),
            resultCode = head.resultCode, verdict = Verdict.fromResultCode(head.resultCode),
            pan = pan, entryModeRaw = entryModeRaw, entryMode = entryModeRaw?.let(EntryMode::fromWire),
            acquirerId = acquirerId, authCode = authCode,
            stan = stan, onlineId = onlineId,
            hostDateTimeRaw = hostDateTimeRaw, actionCode = actionCode,
        )
    }

    /**
     * Risposta 'c' (74 B) o 'v' con DCC (109 B) alla chiusura di preautorizzazione (A§4.5.5): pos 1-71 come la 'E'
     * di pagamento, action code 72/3 e, solo per 'v', il blocco DCC da pos 75 (non 83 come nella 'V').
     */
    fun parsePreAuthClosure(payload: ByteArray, maskPan: Boolean = true): PreAuthClosureResponse {
        val ko = resultOf(payload) == KO_RESULT
        val f = FieldCollector(source(payload, maskPan, panReadable = !ko))
        val head = f.header()
        val base = f.base71(ko, readAuthCode = true)
        val actionCode = f.raw(FieldId.ACTION_CODE, 72, 3).nullIfBlank()
        val dcc = if (head.replyCode == 'v') f.dcc(flagPos = 75) else null

        return PreAuthClosureResponse(
            raw = f.rawPayload(), terminalId = head.terminalId, replyCode = head.replyCode, fields = f.fields(),
            resultCode = head.resultCode, verdict = Verdict.fromResultCode(head.resultCode),
            pan = base.pan, entryModeRaw = base.entryModeRaw, entryMode = base.entryMode,
            authCode = base.authCode, hostDateTimeRaw = base.hostDateTimeRaw,
            errorDescription = base.errorDescription, cardTypeRaw = base.cardTypeRaw, cardType = base.cardType,
            acquirerId = base.acquirerId, stan = base.stan, onlineId = base.onlineId,
            actionCode = actionCode, dcc = dcc,
        )
    }

    // ─── Risposte di servizio ───────────────────────────────────────────────────

    /**
     * Risposta 's' (A§4.5.6). La data/ora `DDMMYYhhmm` diventa un LocalDateTime validato (anno 2000+YY) e null se
     * non è una data reale: i repo producevano una stringa non validata (rust) o normalizzavano in ora locale
     * valori fuori range (C++, A§8 D11).
     */
    fun parseStatus(payload: ByteArray): StatusResponse {
        val f = FieldCollector(payload)
        val terminalId = f.raw(FieldId.TERMINAL_ID, 1, 8)
        val replyCode = f.replyCode()
        val dateTimeRaw = f.raw(FieldId.TERMINAL_DATE_TIME, 21, 10)
        val statusChar = f.raw(FieldId.STATUS, 31, 1).firstOrNull()
        val release = f.rest(FieldId.SOFTWARE_RELEASE, 32).trimEnd(' ')

        return StatusResponse(
            raw = f.rawPayload(), terminalId = terminalId, replyCode = replyCode, fields = f.fields(),
            dateTimeRaw = dateTimeRaw, dateTime = parseDateTime(dateTimeRaw),
            statusDigit = if (statusChar != null && statusChar in '0'..'9') statusChar - '0' else -1,
            softwareRelease = release,
        )
    }

    /** Risposta 'T' (34 B, A§4.5.7): il totale POS si legge con qualsiasi esito. */
    fun parseTotals(payload: ByteArray): TotalsResponse {
        val f = FieldCollector(payload)
        val head = f.header()
        val posTotalRaw = f.raw(FieldId.POS_TOTAL, 13, 16)

        return TotalsResponse(
            raw = f.rawPayload(), terminalId = head.terminalId, replyCode = head.replyCode, fields = f.fields(),
            resultCode = head.resultCode, verdict = Verdict.fromResultCode(head.resultCode),
            posTotalRaw = posTotalRaw, posTotalCents = digitsToLong(posTotalRaw, 16),
        )
    }

    /** Risposta 'C' (44 B, A§4.5.7): ramo positivo SOLO con esito "00", al contrario dei pagamenti. */
    fun parseCloseSession(payload: ByteArray): CloseSessionResponse {
        val f = FieldCollector(payload)
        val head = f.header()
        var posTotalRaw: String? = null
        var hostTotalRaw: String? = null
        var errorDescription: String? = null
        var actionCode: String? = null
        if (head.resultCode == OK_RESULT) {
            posTotalRaw = f.raw(FieldId.POS_TOTAL, 13, 16).nullIfBlank()
            hostTotalRaw = f.raw(FieldId.HOST_TOTAL, 29, 16).nullIfBlank()
        } else {
            errorDescription = f.trimmed(FieldId.ERROR_DESCRIPTION, 13, 19)
            actionCode = f.raw(FieldId.ACTION_CODE, 32, 3).nullIfBlank()
        }

        return CloseSessionResponse(
            raw = f.rawPayload(), terminalId = head.terminalId, replyCode = head.replyCode, fields = f.fields(),
            resultCode = head.resultCode, verdict = Verdict.fromResultCode(head.resultCode),
            posTotalRaw = posTotalRaw, posTotalCents = posTotalRaw?.let { digitsToLong(it, 16) },
            hostTotalRaw = hostTotalRaw, hostTotalCents = hostTotalRaw?.let { digitsToLong(it, 16) },
            errorDescription = errorDescription, actionCode = actionCode,
        )
    }

    /**
     * Riga di scontrino 'S' (A§4.5.9): testo dalla pos 11; 0x7D → '\n'; 0x7F imposta doubleHeight ed è rimosso;
     * la coda 7D×6 + 1B segna la fine dello scontrino ed è rimossa dal testo.
     *
     * maskPan = true: PanMasker.maskDigitRuns su raw e text (alcuni terminali stampano il PAN in chiaro). Il testo
     * si maschera DOPO la conversione: un PAN spezzato da un 0x7F diventa una sola sequenza di cifre solo lì.
     */
    fun parseReceiptLine(payload: ByteArray, maskPan: Boolean = true): ReceiptLine {
        val raw = FieldReader(payload).restFrom(11)
        val isEnd = raw.endsWith(RECEIPT_END_SENTINEL)
        val body = if (isEnd) raw.dropLast(RECEIPT_END_SENTINEL.length) else raw
        val doubleChar = ControlChars.RECEIPT_DOUBLE.toChar()
        val text = buildString(body.length) {
            for (c in body) {
                when (c) {
                    ControlChars.RECEIPT_NEWLINE.toChar() -> append('\n')
                    doubleChar -> Unit
                    else -> append(c)
                }
            }
        }
        return ReceiptLine(
            raw = if (maskPan) PanMasker.maskDigitRuns(raw) else raw,
            text = if (maskPan) PanMasker.maskDigitRuns(text) else text,
            doubleHeight = body.indexOf(doubleChar) >= 0,
            isEnd = isEnd,
        )
    }

    // ─── Supporto ───────────────────────────────────────────────────────────────

    /** Esito (pos 11-12) letto dal payload ORIGINALE: decide il ramo prima del mascheramento. */
    private fun resultOf(payload: ByteArray): String = FieldReader(payload).at(11, 2)

    /**
     * Byte da cui leggere. Con maskPan il PAN viene mascherato una volta sola qui, prima di ogni lettura, così
     * `pan`, `raw` e `fields` coincidono (M9). Oltre alla regola per codice di PanMasker, se il ramo scelto dal
     * parser legge un PAN in 13/19 quella zona passa anche da maskFixedWidth (idempotente): anche un parser
     * chiamato direttamente su un codice inatteso non restituisce mai un PAN in chiaro.
     */
    private fun source(payload: ByteArray, maskPan: Boolean, panReadable: Boolean): ByteArray {
        if (!maskPan) return payload
        // copyOf: la regola "copia identica" di PanMasker non garantisce un array nuovo, e qui lo si modifica.
        val masked = PanMasker.maskApplicationPayload(payload).copyOf()
        if (panReadable && masked.size >= PAN_POS) {
            val from = PAN_POS - 1
            val count = minOf(PAN_LEN, masked.size - from)
            val field = String(masked, from, count, Charsets.ISO_8859_1)
            val maskedField = PanMasker.maskFixedWidth(field)
            if (maskedField.length == count) maskedField.toByteArray(Charsets.ISO_8859_1).copyInto(masked, from)
        }
        return masked
    }

    /** Numero solo se il campo è COMPLETO e di sole cifre (A§8 D10): un importo troncato o "00000650AB" è null. */
    private fun digitsToLong(raw: String, width: Int): Long? =
        if (raw.length == width && raw.all { it in '0'..'9' }) raw.toLongOrNull() else null

    /** DDMMYYhhmm ⇒ LocalDateTime (anno 2000+YY), null se non sono 10 cifre o non è una data reale. */
    private fun parseDateTime(raw: String): LocalDateTime? {
        if (raw.length != 10 || !raw.all { it in '0'..'9' }) return null
        fun part(i: Int) = raw.substring(i, i + 2).toInt()
        return try {
            LocalDateTime.of(2000 + part(4), part(2), part(0), part(6), part(8))
        } catch (@Suppress("UNUSED_PARAMETER") e: DateTimeException) {
            null   // 31/02, ore 25…: il grezzo resta in dateTimeRaw
        }
    }

    /** null se il campo è vuoto o di soli spazi; altrimenti il valore così come sul filo. */
    private fun String.nullIfBlank(): String? = takeIf { it.trim(' ').isNotEmpty() }

    private class Header(val terminalId: String, val replyCode: Char, val resultCode: String)

    /** Campi comuni alle risposte con layout 'E' da 71 byte ('E' nelle sue varianti, 'c', 'v'). */
    private class BaseFields(
        val pan: String?, val entryModeRaw: String?, val authCode: String?, val hostDateTimeRaw: String?,
        val errorDescription: String?, val cardTypeRaw: String?, val acquirerId: String?,
        val stan: String?, val onlineId: String?,
    ) {
        val entryMode: EntryMode? get() = entryModeRaw?.let(EntryMode::fromWire)
        val cardType: CardType? get() = cardTypeRaw?.let(CardType::fromWire)
    }

    /**
     * Lettore che registra ogni campo letto in `fields` con pos/len e valore grezzo (come sul filo, già
     * mascherato). Un campo oltre la fine del payload non compare: la vista mostra solo ciò che è arrivato.
     */
    private class FieldCollector(private val bytes: ByteArray) {
        private val reader = FieldReader(bytes)
        private val collected = mutableListOf<ResponseField>()

        fun raw(id: FieldId, pos: Int, len: Int): String {
            val value = reader.at(pos, len)
            if (value.isNotEmpty()) collected += ResponseField(id, pos, len, value)
            return value
        }

        /** Campo di testo allineato a sinistra: in `fields` il grezzo, al chiamante il valore senza spazi finali. */
        fun trimmed(id: FieldId, pos: Int, len: Int): String? = raw(id, pos, len).trimEnd(' ').ifEmpty { null }

        fun rest(id: FieldId, pos: Int): String {
            val value = reader.restFrom(pos)
            if (value.isNotEmpty()) collected += ResponseField(id, pos, value.length, value)
            return value
        }

        fun replyCode(): Char = raw(FieldId.REPLY_CODE, 10, 1).firstOrNull() ?: MISSING_REPLY_CODE

        fun header(): Header = Header(
            terminalId = raw(FieldId.TERMINAL_ID, 1, 8),
            replyCode = replyCode(),
            resultCode = raw(FieldId.RESULT_CODE, 11, 2),
        )

        /**
         * Pos 13-71 della 'E' base (A§4.5.1): ramo OK (esito ≠ "01") PAN, entry mode, autorizzazione, data/ora host;
         * ramo KO descrizione 13/24 (37-47 riservato); comuni tipo carta, acquirer, STAN, online id.
         */
        fun base71(ko: Boolean, readAuthCode: Boolean): BaseFields {
            var pan: String? = null
            var entryModeRaw: String? = null
            var authCode: String? = null
            var hostDateTimeRaw: String? = null
            var errorDescription: String? = null
            if (!ko) {
                pan = raw(FieldId.PAN, PAN_POS, PAN_LEN).nullIfBlank()
                entryModeRaw = trimmed(FieldId.ENTRY_MODE, 32, 3)
                if (readAuthCode) authCode = trimmed(FieldId.AUTH_CODE, 35, 6)
                hostDateTimeRaw = raw(FieldId.HOST_DATE_TIME, 41, 7).nullIfBlank()
            } else {
                errorDescription = trimmed(FieldId.ERROR_DESCRIPTION, 13, 24)
            }
            return BaseFields(
                pan = pan, entryModeRaw = entryModeRaw, authCode = authCode, hostDateTimeRaw = hostDateTimeRaw,
                errorDescription = errorDescription,
                cardTypeRaw = raw(FieldId.CARD_TYPE, 48, 1).nullIfBlank(),
                acquirerId = trimmed(FieldId.ACQUIRER_ID, 49, 11),
                stan = raw(FieldId.STAN, 60, 6).nullIfBlank(),
                onlineId = raw(FieldId.ONLINE_ID, 66, 6).nullIfBlank(),
            )
        }

        /**
         * Blocco DCC: flag (1), tasso (8, SEMPRE 4 decimali impliciti), valuta (3), importo (12), precisione (1,
         * decimali dell'IMPORTO e non del tasso: il commento contrario in rust è sbagliato, A§4.5.1). Assente (null)
         * se il payload non arriva al flag.
         */
        fun dcc(flagPos: Int): DccInfo? {
            if (reader.length < flagPos) return null
            val flag = raw(FieldId.DCC_APPLIED, flagPos, 1)
            val rateRaw = raw(FieldId.DCC_RATE, flagPos + 1, 8)
            val currency = raw(FieldId.DCC_CURRENCY, flagPos + 9, 3)
            val amountRaw = raw(FieldId.DCC_AMOUNT, flagPos + 12, 12)
            val precisionRaw = raw(FieldId.DCC_PRECISION, flagPos + 24, 1)
            return DccInfo(
                applied = flag == "1",
                rateRaw = rateRaw,
                rate = digitsToLong(rateRaw, 8)?.let { BigDecimal.valueOf(it).movePointLeft(4) },
                currencyCode = currency.trimEnd(' '),
                amountRaw = amountRaw,
                amountMinor = digitsToLong(amountRaw, 12),
                precisionRaw = precisionRaw,
                precision = digitsToLong(precisionRaw, 1)?.toInt(),
            )
        }

        fun fields(): List<ResponseField> = collected.sortedBy { it.pos }

        fun rawPayload(): RawPayload = RawPayload(String(bytes, Charsets.ISO_8859_1))
    }
}
