package it.prime.protocollo17.ecr17.profile

/** Profili v1 (U1): solo Ingenico e CCV. */
enum class TerminalProfileId { INGENICO, CCV }

/** Livello di evidenza (L§10): CAPTURE = osservato sul filo; ASSUMPTION = dedotto, da verificare. */
enum class Evidence { CAPTURE, ASSUMPTION }

/**
 * Profilo di terminale: default delle opzioni e livello di evidenza.
 *
 * Il profilo non è un override delle impostazioni: le opzioni sono persistite per intero (§5.2.1) e il
 * profilo fornisce solo i valori di "Ripristina valori del profilo", più le note mostrate all'operatore.
 */
data class TerminalProfile(
    val id: TerminalProfileId,
    val displayName: String,
    val defaultPort: Int?,               // null = porta obbligatoria
    val defaults: ProtocolOptions,
    val evidence: Evidence,
    val notes: String,
)

/** Catalogo dei profili v1. */
object TerminalProfiles {
    /** Porte tipiche ZVT/OPI: avviso "non è Protocollo 17" (L§10.7). */
    val ZVT_OPI_PORTS: Set<Int> = setOf(20002, 20007, 20011)

    val INGENICO = TerminalProfile(
        id = TerminalProfileId.INGENICO, displayName = "Ingenico", defaultPort = 5577,
        defaults = ProtocolOptions(), evidence = Evidence.CAPTURE,
        notes = "Filo osservato con IAE37.dll 5.45: LRC stx, ACK 06 03 7A, una connessione per operazione, implicit go-ahead.",
    )
    val CCV = TerminalProfile(
        id = TerminalProfileId.CCV, displayName = "CCV", defaultPort = null,
        defaults = ProtocolOptions(), evidence = Evidence.ASSUMPTION,
        notes = "Clone del profilo Ingenico: nessuna cattura CCV disponibile (ANALISI_LARAVEL §10). Porta obbligatoria.",
    )

    // I due profili hanno oggi gli STESSI default: per questo le opzioni sono persistite per intero (§5.2.1)
    // e il profilo fornisce solo i valori di "Ripristina valori del profilo". Divergeranno con una cattura CCV.
    val all: List<TerminalProfile> = listOf(INGENICO, CCV)

    fun of(id: TerminalProfileId): TerminalProfile = all.first { it.id == id }
}
