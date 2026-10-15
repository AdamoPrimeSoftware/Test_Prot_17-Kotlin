package it.prime.protocollo17.presentation.model

import it.prime.protocollo17.domain.model.command.CommandTitles
import it.prime.protocollo17.domain.model.command.FieldKey
import it.prime.protocollo17.domain.util.formatCents
import it.prime.protocollo17.ecr17.event.WarningCode
import it.prime.protocollo17.ecr17.model.AckMode
import it.prime.protocollo17.ecr17.model.CloseSessionResponse
import it.prime.protocollo17.ecr17.model.CommandCode
import it.prime.protocollo17.ecr17.model.CommandOutcome
import it.prime.protocollo17.ecr17.model.ExchangePhase
import it.prime.protocollo17.ecr17.model.ExchangeTrace
import it.prime.protocollo17.ecr17.model.HasResult
import it.prime.protocollo17.ecr17.model.PaymentResponse
import it.prime.protocollo17.ecr17.model.PreAuthClosureResponse
import it.prime.protocollo17.ecr17.model.PreAuthResponse
import it.prime.protocollo17.ecr17.model.ReprintRequest
import it.prime.protocollo17.ecr17.model.ReprintTarget
import it.prime.protocollo17.ecr17.model.StatusResponse
import it.prime.protocollo17.ecr17.model.Verdict
import it.prime.protocollo17.ecr17.model.amountCents
import it.prime.protocollo17.ecr17.wire.Hex
import it.prime.protocollo17.presentation.state.FinishedRun
import it.prime.protocollo17.presentation.util.FailureMessages
import it.prime.protocollo17.presentation.util.formatTime
import it.prime.protocollo17.presentation.util.formatTimeMillis

/**
 * Da un comando concluso alla sua vista (tabella §6.7). Puro e testato: il pannello si limita a disegnare.
 *
 * Il titolo distingue le quattro famiglie che non vanno mai confuse (U8, M6): approvato, rifiutato, non inviato
 * (esito certo, nulla è successo) ed esito sconosciuto (potrebbe essere successo: G). Un esito "da verificare"
 * (possibile doppio addebito o esito prima dell'ACK) non è mai verde: è ambra con l'avviso sotto il titolo.
 */
object ResultViewMapper {

    fun map(run: FinishedRun): ResultView {
        val outcome = run.outcome
        val summary = summarize(run)
        val trace = outcome.trace
        val answered = outcome as? CommandOutcome.Answered
        return ResultView(
            command = run.command,
            tone = summary.tone,
            headline = summary.headline,
            detail = summary.detail,
            fields = answered?.response?.fields?.map(FieldLabels::row).orEmpty(),
            exchange = trace?.let { exchangeRows(it, answered) }.orEmpty(),
            timeline = trace?.let(::timelineRows).orEmpty(),
            rawPayload = answered?.let { Hex.printable(it.response.raw.text.toByteArray(Charsets.ISO_8859_1)) },
            receipt = trace?.receipt?.map { it.text }.orEmpty(),
            warnings = trace?.warnings?.map { "${warningLabel(it.code)}: ${it.detail}" }.orEmpty(),
            reviewNotice = answered?.let(::reviewNotice),
            quickActions = answered?.let { quickActions(run.command, it) }.orEmpty(),
            journalError = run.journalError,
            finishedAt = run.finishedAt,
        )
    }

    /** Riga compatta per la cronologia: ora, lettera, titolo, importo (formatCents). */
    fun historyRow(run: FinishedRun): HistoryRowView {
        val summary = summarize(run)
        return HistoryRowView(
            id = run.id,
            time = formatTime(run.finishedAt),
            letter = run.command.wire,
            danger = CommandTitles.isDanger(run.command),
            headline = summary.headline,
            tone = summary.tone,
            amount = run.request.amountCents?.let(::formatCents),
        )
    }

    // ─── Titolo, tono e dettaglio ─────────────────────────────────────────────────

    private data class Summary(val tone: ResultTone, val headline: String, val detail: String?)

    private fun summarize(run: FinishedRun): Summary = when (val outcome = run.outcome) {
        is CommandOutcome.Answered -> answeredSummary(outcome)
        is CommandOutcome.Acknowledged -> Summary(ResultTone.INFO, "CONFERMATO (ACK)", acknowledgedDetail(run, outcome))
        is CommandOutcome.NotSent -> Summary(ResultTone.NEUTRAL, "NON INVIATO", FailureMessages.forOutcome(outcome))
        is CommandOutcome.Refused -> Summary(ResultTone.DANGER, "RIFIUTATO DAL TERMINALE (NAK)", FailureMessages.forOutcome(outcome))
        is CommandOutcome.Failed -> Summary(ResultTone.DANGER, "NESSUNA RISPOSTA", FailureMessages.forOutcome(outcome))
        is CommandOutcome.Unknown -> Summary(ResultTone.WARNING, "ESITO SCONOSCIUTO", FailureMessages.forOutcome(outcome))
    }

