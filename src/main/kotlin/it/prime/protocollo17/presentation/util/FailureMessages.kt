package it.prime.protocollo17.presentation.util

import it.prime.protocollo17.domain.model.command.CommandTitles
import it.prime.protocollo17.ecr17.error.ConfigProblem
import it.prime.protocollo17.ecr17.error.Ecr17Failure
import it.prime.protocollo17.ecr17.error.RequestField
import it.prime.protocollo17.ecr17.error.ValidationError
import it.prime.protocollo17.ecr17.event.WarningCode
import it.prime.protocollo17.ecr17.model.CloseCause
import it.prime.protocollo17.ecr17.model.CommandCode
import it.prime.protocollo17.ecr17.model.CommandOutcome
import it.prime.protocollo17.presentation.state.RunStart

/**
 * Messaggi d'errore per l'operatore (italiano, azionabili, mai codici nudi): traducono Ecr17Failure e gli altri
 * esiti negativi. I `message` di Ecr17Failure restano in inglese con il prefisso "ECR17:" per log e journal.
 *
 * Prima (stub di WP0) restituiva i testi grezzi "ECR17: no ACK after 3 attempt(s)": veri ma inutili a chi sta
 * davanti al terminale. Ora ogni testo dice cosa è successo e cosa fare, e quando l'esito di un comando
 * journaled è sconosciuto aggiunge SEMPRE l'indicazione su Ultimo esito (G): è l'unico modo per sbloccare i
 * pagamenti (U7), e G non parte mai da solo.
 */
object FailureMessages {

    /** Suffisso [G] per i comandi che muovono denaro: blocco di tutti i pagamenti (§6.19). */
    private const val G_SUFFIX_MONEY = " ESITO SCONOSCIUTO: esegui Ultimo esito (G) prima di un nuovo pagamento."

    /** Suffisso [G] per H/C, che bloccano solo la propria famiglia di risposta (§5.8). */
    private const val G_SUFFIX_FAMILY = " Esito sconosciuto: verifica con Ultimo esito (G) o Totali (T) prima di ripeterlo."

    /** Testo per qualsiasi esito non positivo (null per Answered/Acknowledged senza revisione). Punto d'ingresso principale. */
    fun forOutcome(outcome: CommandOutcome): String? = when (outcome) {
        is CommandOutcome.Answered -> if (outcome.needsReview) reviewText(outcome) else null
        is CommandOutcome.Acknowledged -> null
        is CommandOutcome.NotSent -> text(outcome.failure, outcome.command, unknown = false, sent = false)
        is CommandOutcome.Refused -> text(outcome.failure, outcome.command, unknown = false, sent = true)
        is CommandOutcome.Failed -> text(outcome.failure, outcome.command, unknown = false, sent = true)
        is CommandOutcome.Unknown -> text(outcome.failure, outcome.command, unknown = true, sent = true)
    }

    /** unknown = l'esito è sconosciuto (CommandOutcome.Unknown): aggiunge sempre l'indicazione su G. */
    fun forFailure(failure: Ecr17Failure, command: CommandCode, unknown: Boolean): String =
        text(failure, command, unknown = unknown, sent = unknown)

    /** "Importo: al massimo 8 cifre" — campo e problema, senza il prefisso tecnico "ECR17:". */
    fun forValidation(error: ValidationError): String {
        val label = fieldLabel(error.field)
        val problem = when (error) {
            is ValidationError.FieldOverflow -> when (error.field) {
                RequestField.RECEIPT_TEXT -> "al massimo ${error.width} caratteri"
                RequestField.AMOUNT -> "importo oltre il massimo consentito"
                else -> "al massimo ${error.width} cifre"
            }
            is ValidationError.NotDigits -> "solo cifre (0-9)"
            is ValidationError.NonAscii -> "carattere non ASCII in posizione ${error.index + 1}"
            is ValidationError.NegativeAmount -> "non può essere negativo"
            is ValidationError.Required -> "obbligatorio"
        }
        return "$label: $problem"
    }

