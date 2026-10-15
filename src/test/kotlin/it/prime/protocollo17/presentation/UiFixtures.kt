package it.prime.protocollo17.presentation

import it.prime.protocollo17.domain.model.journal.JournalEntry
import it.prime.protocollo17.domain.model.journal.JournalPhase
import it.prime.protocollo17.domain.model.journal.JournalRecord
import it.prime.protocollo17.ecr17.event.ProtocolWarning
import it.prime.protocollo17.ecr17.model.AckMode
import it.prime.protocollo17.ecr17.model.CardType
import it.prime.protocollo17.ecr17.model.CardVerificationRequest
import it.prime.protocollo17.ecr17.model.CloseSessionRequest
import it.prime.protocollo17.ecr17.model.CloseSessionResponse
import it.prime.protocollo17.ecr17.model.CommandCode
import it.prime.protocollo17.ecr17.model.CommandOutcome
import it.prime.protocollo17.ecr17.model.Ecr17Request
import it.prime.protocollo17.ecr17.model.Ecr17Response
import it.prime.protocollo17.ecr17.model.EcrPrintingRequest
import it.prime.protocollo17.ecr17.model.Endpoint
import it.prime.protocollo17.ecr17.model.EntryMode
import it.prime.protocollo17.ecr17.model.ExchangePhase
import it.prime.protocollo17.ecr17.model.ExchangeTrace
import it.prime.protocollo17.ecr17.model.FieldId
import it.prime.protocollo17.ecr17.model.IncrementalAuthRequest
import it.prime.protocollo17.ecr17.model.LastResultRequest
import it.prime.protocollo17.ecr17.model.PaymentLayout
import it.prime.protocollo17.ecr17.model.PaymentRequest
import it.prime.protocollo17.ecr17.model.PaymentResponse
import it.prime.protocollo17.ecr17.model.PhaseStamp
import it.prime.protocollo17.ecr17.model.PreAuthClosureRequest
import it.prime.protocollo17.ecr17.model.PreAuthRequest
import it.prime.protocollo17.ecr17.model.PreAuthResponse
import it.prime.protocollo17.ecr17.model.RawPayload
import it.prime.protocollo17.ecr17.model.ReceiptLine
import it.prime.protocollo17.ecr17.model.ReprintRequest
import it.prime.protocollo17.ecr17.model.ResponseField
import it.prime.protocollo17.ecr17.model.ReversalRequest
import it.prime.protocollo17.ecr17.model.StatusRequest
import it.prime.protocollo17.ecr17.model.StatusResponse
import it.prime.protocollo17.ecr17.model.TotalsRequest
import it.prime.protocollo17.ecr17.model.TotalsResponse
import it.prime.protocollo17.ecr17.model.Verdict
import it.prime.protocollo17.ecr17.profile.TerminalProfileId
import it.prime.protocollo17.ecr17.testing.Frames
import it.prime.protocollo17.presentation.state.FinishedRun
import it.prime.protocollo17.presentation.state.RunOrigin
import it.prime.protocollo17.presentation.state.RunningCommand
import java.time.LocalDateTime

/**
 * Dati di prova dei test di WP6, costruiti a mano: il parser (WP2) non c'è ancora e, soprattutto, i test della
 * presentation devono restare indipendenti dalle sue regole. Le risposte riprendono i vettori di Frames (E22, E4,
 * T34) e mettono i campi alle posizioni di A§4.5, con il PAN già mascherato come lo consegna il parser (M9).
 */
internal object UiFixtures {

    val ENDPOINT = Endpoint("192.168.1.131", 5577)

    // ─── Esiti ────────────────────────────────────────────────────────────────────

