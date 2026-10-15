package it.prime.protocollo17.domain.model.command

import it.prime.protocollo17.ecr17.event.ProtocolWarning
import it.prime.protocollo17.ecr17.model.CommandCode
import it.prime.protocollo17.ecr17.model.CommandOutcome
import it.prime.protocollo17.ecr17.model.PhaseChange
import it.prime.protocollo17.ecr17.model.ReceiptLine

/**
 * Passi di un comando visti dal servizio applicativo (PosTerminalRepository.execute, §5.5).
 *
 * Il flusso termina con ESATTAMENTE un [Finished] che porta l'esito vero del protocollo: un errore del journal
 * viaggia accanto ([Finished.journalError]) e non lo sostituisce mai, così un disco pieno non trasforma un
 * APPROVATO in un errore generico (R7).
 */
sealed interface CommandProgress {
    data class Started(val command: CommandCode, val attemptId: String?, val at: Long) : CommandProgress
    data class Phase(val change: PhaseChange) : CommandProgress
    data class Progress(val text: String, val at: Long) : CommandProgress
    data class Receipt(val line: ReceiptLine, val at: Long) : CommandProgress
    data class Warning(val warning: ProtocolWarning) : CommandProgress

    /** journalError: messaggio (italiano) se la chiusura del tentativo nel journal è fallita; l'outcome resta quello vero. */
    data class Finished(val outcome: CommandOutcome, val attemptId: String?, val journalError: String? = null) : CommandProgress
}
