package it.prime.protocollo17.data.mapper

import it.prime.protocollo17.domain.model.command.CommandTitles
import it.prime.protocollo17.domain.model.journal.JournalEntry
import it.prime.protocollo17.domain.model.journal.JournalPhase
import it.prime.protocollo17.domain.model.journal.JournalRecord
import it.prime.protocollo17.domain.model.journal.KnownResult
import it.prime.protocollo17.domain.model.journal.KnownResultSource
import it.prime.protocollo17.domain.model.journal.ResolutionKind
import it.prime.protocollo17.domain.util.formatCents
import it.prime.protocollo17.ecr17.model.CardVerificationRequest
import it.prime.protocollo17.ecr17.model.CloseSessionRequest
import it.prime.protocollo17.ecr17.model.CommandCode
import it.prime.protocollo17.ecr17.model.CommandOutcome
import it.prime.protocollo17.ecr17.model.Ecr17Request
import it.prime.protocollo17.ecr17.model.EcrPrintingRequest
import it.prime.protocollo17.ecr17.model.ExchangePhase
import it.prime.protocollo17.ecr17.model.ExchangeTrace
import it.prime.protocollo17.ecr17.model.HasResult
import it.prime.protocollo17.ecr17.model.IncrementalAuthRequest
import it.prime.protocollo17.ecr17.model.LastResultRequest
import it.prime.protocollo17.ecr17.model.PaymentRequest
import it.prime.protocollo17.ecr17.model.PreAuthClosureRequest
import it.prime.protocollo17.ecr17.model.PreAuthRequest
import it.prime.protocollo17.ecr17.model.ReprintRequest
import it.prime.protocollo17.ecr17.model.ReversalRequest
import it.prime.protocollo17.ecr17.model.StatusRequest
import it.prime.protocollo17.ecr17.model.TotalsRequest
import it.prime.protocollo17.ecr17.model.TransactionResponse
import it.prime.protocollo17.ecr17.model.amountCents
import it.prime.protocollo17.ecr17.model.outcomeClass
import it.prime.protocollo17.ecr17.profile.Ecr17Config

/**
 * Mappatura tra il dominio del protocollo e le righe del journal (§5.3.3). Pura e testata (JournalMapperTest):
 * un campo dimenticato qui è un esito che il recupero con G non può più confrontare.
 *
 * Il record è un tipo proprio e non l'outcome serializzato: il file deve restare leggibile anche quando i tipi
 * del protocollo cambiano, e contiene solo dati già mascherati (M9).
 */
object JournalMapper {

    /** Fasi che indicano uno scambio interrotto a metà: al riavvio o dopo una cancellazione diventano UNKNOWN. */
    val IN_FLIGHT_AFTER_SEND: Set<JournalPhase> =
        setOf(JournalPhase.SENDING, JournalPhase.ACKED, JournalPhase.GO_AHEAD, JournalPhase.RECEIVED)

    /** Fasi che chiudono (o sospendono in attesa dell'operatore) un tentativo sul disco. */
    val CLOSING: Set<JournalPhase> =
        setOf(JournalPhase.RESULT, JournalPhase.NOT_SENT, JournalPhase.REFUSED, JournalPhase.UNKNOWN, JournalPhase.RESOLVED)

    /** Descrizione usata per i tentativi senza CREATED (riga corrotta). */
    const val INCOMPLETE_SUMMARY = "Tentativo con dati incompleti"

    // ─── Record ────────────────────────────────────────────────────────────────

    fun createdRecord(attemptId: String, seq: Int, at: Long, request: Ecr17Request, config: Ecr17Config): JournalRecord =
        JournalRecord(
            attemptId = attemptId,
            seq = seq,
            at = at,
            phase = JournalPhase.CREATED,
            command = request.command.wire.toString(),
            amountCents = request.amountCents,
            requestSummary = requestSummary(request),
            profile = config.profile.name,
            endpoint = config.endpoint.toString(),
            terminalId = config.ids.terminalId,
            cashRegisterId = cashRegisterOverride(request) ?: config.ids.cashRegisterId,
            optionsSummary = config.options.summary(),
        )