    fun trace(
        command: CommandCode,
        sends: Int = 1,
        naksReceived: Int = 0,
        timeoutRetransmissions: Int = 0,
        ackMode: AckMode? = AckMode.EXPLICIT,
        warnings: List<ProtocolWarning> = emptyList(),
        receipt: List<ReceiptLine> = emptyList(),
        startedAt: Long = 1_000,
        finishedAt: Long = 2_000,
    ) = ExchangeTrace(
        exchangeId = 1,
        command = command,
        endpoint = ENDPOINT,
        profile = TerminalProfileId.INGENICO,
        optionsSummary = "stx/WIRE rx=TOLERANT/ANY ack=IMPLICIT_GO_AHEAD conn=PER_COMMAND",
        sends = sends,
        anyMainFrameWriteAttempted = sends > 0,
        naksReceived = naksReceived,
        timeoutRetransmissions = timeoutRetransmissions,
        ackMode = ackMode,
        lateAck = false,
        phases = listOf(
            PhaseStamp(ExchangePhase.CONNECTING, startedAt),
            PhaseStamp(ExchangePhase.SENDING, startedAt + 10),
            PhaseStamp(ExchangePhase.COMPLETED, finishedAt),
        ),
        receipt = receipt,
        warnings = warnings,
        resultRetransmissions = 0,
        replayedAfterReconnect = false,
        printingSetupSent = false,
        startedAt = startedAt,
        finishedAt = finishedAt,
    )

    /** Risposta 'E'/'V' letta alle posizioni di A§4.5.1 (ramo KO solo con esito "01", come la spec). */
    fun payment(raw: String = Frames.E22, layout: PaymentLayout = PaymentLayout.BASE_71): PaymentResponse {
        fun at(pos: Int, len: Int) = raw.substring(pos - 1, minOf(raw.length, pos - 1 + len))
        val code = at(11, 2)
        val ko = code == "01"
        val fields = buildList {
            add(ResponseField(FieldId.TERMINAL_ID, 1, 8, at(1, 8)))
            add(ResponseField(FieldId.REPLY_CODE, 10, 1, at(10, 1)))
            add(ResponseField(FieldId.RESULT_CODE, 11, 2, code))
            if (ko) {
                add(ResponseField(FieldId.ERROR_DESCRIPTION, 13, 24, at(13, 24)))
            } else {
                add(ResponseField(FieldId.PAN, 13, 19, at(13, 19)))
                add(ResponseField(FieldId.ENTRY_MODE, 32, 3, at(32, 3)))
                add(ResponseField(FieldId.AUTH_CODE, 35, 6, at(35, 6)))
                add(ResponseField(FieldId.HOST_DATE_TIME, 41, 7, at(41, 7)))
            }
            add(ResponseField(FieldId.CARD_TYPE, 48, 1, at(48, 1)))
            add(ResponseField(FieldId.ACQUIRER_ID, 49, 11, at(49, 11)))
            add(ResponseField(FieldId.STAN, 60, 6, at(60, 6)))
            add(ResponseField(FieldId.ONLINE_ID, 66, 6, at(66, 6)))
        }
        return PaymentResponse(
            raw = RawPayload(raw), terminalId = at(1, 8), replyCode = raw[9], fields = fields,
            resultCode = code, verdict = Verdict.fromResultCode(code), layout = layout,
            pan = if (ko) null else at(13, 19),
            entryModeRaw = if (ko) null else at(32, 3).trimEnd(),
            entryMode = if (ko) null else EntryMode.fromWire(at(32, 3)),
            authCode = if (ko) null else at(35, 6).trimEnd(),
            hostDateTimeRaw = if (ko) null else at(41, 7),
            errorDescription = if (ko) at(13, 24).trimEnd() else null,
            cardTypeRaw = at(48, 1), cardType = CardType.fromWire(at(48, 1)),
            acquirerId = at(49, 11).trimEnd(), stan = at(60, 6), onlineId = at(66, 6),
            actionCode = null, hostAmountCents = null, originalAmountCents = null, dcc = null,
        )
    }

    /** 'e' approvata (106 B, A§4.5.3) con codice di preautorizzazione 123456789. */
    const val PRE_AUTH_OK = "02575733" + "0" + "e" + "00" + "***************4768" + "CLI" + "A12345" +
        "00001000" + "123456789" + "000" + "1231200" + "0000" + "00000000023" + "000007" + "000077" + "000000000000"

