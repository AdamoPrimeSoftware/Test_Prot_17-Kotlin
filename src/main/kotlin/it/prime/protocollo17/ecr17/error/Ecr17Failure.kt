package it.prime.protocollo17.ecr17.error

import it.prime.protocollo17.ecr17.model.CloseCause
import it.prime.protocollo17.ecr17.model.Endpoint

// Gli errori attesi sono DATI, non eccezioni (skill error-handling). L'unica eccezione pubblica è
// Ecr17ValidationException, lanciata da RequestEncoder.encode e convertita dal client in NotSent(Validation).
// I message sono in inglese con il prefisso "ECR17:" dei repo (per i log); la UI li traduce con
// FailureMessages (§6.19).

/** Campo della richiesta a cui si riferisce un errore di validazione (mappato su FieldKey dalla UI). */
enum class RequestField { TERMINAL_ID, CASH_REGISTER_ID, AMOUNT, RECEIPT_TEXT, STAN, PRE_AUTH_CODE }

/** Violazione di una regola di layout della richiesta. Tutte le violazioni si raccolgono insieme, mai troncare. */
sealed interface ValidationError {
    val field: RequestField
    val message: String

    data class FieldOverflow(override val field: RequestField, val value: String, val width: Int) : ValidationError {
        override val message get() = "ECR17: value '$value' exceeds fixed field width of $width bytes"
    }

    data class NotDigits(override val field: RequestField, val value: String) : ValidationError {
        override val message get() = "ECR17: field $field must contain only digits"
    }

    data class NonAscii(override val field: RequestField, val index: Int) : ValidationError {
        override val message get() = "ECR17: field $field contains a non printable ASCII char at index $index"
    }

    data class NegativeAmount(override val field: RequestField = RequestField.AMOUNT) : ValidationError {
        override val message get() = "ECR17: amount must be non-negative"
    }

    data class Required(override val field: RequestField) : ValidationError {
        override val message get() = "ECR17: field $field is required"
    }
}

/** Lanciata da RequestEncoder.encode con richieste non valide: il client la converte in NotSent(Validation). */
class Ecr17ValidationException(val errors: List<ValidationError>) :
    IllegalArgumentException(errors.joinToString("; ") { it.message })

/** Problema strutturale della configurazione (chiave "host", "port" o nome dell'opzione + messaggio inglese). */
data class ConfigProblem(val key: String, val message: String)

/** Motivo di un esito non positivo. Il tipo decide la mappatura money-safety; il message serve ai log. */
sealed interface Ecr17Failure {
    /** Testo stabile in inglese con prefisso "ECR17:" (log, journal). La UI usa FailureMessages (§6.19). */
    val message: String

    data class Validation(val errors: List<ValidationError>) : Ecr17Failure {
        override val message get() = "ECR17: invalid request: " + errors.joinToString("; ") { it.message.removePrefix("ECR17: ") }
    }

    data class ConfigInvalid(val problems: List<ConfigProblem>) : Ecr17Failure {
        override val message get() = "ECR17: invalid configuration: " + problems.joinToString("; ") { "${it.key}: ${it.message}" }
    }

    data object Busy : Ecr17Failure {
        override val message get() = "ECR17: another command is in progress"
    }

    data class ConnectFailed(val endpoint: Endpoint, val detail: String?) : Ecr17Failure {
        override val message get() = "ECR17: connection to $endpoint failed" + (detail?.let { ": $it" } ?: "")
    }

    data class ConnectTimeout(val endpoint: Endpoint, val timeoutMs: Long) : Ecr17Failure {
        override val message get() = "ECR17: connection to $endpoint timed out after $timeoutMs ms"
    }

    data class PrintingSetupFailed(val cause: Ecr17Failure) : Ecr17Failure {
        override val message get() = "ECR17: ECR printing setup (E1) failed: ${cause.message.removePrefix("ECR17: ")}"
    }

    data class Nak(val attempts: Int) : Ecr17Failure {
        override val message get() = "ECR17: NAK after $attempts attempt(s)"
    }

    data class AckTimeout(val attempts: Int) : Ecr17Failure {
        override val message get() = "ECR17: no ACK after $attempts attempt(s)"
    }

    data class ResponseTimeout(val timeoutMs: Long) : Ecr17Failure {
        override val message get() = "ECR17: no application response before timeout ($timeoutMs ms)"
    }

    data class Disconnected(val cause: CloseCause, val detail: String?) : Ecr17Failure {
        override val message get() = "ECR17: connection closed ($cause)" + (detail?.let { ": $it" } ?: "")
    }

    data class WriteFailed(val detail: String?) : Ecr17Failure {
        override val message get() = "ECR17: write failed" + (detail?.let { ": $it" } ?: "")
    }

    data class Cancelled(val byUser: Boolean) : Ecr17Failure {
        override val message get() = if (byUser) "ECR17: cancelled by user" else "ECR17: cancelled"
    }

    /**
     * Eccezione inattesa (journal non scrivibile prima del primo invio, bug, errore del parser) catturata dal
     * client o dal repository. Con NotSent SOLO se nessuna scrittura del frame principale è stata tentata (M10);
     * dopo il primo tentativo di scrittura la stessa failure arriva dentro Unknown (journaled) o Failed.
     */
    data class Internal(val detail: String) : Ecr17Failure {
        override val message get() = "ECR17: internal error: $detail"
    }
}