    private fun answeredSummary(outcome: CommandOutcome.Answered): Summary {
        val response = outcome.response
        if (response is StatusResponse) {
            val dateTime = response.dateTime?.let {
                "%02d/%02d/%04d %02d:%02d".format(it.dayOfMonth, it.monthValue, it.year, it.hour, it.minute)
            } ?: response.dateTimeRaw
            val release = response.softwareRelease.ifBlank { "—" }
            return Summary(ResultTone.INFO, "STATO: ${FieldLabels.statusText(response.statusDigit)}", "Data/ora terminale $dateTime · release $release")
        }
        val hasResult = response as? HasResult ?: return Summary(ResultTone.NEUTRAL, "RISPOSTA '${response.replyCode}' RICEVUTA", null)
        val review = outcome.needsReview
        val reviewSuffix = if (review) " — DA VERIFICARE" else ""
        return when (hasResult.verdict) {
            Verdict.APPROVED -> {
                val word = if (outcome.command == CommandCode.TOTALS || outcome.command == CommandCode.CLOSE_SESSION) "OK" else "APPROVATO"
                Summary(if (review) ResultTone.WARNING else ResultTone.SUCCESS, word + reviewSuffix, null)
            }
            Verdict.DECLINED -> Summary(if (review) ResultTone.WARNING else ResultTone.DANGER, "RIFIUTATO$reviewSuffix", errorDescription(hasResult))
            Verdict.CARD_NOT_PRESENT -> Summary(ResultTone.WARNING, "CARTA NON PRESENTE (05)$reviewSuffix", null)
            Verdict.UNKNOWN_TAG -> Summary(ResultTone.WARNING, "TAG SCONOSCIUTO (09)$reviewSuffix", null)
            Verdict.UNRECOGNIZED -> Summary(ResultTone.WARNING, "ESITO NON RICONOSCIUTO (${hasResult.resultCode})$reviewSuffix", null)
        }
    }

    /** Descrizione d'errore del terminale (ramo KO), dove il layout la prevede. */
    private fun errorDescription(response: HasResult): String? = when (response) {
        is PaymentResponse -> response.errorDescription
        is PreAuthResponse -> response.errorDescription
        is PreAuthClosureResponse -> response.errorDescription
        is CloseSessionResponse -> response.errorDescription
        else -> null
    }?.trim()?.takeIf { it.isNotEmpty() }

    private fun acknowledgedDetail(run: FinishedRun, outcome: CommandOutcome.Acknowledged): String? {
        val request = run.request
        return if (request is ReprintRequest && request.target == ReprintTarget.ECR) {
            val lines = outcome.trace.receipt.size
            if (lines == 1) "1 riga di scontrino ricevuta" else "$lines righe di scontrino ricevute"
        } else {
            null
        }
    }

    // ─── Revisione e azioni rapide ────────────────────────────────────────────────

    private fun reviewNotice(outcome: CommandOutcome.Answered): String? {
        val codes = outcome.reviewWarnings.map { it.code }.toSet()
        if (codes.isEmpty()) return null
        return buildList {
            if (WarningCode.POSSIBLE_DUPLICATE in codes) {
                add(
                    "Esito arrivato dopo ${outcome.trace.timeoutRetransmissions} reinvii per ACK mancante: possibile doppio " +
                        "addebito. Verifica con Ultimo esito (G) o Totali prima di un nuovo pagamento.",
                )
            }
            if (WarningCode.EARLY_RESULT in codes) add("Esito arrivato prima dell'ACK: verificalo con Ultimo esito (G).")
        }.joinToString(" ")
    }

    /** §6.6: da `e` approvata il codice in i/c; da `E` approvata di P/X lo STAN in S. */
    private fun quickActions(command: CommandCode, outcome: CommandOutcome.Answered): List<QuickAction> {
        val response = outcome.response
        return when {
            response is PreAuthResponse && response.verdict == Verdict.APPROVED -> {
                val code = response.preAuthCode?.trim().orEmpty()
                if (code.isEmpty()) {
                    emptyList()
                } else {
                    listOf(
                        QuickAction("Usa codice in Integrazione (i)", CommandCode.INCREMENTAL_AUTH, FieldKey.PRE_AUTH_CODE, code),
                        QuickAction("Usa codice in Chiusura (c)", CommandCode.PRE_AUTH_CLOSURE, FieldKey.PRE_AUTH_CODE, code),
                    )
                }
            }
            response is PaymentResponse && response.verdict == Verdict.APPROVED &&
                (command == CommandCode.PAYMENT || command == CommandCode.PAYMENT_EXTENDED) -> {
                val stan = response.stan?.trim().orEmpty()
                if (stan.isEmpty()) emptyList() else listOf(QuickAction("Usa STAN in Storno (S)", CommandCode.REVERSAL, FieldKey.STAN, stan))
            }
            else -> emptyList()
        }
    }

    // ─── Dettagli dello scambio ───────────────────────────────────────────────────

