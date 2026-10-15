package it.prime.protocollo17.domain.model.command

import it.prime.protocollo17.ecr17.error.ConfigProblem
import it.prime.protocollo17.ecr17.profile.Ecr17Config
import it.prime.protocollo17.ecr17.profile.TerminalProfile

/** Problema o avviso di configurazione per l'operatore. message in italiano. */
data class ConfigIssue(val key: String, val message: String)   // message in italiano

/**
 * Configurazione effettiva ricavata dalle impostazioni (ConfigResolver.resolve, §5.2.2).
 *
 * Tiene separati i tre livelli: [issues] (valori illeggibili nel file: mai sostituiti in silenzio, L1),
 * [problems] (coerenza strutturale del protocollo, Ecr17Config.validate) e [warnings] (avvisi che non
 * bloccano). Solo i primi due rendono la configurazione non utilizzabile.
 */
data class ConfigResolution(
    val profile: TerminalProfile,
    val config: Ecr17Config,
    /** Valori illeggibili nel file (es. appLrcMode "sxt"): la config NON è utilizzabile finché non si correggono. */
    val issues: List<ConfigIssue>,
    /** Avvisi non bloccanti (porta ZVT/OPI, profilo CCV non verificato, valori fuori dai range dell'operatore). */
    val warnings: List<ConfigIssue>,
) {
    val problems: List<ConfigProblem> get() = config.validate()
    val usable: Boolean get() = issues.isEmpty() && problems.isEmpty()
}
