package it.prime.protocollo17.presentation.model

import it.prime.protocollo17.domain.model.command.CommandTitles
import it.prime.protocollo17.domain.model.journal.JournalEntry
import it.prime.protocollo17.domain.model.journal.JournalPhase
import it.prime.protocollo17.domain.model.journal.ResolutionKind
import it.prime.protocollo17.domain.util.formatCents
import it.prime.protocollo17.ecr17.model.CloseCause
import it.prime.protocollo17.presentation.util.FailureMessages
import it.prime.protocollo17.presentation.util.formatSeconds

/**
 * Testi italiani di un tentativo del journal, condivisi dal banner della Console, dalla finestra Journal e dal
 * dialog di risoluzione: lo stesso tentativo si chiama allo stesso modo ovunque.
 *
 * Il journal conserva il `message` inglese di Ecr17Failure (stabile, per i log): qui lo si riconosce per
 * forma e lo si traduce nella frase breve del banner ("nessun esito entro 120 s"). Un testo non riconosciuto
 * (ad esempio quello già italiano del recupero all'avvio) passa così com'è, senza il prefisso tecnico.
 */
object AttemptTexts {

    /** "Pagamento 0,10 €"; "Tentativo con dati incompleti" se il record CREATED era illeggibile. */
    fun title(entry: JournalEntry): String {
        val command = entry.command ?: return "Tentativo con dati incompleti"
        val amount = entry.amountCents?.let { " ${formatCents(it)}" }.orEmpty()
        return CommandTitles.title(command) + amount
    }

    /** Perché il tentativo è da risolvere, in una frase breve (banner, dialog). */
    fun reason(entry: JournalEntry): String = when {
        !entry.persisted -> "journal non scritto su disco: il tentativo resta bloccante finché non lo risolvi"
        entry.needsReview -> reviewReason(entry.resultRecord?.reviewReason)
        entry.incomplete -> "dati del tentativo illeggibili nel journal"
        entry.phase == JournalPhase.UNKNOWN -> {
            val base = failureText(entry.failureRecord?.failure)
            if (entry.receivedRecord != null) "$base; un esito era stato ricevuto prima dell'interruzione" else base
        }
        else -> phaseLabel(entry).replaceFirstChar { it.lowercase() }
    }

    fun reviewReason(code: String?): String = when {
        code == null -> "esito da verificare"
        "POSSIBLE_DUPLICATE" in code -> "possibile doppio addebito: esito arrivato dopo reinvii per ACK mancante"
        "EARLY_RESULT" in code -> "esito arrivato prima dell'ACK"
        else -> "esito da verificare ($code)"
    }

    /** Messaggio inglese di Ecr17Failure (journal) ⇒ frase italiana breve. */
    fun failureText(message: String?): String {
        if (message.isNullOrBlank()) return "esito sconosciuto"
        val text = message.removePrefix("ECR17: ")
        Regex("""no application response before timeout \((\d+) ms\)""").find(text)?.let {
            return "nessun esito entro ${formatSeconds(it.groupValues[1].toLong())} s"
        }
        Regex("""no ACK after (\d+) attempt""").find(text)?.let { return "nessun ACK dopo ${it.groupValues[1]} invii" }
        Regex("""connection closed \((\w+)\)""").find(text)?.let { match ->
            val cause = CloseCause.entries.firstOrNull { it.name == match.groupValues[1] }
            val what = cause?.let(FailureMessages::forCloseCause) ?: "Connessione chiusa"
            return "${what.replaceFirstChar { it.lowercase() }} durante lo scambio"
        }
        return when {
            text.startsWith("cancelled by user") -> "annullato dall'operatore dopo l'invio"
            text.startsWith("cancelled") -> "annullato dopo l'invio"
            text.startsWith("NAK after") -> "NAK dopo invii rimasti senza risposta"
            text.startsWith("write failed") -> "invio non riuscito"
            text.startsWith("internal error: ") -> "errore interno: ${text.removePrefix("internal error: ")}"
            else -> text
        }
    }

    /** Etichetta della fase per la lista del Journal. */
    fun phaseLabel(entry: JournalEntry): String = when (entry.phase) {
        JournalPhase.RESULT -> when {
            entry.needsReview -> "Da verificare"
            entry.resultRecord?.outcome == "APPROVED" -> "Approvato"
            entry.resultRecord?.outcome == "DECLINED" -> "Rifiutato"
            else -> "Esito ricevuto"
        }
        JournalPhase.UNKNOWN -> "Esito sconosciuto"
        JournalPhase.NOT_SENT -> "Non inviato"
        JournalPhase.REFUSED -> "Rifiutato (NAK)"
        JournalPhase.RESOLVED -> "Risolto: " + resolutionLabel(entry.resolutionRecord?.resolution)
        JournalPhase.CREATED, JournalPhase.SENDING, JournalPhase.ACKED, JournalPhase.GO_AHEAD, JournalPhase.RECEIVED ->
            "In corso (${recordPhaseLabel(entry.phase)})"
        JournalPhase.EVIDENCE -> "Prova allegata"
    }

    fun tone(entry: JournalEntry): ResultTone = when (entry.phase) {
        JournalPhase.RESULT -> when {
            entry.needsReview -> ResultTone.WARNING
            entry.resultRecord?.outcome == "APPROVED" -> ResultTone.SUCCESS
            entry.resultRecord?.outcome == "DECLINED" -> ResultTone.DANGER
            else -> ResultTone.INFO
        }
        JournalPhase.UNKNOWN -> ResultTone.WARNING
        JournalPhase.REFUSED -> ResultTone.DANGER
        JournalPhase.NOT_SENT, JournalPhase.RESOLVED -> ResultTone.NEUTRAL
        else -> ResultTone.INFO
    }

    fun resolutionLabel(kind: ResolutionKind?): String = when (kind) {
        ResolutionKind.CONFIRMED_BY_G -> "esito confermato con G"
        ResolutionKind.NOT_EXECUTED -> "non eseguito"
        ResolutionKind.REVIEWED -> "verificato"
        ResolutionKind.DISMISSED -> "chiuso dall'operatore"
        null -> "chiuso"
    }

    /** Nome italiano di una fase del journal (timeline del dettaglio). */
    fun recordPhaseLabel(phase: JournalPhase): String = when (phase) {
        JournalPhase.CREATED -> "creato"
        JournalPhase.SENDING -> "invio"
        JournalPhase.ACKED -> "ACK ricevuto"
        JournalPhase.GO_AHEAD -> "go-ahead implicito"
        JournalPhase.RECEIVED -> "esito ricevuto"
        JournalPhase.EVIDENCE -> "prova allegata"
        JournalPhase.RESULT -> "esito"
        JournalPhase.NOT_SENT -> "non inviato"
        JournalPhase.REFUSED -> "rifiutato (NAK)"
        JournalPhase.UNKNOWN -> "esito sconosciuto"
        JournalPhase.RESOLVED -> "risolto"
    }

    /** Payload ricevuti o arrivati in ritardo allegati al tentativo (prove per il dialog §6.8). */
    fun evidence(entry: JournalEntry): List<String> = entry.records
        .filter { it.phase == JournalPhase.RECEIVED || it.phase == JournalPhase.EVIDENCE }
        .mapNotNull { record -> record.rawResult?.let { raw -> if (record.phase == JournalPhase.EVIDENCE) "$raw  (${record.evidenceNote ?: "orfano"})" else raw } }
}