    /**
     * Record terminale da un outcome: Answered ⇒ RESULT, NotSent ⇒ NOT_SENT, Refused ⇒ REFUSED, Unknown e Failed ⇒
     * UNKNOWN (per i journaled Failed non si verifica, ma il mapper è totale). Acknowledged (mai per un journaled)
     * ⇒ RESULT con outcome ACKNOWLEDGED.
     */
    fun terminalRecord(attemptId: String, seq: Int, at: Long, outcome: CommandOutcome): JournalRecord {
        val base = JournalRecord(attemptId = attemptId, seq = seq, at = at, phase = JournalPhase.RESULT, outcome = outcome.outcomeClass().name)
            .withTrace(outcome.trace)
        return when (outcome) {
            is CommandOutcome.Answered -> {
                val response = outcome.response
                val result = response as? HasResult
                val tx = response as? TransactionResponse
                val review = outcome.reviewWarnings
                base.copy(
                    phase = JournalPhase.RESULT,
                    replyCode = response.replyCode.toString(),
                    resultCode = result?.resultCode,
                    verdict = result?.verdict?.name,
                    stan = tx?.stan,
                    onlineId = tx?.onlineId,
                    authCode = tx?.authCode,
                    hostDateTime = tx?.hostDateTimeRaw,
                    rawResult = response.raw.text,
                    needsReview = if (outcome.needsReview) true else null,
                    reviewReason = if (outcome.needsReview) review.joinToString(",") { it.code.name } else null,
                )
            }
            is CommandOutcome.Acknowledged -> base.copy(phase = JournalPhase.RESULT)
            is CommandOutcome.NotSent -> base.copy(
                phase = JournalPhase.NOT_SENT,
                failure = outcome.failure.message,
                failurePhase = outcome.trace?.phases?.lastOrNull()?.phase?.name,
            )
            is CommandOutcome.Refused -> base.copy(
                phase = JournalPhase.REFUSED,
                failure = outcome.failure.message,
                failurePhase = outcome.trace.phases.lastOrNull()?.phase?.name,
            )
            is CommandOutcome.Unknown -> base.copy(
                phase = JournalPhase.UNKNOWN,
                failure = outcome.failure.message,
                failurePhase = outcome.phase.name,
            )
            is CommandOutcome.Failed -> base.copy(
                phase = JournalPhase.UNKNOWN,
                failure = outcome.failure.message,
                failurePhase = outcome.phase.name,
            )
        }
    }

    /** Chiusura dopo una cancellazione (outcome null): failurePhase dalla fase dell'ultimo record scritto. */
    fun interruptedRecord(attemptId: String, seq: Int, at: Long, phase: JournalPhase, failure: String, lastPhase: JournalPhase?): JournalRecord =
        JournalRecord(
            attemptId = attemptId,
            seq = seq,
            at = at,
            phase = phase,
            outcome = if (phase == JournalPhase.NOT_SENT) "NOT_SENT" else "UNKNOWN",
            failure = failure,
            failurePhase = lastPhase?.let(::exchangePhaseOf)?.name,
        )

    fun resolvedRecord(
        attemptId: String,
        seq: Int,
        at: Long,
        kind: ResolutionKind,
        note: String,
        gOutcome: CommandOutcome.Answered?,
    ): JournalRecord {
        val response = gOutcome?.response
        val tx = response as? TransactionResponse
        return JournalRecord(
            attemptId = attemptId,
            seq = seq,
            at = at,
            phase = JournalPhase.RESOLVED,
            resolution = kind,
            note = note,
            gVerdict = (response as? HasResult)?.verdict?.name,
            gRawResult = response?.raw?.text,
            gStan = tx?.stan,
            gOnlineId = tx?.onlineId,
            gHostDateTime = tx?.hostDateTimeRaw,
        )
    }

    // ─── Fold ──────────────────────────────────────────────────────────────────

    /**
     * Vista aggregata di un tentativo da TUTTI i suoi record (in ordine di seq; a parità, ordine di file).
     * Senza CREATED (riga corrotta) ⇒ command = null e campi descrittivi vuoti: l'entry resta visibile e, se non
     * chiusa, bloccante (M6). Senza record diversi da EVIDENCE la fase è UNKNOWN: nel dubbio si blocca.
     */
    fun fold(attemptId: String, records: List<JournalRecord>, persisted: Boolean = true): JournalEntry {
        require(records.isNotEmpty()) { "fold di un tentativo senza record: $attemptId" }
        val sorted = records.sortedBy { it.seq }
        val created = sorted.firstOrNull { it.phase == JournalPhase.CREATED }
        val diskPhase = lastDiskPhase(sorted) ?: JournalPhase.UNKNOWN
        return JournalEntry(
            attemptId = attemptId,
            command = created?.command?.let(::commandOf),
            createdAt = created?.at ?: sorted.minOf { it.at },
            amountCents = created?.amountCents,
            requestSummary = created?.requestSummary.orEmpty(),
            endpoint = created?.endpoint.orEmpty(),
            profile = created?.profile.orEmpty(),
            phase = if (persisted) diskPhase else JournalPhase.UNKNOWN,
            lastUpdate = sorted.maxOf { it.at },
            sends = sorted.mapNotNull { it.sends }.maxOrNull() ?: 0,
            records = sorted,
            persisted = persisted,
        )
    }

    /** Fase dell'ultimo record diverso da EVIDENCE, null se non ce ne sono. */
    fun lastDiskPhase(records: List<JournalRecord>): JournalPhase? =
        records.lastOrNull { it.phase != JournalPhase.EVIDENCE }?.phase

    /** Lettera del comando ("P") o, per robustezza, nome dell'enum ("PAYMENT"); null se illeggibile. */
    fun commandOf(value: String): CommandCode? =
        if (value.length == 1) CommandCode.fromWire(value[0]) else CommandCode.entries.firstOrNull { it.name == value }