    /** Problemi strutturali di Ecr17Config.validate() (chiavi "host", "port", nomi delle opzioni) in italiano. */
    fun forConfigProblem(problem: ConfigProblem): String {
        val message = problem.message
        return when (problem.key) {
            "host" -> "Indirizzo del terminale obbligatorio: inseriscilo in Opzioni"
            "port" -> if (message.startsWith("is required")) {
                "Porta del terminale obbligatoria (il profilo CCV non ha una porta predefinita): inseriscila in Opzioni"
            } else {
                "Porta del terminale non valida: deve essere tra 1 e 65535" + wasSuffix(message)
            }
            else -> {
                val label = OPTION_LABELS[problem.key]
                when {
                    // ConfigIssue di ConfigResolver arrivano già in italiano (valori illeggibili nel file).
                    label == null -> message
                    message.startsWith("must be greater than 0") -> "$label: deve essere maggiore di 0" + wasSuffix(message)
                    message.startsWith("must be 0 or greater") -> "$label: non può essere negativo" + wasSuffix(message)
                    message.startsWith("must be in ") -> {
                        val range = Regex("""must be in (\d+)\.\.(\d+)""").find(message)
                        val bounds = range?.let { " tra ${it.groupValues[1]} e ${it.groupValues[2]}" }.orEmpty()
                        "$label: valore$bounds" + wasSuffix(message)
                    }
                    else -> "$label: $message"
                }
            }
        }
    }

    fun forCloseCause(cause: CloseCause): String = when (cause) {
        CloseCause.LOCAL -> "Connessione chiusa dall'app"
        CloseCause.PEER_CLOSED -> "Connessione chiusa dal terminale"
        CloseCause.RESET -> "Connessione interrotta (reset)"
        CloseCause.IO_ERROR -> "Errore di rete"
        CloseCause.ABORTED -> "Connessione annullata"
        CloseCause.WRITE_TIMEOUT -> "Invio bloccato oltre il timeout di scrittura"
    }

    fun forRunStart(start: RunStart): String? = when (start) {
        RunStart.Started -> null
        RunStart.Busy -> BUSY
        is RunStart.Blocked -> {
            val n = start.blocking.size
            val what = if (n == 1) "un tentativo con esito da risolvere" else "$n tentativi con esito da risolvere"
            "Comando bloccato: c'è $what. Esegui Ultimo esito (G) o chiudi il tentativo (banner o Journal)."
        }
        is RunStart.InvalidConfig ->
            "Configurazione non utilizzabile: ${start.messages.joinToString("; ")}. Apri Opzioni (Ctrl+,)."
    }

    // ─── Testi per failure ────────────────────────────────────────────────────────

    private const val BUSY = "Un comando è già in corso: attendi la fine o premi Annulla (Esc)."

    /** sent = almeno un tentativo di scrittura del frame principale (M10): distingue l'Internal "non partito". */
    private fun text(failure: Ecr17Failure, command: CommandCode, unknown: Boolean, sent: Boolean): String {
        val base = baseText(failure, unknown = unknown, sent = sent)
        // [G] sempre in coda quando l'esito è sconosciuto: è l'unica via per sbloccare (U7).
        return if (unknown) base + gSuffix(command) else base
    }

