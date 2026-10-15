package it.prime.protocollo17.data.testing

import it.prime.protocollo17.ecr17.error.Ecr17Failure
import it.prime.protocollo17.ecr17.event.ProtocolWarning
import it.prime.protocollo17.ecr17.event.WarningCode
import it.prime.protocollo17.ecr17.model.AckMode
import it.prime.protocollo17.ecr17.model.CardType
import it.prime.protocollo17.ecr17.model.CommandCode
import it.prime.protocollo17.ecr17.model.CommandOutcome
import it.prime.protocollo17.ecr17.model.Endpoint
import it.prime.protocollo17.ecr17.model.EntryMode
import it.prime.protocollo17.ecr17.model.ExchangePhase
import it.prime.protocollo17.ecr17.model.ExchangeTrace
import it.prime.protocollo17.ecr17.model.PaymentLayout
import it.prime.protocollo17.ecr17.model.PaymentResponse
import it.prime.protocollo17.ecr17.model.PhaseStamp
import it.prime.protocollo17.ecr17.model.RawPayload
import it.prime.protocollo17.ecr17.model.StatusResponse
import it.prime.protocollo17.ecr17.model.Verdict
import it.prime.protocollo17.ecr17.profile.Ecr17Config
import it.prime.protocollo17.ecr17.profile.ProtocolOptions
import it.prime.protocollo17.ecr17.profile.TerminalProfileId
import it.prime.protocollo17.ecr17.model.TerminalIds
import it.prime.protocollo17.ecr17.testing.Frames

/**
 * [WP5] Esiti costruiti a mano per i test del data layer e dei servizi: nessuna dipendenza dal parser (WP2) né
 * dal client (WP3), così journal, repository e servizio si provano prima del merge.
 */
object OutcomeFixtures {

    val endpoint = Endpoint("127.0.0.1", 5577)

    val config = Ecr17Config(
        profile = TerminalProfileId.INGENICO,
        endpoint = endpoint,
        ids = TerminalIds("12345678", "00000042"),
        options = ProtocolOptions(),
    )

    fun trace(
        command: CommandCode = CommandCode.PAYMENT,
        sends: Int = 1,
        naks: Int = 0,
        timeoutRetransmissions: Int = 0,
        ackMode: AckMode? = AckMode.EXPLICIT,
        warnings: List<ProtocolWarning> = emptyList(),
        lastPhase: ExchangePhase = ExchangePhase.COMPLETED,
    ) = ExchangeTrace(
        exchangeId = 1, command = command, endpoint = endpoint, profile = TerminalProfileId.INGENICO,
        optionsSummary = ProtocolOptions().summary(), sends = sends, anyMainFrameWriteAttempted = sends > 0,
        naksReceived = naks, timeoutRetransmissions = timeoutRetransmissions, ackMode = ackMode, lateAck = false,
        phases = listOf(PhaseStamp(ExchangePhase.PREPARING, 0), PhaseStamp(lastPhase, 10)), receipt = emptyList(),
        warnings = warnings, resultRetransmissions = 0, replayedAfterReconnect = false, printingSetupSent = false,
        startedAt = 0, finishedAt = 10,
    )

    /** Esito E22 approvato di A§9.8 (PAN già mascherato come dal parser con maskPan = true). */
    fun approvedPayment(raw: String = Frames.E22, stan: String? = "000004", onlineId: String? = "000055", hostDateTime: String? = "0770947") =
        PaymentResponse(
            raw = RawPayload(raw), terminalId = "02575733", replyCode = 'E', fields = emptyList(),
            resultCode = "00", verdict = Verdict.APPROVED, layout = PaymentLayout.BASE_71,
            pan = "***************4768", entryModeRaw = "CLI", entryMode = EntryMode.CLESS_ICC,
            authCode = "R48432", hostDateTimeRaw = hostDateTime, errorDescription = null,
            cardTypeRaw = "2", cardType = CardType.CREDIT, acquirerId = "00000000023", stan = stan, onlineId = onlineId,
            actionCode = null, hostAmountCents = null, originalAmountCents = null, dcc = null,
        )

    /** Esito E4 rifiutato di A§9.8 ("OPERAZIONE ANNULLATA"). */
    fun declinedPayment() = PaymentResponse(
        raw = RawPayload(Frames.E4), terminalId = "02575733", replyCode = 'E', fields = emptyList(),
        resultCode = "01", verdict = Verdict.DECLINED, layout = PaymentLayout.BASE_71,
        pan = null, entryModeRaw = null, entryMode = null, authCode = null, hostDateTimeRaw = null,
        errorDescription = "OPERAZIONE ANNULLATA", cardTypeRaw = null, cardType = null, acquirerId = "00000000000",
        stan = "000000", onlineId = "000000", actionCode = null, hostAmountCents = null, originalAmountCents = null, dcc = null,
    )

    fun statusResponse() = StatusResponse(
        raw = RawPayload("025757330s00000000000101261530" + "2" + "V1.2.3  "), terminalId = "02575733", replyCode = 's',
        fields = emptyList(), dateTimeRaw = "0101261530", dateTime = null, statusDigit = 2, softwareRelease = "V1.2.3",
    )

    fun answered(command: CommandCode = CommandCode.PAYMENT, warnings: List<ProtocolWarning> = emptyList(), naks: Int = 0, retx: Int = 0) =
        CommandOutcome.Answered(command, approvedPayment(), trace(command, sends = 1 + naks + retx, naks = naks, timeoutRetransmissions = retx, warnings = warnings))

    fun duplicateWarning() = ProtocolWarning(WarningCode.POSSIBLE_DUPLICATE, "esito dopo un reinvio per ACK mancante", at = 5)

    fun unknown(command: CommandCode = CommandCode.PAYMENT, withTrace: Boolean = true) = CommandOutcome.Unknown(
        command, Ecr17Failure.ResponseTimeout(120_000), ExchangePhase.AWAITING_RESULT,
        if (withTrace) trace(command, lastPhase = ExchangePhase.AWAITING_RESULT) else null,
    )

    fun notSent(command: CommandCode = CommandCode.PAYMENT) =
        CommandOutcome.NotSent(command, Ecr17Failure.ConnectFailed(endpoint, "Connection refused"), null)

    fun refused(command: CommandCode = CommandCode.PAYMENT) =
        CommandOutcome.Refused(command, Ecr17Failure.Nak(4), trace(command, sends = 4, naks = 4, lastPhase = ExchangePhase.AWAITING_ACK))

    fun failed(command: CommandCode = CommandCode.STATUS) =
        CommandOutcome.Failed(command, Ecr17Failure.ResponseTimeout(120_000), ExchangePhase.AWAITING_RESULT, trace(command))
}