    /**
     * Esiti noti di un tentativo, per GAssessment (§5.3.4): rawResult dei RESULT e RECEIVED, prove EVIDENCE e
     * l'esito di G registrato nei RESOLVED (gRawResult/gStan/gOnlineId/gHostDateTime).
     */
    fun knownResults(entry: JournalEntry): List<KnownResult> = entry.records.mapNotNull { r ->
        fun known(source: KnownResultSource, raw: String, stan: String? = null, onlineId: String? = null, hostDateTime: String? = null) =
            KnownResult(
                attemptId = entry.attemptId, source = source, raw = raw,
                stan = stan, onlineId = onlineId, hostDateTime = hostDateTime, at = r.at,
            )
        when (r.phase) {
            JournalPhase.RESULT -> r.rawResult?.let {
                known(KnownResultSource.RESULT, it, stan = r.stan, onlineId = r.onlineId, hostDateTime = r.hostDateTime)
            }
            JournalPhase.RECEIVED -> r.rawResult?.let { known(KnownResultSource.RECEIVED, it) }
            JournalPhase.EVIDENCE -> r.rawResult?.let { known(KnownResultSource.EVIDENCE, it) }
            JournalPhase.RESOLVED -> r.gRawResult?.let {
                known(KnownResultSource.G_RESOLUTION, it, stan = r.gStan, onlineId = r.gOnlineId, hostDateTime = r.gHostDateTime)
            }
            else -> null
        }
    }

    // ─── Testi ─────────────────────────────────────────────────────────────────

    /** Riepilogo italiano della richiesta per journal e finestra Journal, es. "Pagamento 0,10 € · tipo AUTO". */
    fun requestSummary(request: Ecr17Request): String {
        val parts = mutableListOf<String>()
        val head = CommandTitles.title(request.command) + (request.amountCents?.let { " " + formatCents(it) } ?: "")
        parts += head
        when (request) {
            is PaymentRequest -> {
                parts += "tipo ${request.paymentType.name}"
                if (request.cardAlreadyPresent) parts += "carta già presente"
                if (request.receiptText.isNotBlank()) parts += "testo \"${request.receiptText}\""
            }
            is PreAuthRequest -> {
                parts += "tipo ${request.paymentType.name}"
                if (request.cardAlreadyPresent) parts += "carta già presente"
                if (request.receiptText.isNotBlank()) parts += "testo \"${request.receiptText}\""
            }
            is IncrementalAuthRequest -> {
                parts += "codice preautorizzazione ${request.originalPreAuthCode}"
                if (request.receiptText.isNotBlank()) parts += "testo \"${request.receiptText}\""
            }
            is PreAuthClosureRequest -> {
                parts += "codice preautorizzazione ${request.originalPreAuthCode}"
                if (request.receiptText.isNotBlank()) parts += "testo \"${request.receiptText}\""
            }
            is ReversalRequest -> parts += request.stan?.takeIf { it.isNotBlank() }?.let { "STAN $it" } ?: "ultima transazione"
            is CardVerificationRequest -> parts += "tipo ${request.paymentType.name}"
            is EcrPrintingRequest -> parts += if (request.enabled) "abilita" else "disabilita"
            is ReprintRequest -> parts += "${request.target.name} ${request.ticketType.name}"
            is CloseSessionRequest, is TotalsRequest, is LastResultRequest, StatusRequest -> Unit
        }
        cashRegisterOverride(request)?.let { parts += "ECR id $it" }
        return parts.joinToString(" · ")
    }

    private fun cashRegisterOverride(request: Ecr17Request): String? = when (request) {
        is PaymentRequest -> request.cashRegisterId
        is ReversalRequest -> request.cashRegisterId
        is PreAuthRequest -> request.cashRegisterId
        is IncrementalAuthRequest -> request.cashRegisterId
        is PreAuthClosureRequest -> request.cashRegisterId
        is CardVerificationRequest -> request.cashRegisterId
        is CloseSessionRequest -> request.cashRegisterId
        is TotalsRequest -> request.cashRegisterId
        is LastResultRequest -> request.cashRegisterId
        is EcrPrintingRequest, is ReprintRequest, StatusRequest -> null
    }?.takeIf { it.isNotBlank() }

    /** Fase dello scambio corrispondente all'ultima fase scritta nel journal (per failurePhase). */
    private fun exchangePhaseOf(phase: JournalPhase): ExchangePhase? = when (phase) {
        JournalPhase.CREATED -> ExchangePhase.PREPARING
        JournalPhase.SENDING -> ExchangePhase.SENDING
        JournalPhase.ACKED -> ExchangePhase.ACKNOWLEDGED
        JournalPhase.GO_AHEAD -> ExchangePhase.IMPLICIT_GO_AHEAD
        JournalPhase.RECEIVED -> ExchangePhase.RESULT_RECEIVED
        else -> null
    }

    /** Contatori dello scambio comuni a tutti i record terminali (null se la trace manca). */
    private fun JournalRecord.withTrace(trace: ExchangeTrace?): JournalRecord = if (trace == null) this else copy(
        sends = trace.sends,
        ackMode = trace.ackMode?.name,
        naksReceived = trace.naksReceived,
        timeoutRetransmissions = trace.timeoutRetransmissions,
    )
}
