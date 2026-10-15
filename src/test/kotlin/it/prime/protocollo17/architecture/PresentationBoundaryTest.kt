package it.prime.protocollo17.architecture

import it.prime.protocollo17.architecture.SourceImports.isUnder
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Confini di `presentation`, `presentation.state` e `domain` (§2.10).
 *
 * La presentation vede il protocollo solo attraverso i tipi valore (`ecr17.model/profile/error/event` e tre
 * oggetti di `ecr17.wire` nominati) e il data layer solo attraverso le due classi locali tollerate: se un
 * ViewModel importasse il client o il journal concreto, i suoi test richiederebbero socket e file veri.
 * `presentation.state` contiene servizi app-scoped testabili senza UI, quindi niente Compose.
 * Il dominio resta senza UI, DI e data layer.
 */
class PresentationBoundaryTest {

    private val root = "it.prime.protocollo17"
    private val ecr17 = "$root.ecr17"

    private val presentationFiles = SourceImports.sources("presentation")
    private val stateFiles = SourceImports.sources("presentation/state")
    private val domainFiles = SourceImports.sources("domain")

    // ─── Regole ecr17 comuni ─────────────────────────────────────────────────────

    /** Sottopackage di ecr17 importabili per intero da domain e presentation. */
    private val ecr17ValuePackages = listOf("model", "profile", "error", "event").map { "$ecr17.$it" }

    /** Tipi di ecr17.wire / ecr17.message ammessi per nome (§2.10): tutto il resto di quei package è vietato. */
    private val presentationNamedEcr17 = listOf("$ecr17.wire.LrcMode", "$ecr17.wire.ControlLrcRule", "$ecr17.wire.Hex")
    private val domainNamedEcr17 = presentationNamedEcr17 + "$ecr17.message.RequestEncoder"

    private fun ecr17Allowed(path: String, named: List<String>): Boolean =
        ecr17ValuePackages.any { path.isUnder(it) } || named.any { path.isUnder(it) }

    // ─── Test ─────────────────────────────────────────────────────────────────────

    /** Una cartella di lavoro sbagliata farebbe passare a vuoto i controlli seguenti (zero file, zero violazioni). */
    @Test
    fun `i sorgenti di presentation e domain esistono`() {
        assertTrue(
            presentationFiles.isNotEmpty() && stateFiles.isNotEmpty() && domainFiles.isNotEmpty(),
            "sorgenti non trovati; cartella di lavoro: ${java.io.File(".").absolutePath}",
        )
    }

    /** Un ViewModel che importa client, encoder o journal concreto avrebbe bisogno di socket e file nei test. */
    @Test
    fun `presentation importa da ecr17 e data solo cio che la tabella ammette`() {
        val allowedData = listOf("$root.data.local.settings.SettingsRepository", "$root.data.local.AppDirs")
        val offenders = SourceImports.imports(presentationFiles).filter { imp ->
            val p = imp.path
            when {
                p.isUnder(ecr17) -> !ecr17Allowed(p, presentationNamedEcr17)
                p.isUnder("$root.data") -> allowedData.none { p.isUnder(it) }
                else -> false
            }
        }
        assertTrue(
            offenders.isEmpty(),
            "presentation può importare domain, ecr17.(model|profile|error|event), ecr17.wire.{LrcMode, " +
                "ControlLrcRule, Hex}, data.local.settings.SettingsRepository e data.local.AppDirs (§2.10). " +
                "Import vietati:\n" + offenders.joinToString("\n"),
        )
    }

    /** I servizi app-scoped di presentation/state devono restare testabili con JUnit puro, senza runtime Compose. */
    @Test
    fun `presentation state non importa Compose`() {
        val offenders = SourceImports.imports(stateFiles).filter { imp ->
            imp.path.isUnder("androidx") || imp.path.isUnder("org.jetbrains.compose")
        }
        assertTrue(
            offenders.isEmpty(),
            "presentation/state contiene servizi app-scoped testabili senza UI: niente androidx.* né " +
                "org.jetbrains.compose.* (§2.10). Import vietati:\n" + offenders.joinToString("\n"),
        )
    }

    /** Il dominio non dipende da UI, DI, data layer né dal motore ecr17 (sessione, client, trasporto). */
    @Test
    fun `domain non importa UI, DI, data, presentation ne il motore del protocollo`() {
        val forbidden = listOf(
            "androidx", "org.jetbrains.compose", "org.koin", "cafe.adriel", "java.awt", "javax.swing",
            "kotlinx.coroutines.swing", "$root.data", "$root.presentation", "$root.di",
        )
        val offenders = SourceImports.imports(domainFiles).filter { imp ->
            val p = imp.path
            forbidden.any { p.isUnder(it) } || (p.isUnder(ecr17) && !ecr17Allowed(p, domainNamedEcr17))
        }
        assertTrue(
            offenders.isEmpty(),
            "domain può importare stdlib, kotlinx.serialization, coroutines core, ecr17.(model|profile|error|event), " +
                "ecr17.wire.{LrcMode, ControlLrcRule, Hex} ed ecr17.message.RequestEncoder (§2.10). " +
                "Import vietati:\n" + offenders.joinToString("\n"),
        )
    }
}