    private fun exchangeRows(trace: ExchangeTrace, answered: CommandOutcome.Answered?): List<FieldRow> = buildList {
        add(FieldRow("Invii della richiesta", trace.sends.toString()))
        add(FieldRow("NAK ricevuti", trace.naksReceived.toString()))
        add(FieldRow("Reinvii per ACK mancante", trace.timeoutRetransmissions.toString()))
        add(FieldRow("Modalità ACK", trace.ackMode?.let(::ackModeLabel) ?: "—"))
        add(FieldRow("ACK tardivo", if (trace.lateAck) "sì" else "no"))
        val expected = trace.command.expectedReplies.joinToString(" ").ifEmpty { "solo ACK" }
        val received = answered?.response?.replyCode?.toString() ?: "—"
        add(FieldRow("Codice ricevuto / attesi", "$received / $expected"))
        if (trace.resultRetransmissions > 0) add(FieldRow("Esito ritrasmesso dal terminale", trace.resultRetransmissions.toString()))
        if (trace.replayedAfterReconnect) add(FieldRow("Ripetuto dopo una caduta", "sì"))
        if (trace.printingSetupSent) add(FieldRow("Stampa su ECR (E1) inviata", "sì"))
        add(FieldRow("Durata", "${(trace.finishedAt - trace.startedAt).coerceAtLeast(0)} ms"))
        add(FieldRow("Terminale", "${trace.endpoint} · ${trace.profile.name}"))
        add(FieldRow("Opzioni", trace.optionsSummary))
    }

    private fun timelineRows(trace: ExchangeTrace): List<FieldRow> = trace.phases.map { stamp ->
        FieldRow(phaseLabel(stamp.phase), formatTimeMillis(stamp.at), "+${(stamp.at - trace.startedAt).coerceAtLeast(0)} ms")
    }

    fun phaseLabel(phase: ExchangePhase): String = when (phase) {
        ExchangePhase.PREPARING -> "Preparazione"
        ExchangePhase.CONNECTING -> "Connessione"
        ExchangePhase.PRINTING_SETUP -> "Abilitazione stampa su ECR"
        ExchangePhase.SENDING -> "Invio richiesta"
        ExchangePhase.AWAITING_ACK -> "Attesa ACK"
        ExchangePhase.IMPLICIT_GO_AHEAD -> "Go-ahead implicito (ACK non ricevuto)"
        ExchangePhase.ACKNOWLEDGED -> "Richiesta accettata"
        ExchangePhase.AWAITING_RESULT -> "Attesa esito"
        ExchangePhase.RESULT_RECEIVED -> "Esito ricevuto"
        ExchangePhase.DRAINING -> "Chiusura dello scambio"
        ExchangePhase.COMPLETED -> "Completato"
    }

    private fun ackModeLabel(mode: AckMode): String = when (mode) {
        AckMode.EXPLICIT -> "ACK esplicito"
        AckMode.BARE -> "ACK senza ETX/LRC (nudo)"
        AckMode.PROGRESS_BEFORE_ACK -> "Progress o riga S prima dell'ACK (ACK implicito)"
        AckMode.RESULT_BEFORE_ACK -> "Esito prima dell'ACK"
        AckMode.IMPLICIT_GO_AHEAD -> "Nessun ACK: go-ahead implicito"
    }

    fun warningLabel(code: WarningCode): String = when (code) {
        WarningCode.CONTROL_LRC_MISMATCH -> "ACK con LRC diverso (tollerato)"
        WarningCode.BARE_CONTROL -> "ACK senza ETX/LRC"
        WarningCode.AMBIGUOUS_NAK -> "NAK ambiguo ignorato"
        WarningCode.APP_LRC_OTHER_MODE -> "LRC applicativo di un'altra modalità"
        WarningCode.APP_LRC_INVALID -> "LRC applicativo errato (NAK inviato)"
        WarningCode.IMPLICIT_GO_AHEAD -> "ACK non ricevuto, nessun reinvio"
        WarningCode.LATE_ACK -> "ACK tardivo"
        WarningCode.SPURIOUS_CONTROL -> "ACK/NAK inatteso"
        WarningCode.UNEXPECTED_REPLY -> "Risposta inattesa (orfana)"
        WarningCode.STALE_BYTES -> "Byte stantii prima dell'invio"
        WarningCode.POSSIBLE_LOST_RESULT -> "Possibile esito perso"
        WarningCode.EARLY_RESULT -> "Esito prima dell'ACK"
        WarningCode.POSSIBLE_DUPLICATE -> "Possibile doppio addebito"
        WarningCode.RESULT_RETRANSMITTED -> "Esito ritrasmesso dal terminale"
        WarningCode.CONTROL_SEND_FAILED -> "Invio di ACK/NAK fallito"
        WarningCode.JOURNAL_WRITE_FAILED -> "Journal non scritto"
        WarningCode.DRAIN_INTERRUPTED -> "Chiusura dello scambio interrotta"
        WarningCode.RX_OVERFLOW -> "Buffer di ricezione pieno"
        WarningCode.PROGRESS_LENGTH -> "Progress di lunghezza anomala"
        WarningCode.RECEIPT_END_MISSING -> "Fine scontrino non ricevuta"
    }
}
