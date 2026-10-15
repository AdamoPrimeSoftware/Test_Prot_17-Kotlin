package it.prime.protocollo17.domain.util

import it.prime.protocollo17.domain.model.command.CommandTitles
import it.prime.protocollo17.domain.model.journal.JournalEntry
import it.prime.protocollo17.domain.model.journal.JournalPhase
import it.prime.protocollo17.domain.model.journal.KnownResult
import it.prime.protocollo17.domain.model.journal.KnownResultSource
import it.prime.protocollo17.ecr17.model.CommandOutcome
import it.prime.protocollo17.ecr17.model.TransactionResponse
import it.prime.protocollo17.ecr17.model.Verdict
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Quanto è probabile che l'esito di `G` appartenga al tentativo da risolvere. */
enum class GVerdict { LIKELY_THIS_ATTEMPT, LIKELY_PREVIOUS_RESULT, INCONCLUSIVE }

/** Verdetto con la spiegazione (italiano) mostrata nel dialog di risoluzione. */
data class GAssessmentResult(val verdict: GVerdict, val explanation: String)

/**
 * Suggerimento per l'operatore dopo un `G` (L§6.5 punti 4-5): l'esito riportato è quello del tentativo
 * sconosciuto o di una transazione precedente?
 *
 * Prima si confrontava `G` solo con l'ULTIMO esito registrato: un tentativo chiuso a mano con `CONFIRMED_BY_G`
 * (il cui esito esiste solo come `gRawResult`) o un esito più vecchio facevano dire "probabilmente questo
 * tentativo" a un `G` che ripeteva una transazione già nota. Ora si confronta con TUTTI gli esiti noti prima del
 * tentativo (journal.knownResultsBefore), payload per primo. È solo un suggerimento: G riporta l'ultima
 * transazione del terminale, e la decisione finale resta all'operatore (dialog §6.8, M6).
 */
object GAssessment {

    private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM HH:mm:ss")

    /** earlier = journal.knownResultsBefore(attempt.attemptId). Regole di §5.3.4, nell'ordine. */
    fun assess(attempt: JournalEntry, earlier: List<KnownResult>, g: CommandOutcome.Answered): GAssessmentResult {
        // 1. Solo una risposta di transazione si può attribuire a un tentativo.
        val response = g.response as? TransactionResponse ?: return GAssessmentResult(
            GVerdict.INCONCLUSIVE,
            "G non ha restituito un esito di transazione: non si può confrontare con il tentativo. " +
                "Verifica sullo scontrino o con Totali.",
        )
        val gRaw = response.raw.text

        // 2. Payload identico a un esito già noto ⇒ G ripete una transazione precedente.
        earlier.firstOrNull { it.raw == gRaw }?.let { known ->
            return GAssessmentResult(
                GVerdict.LIKELY_PREVIOUS_RESULT,
                "G restituisce un esito già registrato alle ${time(known.at)} (${sourceLabel(known.source)}): " +
                    "il tentativo probabilmente NON è stato eseguito.",
            )
        }

        // 3. Payload identico a un esito ricevuto o arrivato in ritardo PER QUESTO tentativo.
        val ownPayloads = attempt.records
            .filter { it.phase == JournalPhase.EVIDENCE || it.phase == JournalPhase.RECEIVED }
            .mapNotNull { it.rawResult }
        if (gRaw in ownPayloads) {
            return GAssessmentResult(
                GVerdict.LIKELY_THIS_ATTEMPT,
                "L'esito di G coincide con l'esito ricevuto o arrivato in ritardo per questo tentativo: " +
                    "probabilmente gli appartiene.",
            )
        }

        // 4. STAN, online id e data/ora host tutti presenti e uguali a un esito noto. Un campo assente non è mai
        //    "uguale": due KO senza data/ora non sono la stessa transazione solo perché mancano entrambe.
        val stan = response.stan.present()
        val online = response.onlineId.present()
        val dateTime = response.hostDateTimeRaw.present()
        if (stan != null && online != null && dateTime != null) {
            earlier.firstOrNull { it.stan == stan && it.onlineId == online && it.hostDateTime == dateTime }?.let { known ->
                return GAssessmentResult(
                    GVerdict.LIKELY_PREVIOUS_RESULT,
                    "STAN $stan, online id $online e data/ora host $dateTime coincidono con un esito già registrato " +
                        "alle ${time(known.at)}: il tentativo probabilmente NON è stato eseguito.",
                )
            }
        }

        // 5. KO senza data/ora (ramo KO: pos 37-47 riservate): lo STAN di un rifiuto non è univoco (A§4.5.1).
        if (response.verdict == Verdict.DECLINED && dateTime == null) {
            return GAssessmentResult(
                GVerdict.INCONCLUSIVE,
                "G riporta un rifiuto senza data/ora host: lo STAN di un rifiuto non è univoco, quindi non si può " +
                    "dire a quale tentativo appartenga. Verifica sullo scontrino o con Totali.",
            )
        }

        // 6. Famiglia del codice coerente col comando del tentativo.
        val command = attempt.command ?: return GAssessmentResult(
            GVerdict.INCONCLUSIVE,
            "Tentativo con dati incompleti (comando non leggibile nel journal): non si può confrontare con " +
                "l'esito di G. Verifica sullo scontrino o con Totali.",
        )
        val title = CommandTitles.title(command)
        if (response.replyCode in command.expectedReplies) {
            return GAssessmentResult(
                GVerdict.LIKELY_THIS_ATTEMPT,
                "L'esito di G ('${response.replyCode}') è del tipo atteso per $title e non coincide con nessun " +
                    "esito già registrato: probabilmente appartiene a questo tentativo.",
            )
        }

        // 7. Altrimenti nulla di certo.
        return GAssessmentResult(
            GVerdict.INCONCLUSIVE,
            "L'esito di G ('${response.replyCode}') non è del tipo atteso per $title " +
                "(${command.expectedReplies.joinToString(" ")}): G riporta l'ultima transazione del terminale, " +
                "che potrebbe essere un'altra operazione. Verifica sullo scontrino o con Totali.",
        )
    }

    private fun String?.present(): String? = this?.takeIf { it.isNotBlank() }

    private fun time(epochMs: Long): String =
        timeFormat.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

    private fun sourceLabel(source: KnownResultSource): String = when (source) {
        KnownResultSource.RESULT -> "esito di un tentativo precedente"
        KnownResultSource.RECEIVED -> "esito ricevuto da un tentativo precedente"
        KnownResultSource.G_RESOLUTION -> "esito di G usato per chiudere un tentativo precedente"
        KnownResultSource.EVIDENCE -> "esito orfano allegato come prova"
    }
}
