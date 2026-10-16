package it.prime.protocollo17.domain.model.simulator

// Stato del simulatore per la UI (skill embedded-server §1): la presentation vede questi modelli e mai
// l'engine `ecr17.sim`, che non può importare (§2.10).

/** Voce del log del simulatore. direction "IN" | "OUT" | ""; hex già mascherato (M9). */
data class SimLogEntry(val id: Long, val at: Long, val direction: String, val text: String, val hex: String?)  // direction "IN" | "OUT" | ""; hex già mascherato

/** Contatori mostrati nella finestra Simulatore. */
data class SimStats(val connections: Int = 0, val requests: Int = 0, val rejectedForLrc: Int = 0, val lastCommand: Char? = null, val posTotalCents: Long = 0)

/**
 * Perché il simulatore non è partito o non è più in ascolto. È uno stato visibile all'utente e non un'eccezione:
 * una porta occupata è un caso normale (un'altra istanza, un altro programma) e va spiegata accanto al campo porta.
 */
sealed interface SimulatorFailure {
    data class PortInUse(val port: Int) : SimulatorFailure
    data class Unexpected(val message: String) : SimulatorFailure

    /** L'ascolto è morto da solo dopo un avvio riuscito: le connessioni dell'ECR sono rifiutate finché non si riavvia. */
    data class ListenerDied(val port: Int) : SimulatorFailure
}

/** Ciclo di vita del simulatore come lo vede l'utente. */
sealed interface SimulatorStatus {
    data object Stopped : SimulatorStatus
    data object Starting : SimulatorStatus
    data class Running(val port: Int) : SimulatorStatus
    data class Failed(val failure: SimulatorFailure) : SimulatorStatus
}

/** Porta in ascolto se il simulatore è in esecuzione, altrimenti null. */
val SimulatorStatus.runningPort: Int? get() = (this as? SimulatorStatus.Running)?.port