    fun preAuth(raw: String = PRE_AUTH_OK): PreAuthResponse {
        fun at(pos: Int, len: Int) = raw.substring(pos - 1, minOf(raw.length, pos - 1 + len))
        val code = at(11, 2)
        return PreAuthResponse(
            raw = RawPayload(raw), terminalId = at(1, 8), replyCode = raw[9],
            fields = listOf(
                ResponseField(FieldId.RESULT_CODE, 11, 2, code),
                ResponseField(FieldId.PAN, 13, 19, at(13, 19)),
                ResponseField(FieldId.PRE_AUTH_AMOUNT, 41, 8, at(41, 8)),
                ResponseField(FieldId.PRE_AUTH_CODE, 49, 9, at(49, 9)),
                ResponseField(FieldId.STAN, 83, 6, at(83, 6)),
            ),
            resultCode = code, verdict = Verdict.fromResultCode(code),
            pan = at(13, 19), entryModeRaw = at(32, 3), entryMode = EntryMode.fromWire(at(32, 3)),
            authCode = at(35, 6), preAuthorizedAmountRaw = at(41, 8), preAuthorizedAmountCents = at(41, 8).toLong(),
            preAuthCode = at(49, 9), actionCode = at(58, 3), hostDateTimeRaw = at(61, 7), errorDescription = null,
            cardTypeRaw = null, cardType = null, acquirerId = at(72, 11).trimEnd(), stan = at(83, 6), onlineId = at(89, 6),
        )
    }

    /** 's' con stato 2 (operativo), data 14/10/2026 15:35 e release V1.2.3 (A§4.5.6). */
    fun status(digit: Int = 2): StatusResponse {
        val raw = "025757330s" + "0000000000" + "1410261535" + digit + "V1.2.3  "
        return StatusResponse(
            raw = RawPayload(raw), terminalId = "02575733", replyCode = 's',
            fields = listOf(ResponseField(FieldId.STATUS, 31, 1, digit.toString())),
            dateTimeRaw = "1410261535", dateTime = LocalDateTime.of(2026, 10, 14, 15, 35),
            statusDigit = digit, softwareRelease = "V1.2.3",
        )
    }

    fun totals(): TotalsResponse = TotalsResponse(
        raw = RawPayload(Frames.T34), terminalId = "02575733", replyCode = 'T',
        fields = listOf(ResponseField(FieldId.POS_TOTAL, 13, 16, "0000000000000150")),
        resultCode = "00", verdict = Verdict.APPROVED, posTotalRaw = "0000000000000150", posTotalCents = 150,
    )

    fun closeSession(): CloseSessionResponse = CloseSessionResponse(
        raw = RawPayload("025757330C00" + "0000000000000150" + "0000000000000150"), terminalId = "02575733",
        replyCode = 'C', fields = emptyList(), resultCode = "00", verdict = Verdict.APPROVED,
        posTotalRaw = "0000000000000150", posTotalCents = 150, hostTotalRaw = "0000000000000150", hostTotalCents = 150,
        errorDescription = null, actionCode = null,
    )

    fun answered(command: CommandCode, response: Ecr17Response, trace: ExchangeTrace = trace(command)) =
        CommandOutcome.Answered(command, response, trace)

    // ─── Richieste e comandi conclusi ─────────────────────────────────────────────

    fun requestFor(command: CommandCode): Ecr17Request = when (command) {
        CommandCode.STATUS -> StatusRequest
        CommandCode.PAYMENT -> PaymentRequest(amountCents = 10)
        CommandCode.PAYMENT_EXTENDED -> PaymentRequest(amountCents = 10, extended = true)
        CommandCode.REVERSAL -> ReversalRequest()
        CommandCode.PRE_AUTH -> PreAuthRequest(amountCents = 1_000)
        CommandCode.INCREMENTAL_AUTH -> IncrementalAuthRequest(amountCents = 500, originalPreAuthCode = "123456789")
        CommandCode.PRE_AUTH_CLOSURE -> PreAuthClosureRequest(amountCents = 1_500, originalPreAuthCode = "123456789")
        CommandCode.CARD_VERIFICATION -> CardVerificationRequest()
        CommandCode.CLOSE_SESSION -> CloseSessionRequest()
        CommandCode.TOTALS -> TotalsRequest()
        CommandCode.LAST_RESULT -> LastResultRequest()
        CommandCode.ECR_PRINTING -> EcrPrintingRequest(enabled = true)
        CommandCode.REPRINT -> ReprintRequest()
    }

