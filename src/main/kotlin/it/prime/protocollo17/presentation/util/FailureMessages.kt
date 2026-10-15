package it.prime.protocollo17.presentation.util

import it.prime.protocollo17.ecr17.error.ConfigProblem
import it.prime.protocollo17.ecr17.error.Ecr17Failure
import it.prime.protocollo17.ecr17.error.ValidationError
import it.prime.protocollo17.ecr17.model.CloseCause
import it.prime.protocollo17.ecr17.model.CommandCode
import it.prime.protocollo17.ecr17.model.CommandOutcome
import it.prime.protocollo17.presentation.state.RunStart

// STUB NON LANCIANTE di WP0 (eccezione esplicita alla regola TODO, §6.19 e §8.2): firme definitive di §6.19,
// testi italiani definitivi a carico di WP6. Restituisce testi grezzi così TerminalSessionService (WP5) e i suoi
// test non finiscono nel ramo "eccezione inattesa" prima del merge di WP6.

/**
 * Messaggi d'errore per l'operatore (italiano, azionabili, mai codici nudi): traducono Ecr17Failure e gli altri
 * esiti negativi. I `message` di Ecr17Failure restano in inglese con il prefisso "ECR17:" per log e journal.
 */
object FailureMessages {

    /** Testo per qualsiasi esito non positivo (null per Answered/Acknowledged senza revisione). Punto d'ingresso principale. */
    fun forOutcome(outcome: CommandOutcome): String? = when (outcome) {
        is CommandOutcome.Answered -> if (outcome.needsReview) outcome::class.simpleName else null
        is CommandOutcome.Acknowledged -> null
        is CommandOutcome.NotSent -> "${outcome::class.simpleName}: ${outcome.failure.message}"
        is CommandOutcome.Refused -> "${outcome::class.simpleName}: ${outcome.failure.message}"
        is CommandOutcome.Failed -> "${outcome::class.simpleName}: ${outcome.failure.message}"
        is CommandOutcome.Unknown -> "${outcome::class.simpleName}: ${outcome.failure.message}"
    }

    /** unknown = l'esito è sconosciuto (CommandOutcome.Unknown): aggiunge sempre l'indicazione su G. */
    fun forFailure(failure: Ecr17Failure, command: CommandCode, unknown: Boolean): String = failure.message

    fun forValidation(error: ValidationError): String = error.message

    /** Problemi strutturali di Ecr17Config.validate() (chiavi "host", "port", nomi delle opzioni) in italiano. */
    fun forConfigProblem(problem: ConfigProblem): String = "${problem.key}: ${problem.message}"

    fun forCloseCause(cause: CloseCause): String = cause.name

    fun forRunStart(start: RunStart): String? = when (start) {
        RunStart.Started -> null
        RunStart.Busy -> "Busy"
        is RunStart.Blocked -> "Blocked: ${start.blocking.size}"
        is RunStart.InvalidConfig -> start.messages.joinToString("; ")
    }
}
