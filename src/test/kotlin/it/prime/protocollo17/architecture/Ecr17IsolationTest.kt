package it.prime.protocollo17.architecture

import it.prime.protocollo17.architecture.SourceImports.isUnder
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Il sottoalbero `ecr17` resta puro (U12, §2.10): solo stdlib, JDK e kotlinx-coroutines-core, così si può estrarre
 * in un modulo separato senza trascinarsi UI, DI o logging.
 *
 * Più due regole sui dispatcher: nessun `Dispatchers.Main` in tutto `ecr17` (non esiste fuori da un'app con UI) e
 * nessun `Dispatchers.IO` in client e sessione. Basta un `withContext(Dispatchers.IO)` nel client perché
 * `withTimeoutOrNull` usi il tempo reale e i test in tempo virtuale attendano 120 s veri (§2.10, §7.1).
 */
class Ecr17IsolationTest {

    private val ecr17Files = SourceImports.sources("ecr17")

    /** Prefissi ammessi dalla prima riga della tabella di §2.10. */
    private val allowed = listOf("kotlin", "java", "kotlinx.coroutines", "it.prime.protocollo17.ecr17")

    /** Divieti espliciti di §2.10 (alcuni stanno dentro un prefisso ammesso, quindi vanno controllati prima). */
    private val forbidden = listOf(
        "androidx", "org.jetbrains.compose", "org.koin", "cafe.adriel", "java.awt", "javax.swing",
        "kotlinx.coroutines.swing", "kotlinx.serialization", "org.slf4j", "ch.qos",
        "it.prime.protocollo17.domain", "it.prime.protocollo17.data", "it.prime.protocollo17.presentation",
        "it.prime.protocollo17.di",
    )

    /** Una cartella di lavoro sbagliata farebbe passare a vuoto i controlli seguenti (zero file, zero violazioni). */
    @Test
    fun `i sorgenti di ecr17 esistono`() {
        assertTrue(
            ecr17Files.size >= 20,
            "sorgenti ecr17 non trovati (${ecr17Files.size}); cartella di lavoro: ${java.io.File(".").absolutePath}",
        )
    }

    /** Un import di UI, DI, logging o di un altro layer dentro ecr17 lo renderebbe non estraibile (U12). */
    @Test
    fun `ecr17 importa solo stdlib, JDK e coroutines core`() {
        val offenders = SourceImports.imports(ecr17Files).filter { imp ->
            forbidden.any { imp.path.isUnder(it) } || allowed.none { imp.path.isUnder(it) }
        }
        assertTrue(
            offenders.isEmpty(),
            "ecr17 deve restare puro (§2.10: kotlin.*, java.* senza AWT, kotlinx.coroutines core, ecr17 stesso). " +
                "Import vietati:\n" + offenders.joinToString("\n"),
        )
    }

    /** Dispatchers.Main in ecr17 lo legherebbe a un'app con UI e farebbe fallire i test JVM senza Main. */
    @Test
    fun `nessun Dispatchers Main in ecr17`() {
        val main = Regex("""\bDispatchers\s*\.\s*Main\b""")
        val offenders = ecr17Files.filter { main.containsMatchIn(SourceImports.codeWithoutComments(it)) }
            .map { SourceImports.rel(it) }
        assertTrue(
            offenders.isEmpty(),
            "Dispatchers.Main non esiste fuori da un'app con UI: ecr17 non deve usarlo (§2.10). File:\n" +
                offenders.joinToString("\n"),
        )
    }

    /** Un Dispatchers.IO in client o sessione porterebbe i timeout in tempo reale e i test virtuali a 120 s veri. */
    @Test
    fun `nessun Dispatchers IO in client e sessione`() {
        val io = Regex("""\bDispatchers\s*\.\s*IO\b""")
        val files = SourceImports.sources("ecr17/client") + SourceImports.sources("ecr17/session")
        val offenders = files.filter { io.containsMatchIn(SourceImports.codeWithoutComments(it)) }
            .map { SourceImports.rel(it) }
        assertTrue(
            offenders.isEmpty(),
            "Client e sessione girano sul dispatcher del chiamante: un Dispatchers.IO rompe il tempo virtuale " +
                "dei test (§2.10). Solo TcpTransport ha un proprio scope IO. File:\n" + offenders.joinToString("\n"),
        )
    }
}