    fun finished(
        outcome: CommandOutcome,
        id: Long = 1,
        request: Ecr17Request = requestFor(outcome.command),
        origin: RunOrigin = RunOrigin.CONSOLE,
        attemptId: String? = if (outcome.command.journaled) "attempt-$id" else null,
        startedAt: Long = 1_000,
        finishedAt: Long = 2_000,
        journalError: String? = null,
    ) = FinishedRun(
        id = id, command = outcome.command, request = request, origin = origin, outcome = outcome,
        attemptId = attemptId, startedAt = startedAt, finishedAt = finishedAt, journalError = journalError,
    )

    fun running(
        command: CommandCode,
        phase: ExchangePhase,
        origin: RunOrigin = RunOrigin.CONSOLE,
        cancelRequested: Boolean = false,
    ) = RunningCommand(
        command = command, request = requestFor(command), origin = origin,
        attemptId = if (command.journaled) "attempt-running" else null,
        startedAt = 1_000, phase = phase, phaseAt = 1_000,
        sends = if (phase.ordinal >= ExchangePhase.SENDING.ordinal) 1 else 0,
        implicitGoAhead = false, resultWaitStartedAt = null, lastProgress = null,
        receipt = emptyList(), warnings = emptyList(), cancelRequested = cancelRequested,
        ackTimeoutMs = 2_000, responseTimeoutMs = 120_000,
    )

    // ─── Journal ──────────────────────────────────────────────────────────────────

    /**
     * Tentativo con la sua storia di record. phase UNKNOWN (default) o RESULT con needsReview; extra = record
     * aggiuntivi (EVIDENCE, RECEIVED) inseriti prima della chiusura.
     */
    fun entry(
        attemptId: String = "attempt-1",
        command: CommandCode? = CommandCode.PAYMENT,
        phase: JournalPhase = JournalPhase.UNKNOWN,
        needsReview: Boolean = false,
        createdAt: Long = 1_000,
        amountCents: Long? = 10,
        extra: List<JournalRecord> = emptyList(),
        persisted: Boolean = true,
    ): JournalEntry {
        val records = buildList {
            if (command != null) {
                add(JournalRecord(attemptId, 1, createdAt, JournalPhase.CREATED, command = command.wire.toString(), amountCents = amountCents))
            }
            add(JournalRecord(attemptId, 2, createdAt + 10, JournalPhase.SENDING, sends = 1))
            addAll(extra)
            when (phase) {
                JournalPhase.RESULT -> add(
                    JournalRecord(
                        attemptId, 10, createdAt + 500, JournalPhase.RESULT, outcome = "APPROVED",
                        rawResult = Frames.E22, needsReview = needsReview.takeIf { it },
                        reviewReason = if (needsReview) "POSSIBLE_DUPLICATE" else null,
                    ),
                )
                JournalPhase.UNKNOWN -> add(
                    JournalRecord(attemptId, 10, createdAt + 500, JournalPhase.UNKNOWN, failure = "ECR17: no application response before timeout (120000 ms)"),
                )
                else -> add(JournalRecord(attemptId, 10, createdAt + 500, phase))
            }
        }
        return JournalEntry(
            attemptId = attemptId, command = command, createdAt = createdAt, amountCents = amountCents,
            requestSummary = command?.let { "${it.wire} importo $amountCents" } ?: "",
            endpoint = if (command != null) ENDPOINT.toString() else "", profile = if (command != null) "INGENICO" else "",
            phase = phase, lastUpdate = createdAt + 500, sends = 1, records = records, persisted = persisted,
        )
    }
}