    private fun baseText(failure: Ecr17Failure, unknown: Boolean, sent: Boolean): String {
        return when (failure) {
            is Ecr17Failure.Validation ->
                "Dati non validi: ${failure.errors.joinToString("; ") { forValidation(it) }}. Correggi il campo evidenziato."
            is Ecr17Failure.ConfigInvalid ->
                "Configurazione incompleta: ${failure.problems.joinToString("; ") { forConfigProblem(it) }}. Apri Opzioni (Ctrl+,)."
            Ecr17Failure.Busy -> BUSY
            is Ecr17Failure.ConnectFailed ->
                "Connessione a ${failure.endpoint} non riuscita${failure.detail.inParentheses()}. " +
                    "Verifica IP e porta in Opzioni (Ingenico: porta tipica 5577)."
            is Ecr17Failure.ConnectTimeout ->
                "Il terminale ${failure.endpoint} non risponde entro ${formatSeconds(failure.timeoutMs)} s. " +
                    "Controlla la rete o aumenta il timeout di connessione in Opzioni."
            is Ecr17Failure.PrintingSetupFailed ->
                "Il terminale non ha accettato l'abilitazione della stampa su ECR (E1): il comando NON è stato inviato. " +
                    "Disattiva 'Stampa su ECR' in Opzioni o riprova."
            is Ecr17Failure.Nak -> if (unknown) {
                "NAK dopo invii rimasti senza risposta: un invio precedente potrebbe essere stato eseguito."
            } else {
                "Il terminale ha rifiutato il messaggio (NAK × ${failure.attempts}): il comando non è stato eseguito. " +
                    "Controlla LRC applicativo, Terminal ID ed ECR id in Opzioni."
            }
            is Ecr17Failure.AckTimeout ->
                "Nessun ACK dopo ${failure.attempts} ${if (failure.attempts == 1) "invio" else "invii"}. " +
                    "Verifica porta e LRC (prova Stato con le diverse modalità) e che il terminale sia libero."
            is Ecr17Failure.ResponseTimeout -> if (unknown) {
                "Nessun esito entro ${formatSeconds(failure.timeoutMs)} s."
            } else {
                "Nessuna risposta entro ${formatSeconds(failure.timeoutMs)} s. Il terminale potrebbe essere occupato o " +
                    "senza parametri ECR (lo Stato non risponde se non configurato)."
            }
            is Ecr17Failure.Disconnected -> "${forCloseCause(failure.cause)} durante lo scambio."
            is Ecr17Failure.WriteFailed -> "Invio non riuscito${failure.detail.inParentheses()}."
            is Ecr17Failure.Cancelled -> (if (failure.byUser) "Annullato dall'operatore." else "Comando annullato.")
            is Ecr17Failure.Internal -> if (!sent) {
                "Errore interno (${failure.detail}): il comando NON è partito. Controlla il file di log e lo spazio su disco."
            } else {
                "Errore interno dopo l'invio (${failure.detail}). Controlla il file di log."
            }
        }
    }

    private fun gSuffix(command: CommandCode): String = if (command.journaled && !command.movesMoney) G_SUFFIX_FAMILY else G_SUFFIX_MONEY

    /** Esito ricevuto ma "da verificare" (M6): possibile doppio addebito o esito prima dell'ACK. */
    private fun reviewText(outcome: CommandOutcome.Answered): String {
        val codes = outcome.reviewWarnings.map { it.code }.toSet()
        val title = CommandTitles.title(outcome.command)
        return when {
            WarningCode.POSSIBLE_DUPLICATE in codes ->
                "$title da verificare: esito arrivato dopo ${outcome.trace.timeoutRetransmissions} reinvii per ACK " +
                    "mancante, possibile doppio addebito. Verifica con Ultimo esito (G) o Totali prima di un nuovo pagamento."
            else ->
                "$title da verificare: esito arrivato prima dell'ACK. Verificalo con Ultimo esito (G) prima di un nuovo pagamento."
        }
    }

    private fun fieldLabel(field: RequestField): String = when (field) {
        RequestField.TERMINAL_ID -> "Terminal ID"
        RequestField.CASH_REGISTER_ID -> "ECR id"
        RequestField.AMOUNT -> "Importo"
        RequestField.RECEIPT_TEXT -> "Testo scontrino"
        RequestField.STAN -> "STAN"
        RequestField.PRE_AUTH_CODE -> "Codice preautorizzazione"
    }

    private fun String?.inParentheses(): String = this?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()

    /** "(was 0)" del messaggio strutturale ⇒ " (ora 0)". */
    private fun wasSuffix(message: String): String =
        Regex("""\(was (-?\d+)\)""").find(message)?.let { " (ora ${it.groupValues[1]})" }.orEmpty()

    /** Chiavi di ProtocolOptions.validate() ⇒ etichette delle Opzioni. */
    private val OPTION_LABELS: Map<String, String> = mapOf(
        "connectTimeoutMs" to "Timeout di connessione",
        "ackTimeoutMs" to "Timeout ACK",
        "responseTimeoutMs" to "Timeout esito",
        "writeTimeoutMs" to "Timeout di scrittura",
        "retryDelayMs" to "Pausa tra ritrasmissioni",
        "postResultDrainMs" to "Drain post-esito",
        "receiptDrainMs" to "Drain scontrini",
        "preSendQuietMs" to "Attesa pre-invio",
        "nakRetryCount" to "Reinvii su NAK",
        "ackTimeoutRetransmitCount" to "Reinvii su ACK mancante",
    )
}
