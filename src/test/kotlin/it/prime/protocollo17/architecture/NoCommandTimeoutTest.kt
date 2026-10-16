package it.prime.protocollo17.architecture

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * M8 (§1.5): nessun timeout esterno attorno a un comando. Il timeout di un comando è del protocollo (ACK, esito,
 * drain, dentro la sessione): un `withTimeout` in UI o repository interromperebbe un pagamento già inviato e
 * lascerebbe l'esito sconosciuto senza che il terminale l'abbia deciso. Il compilatore non lo vede, questo test sì.
 *
 * Vietati `withTimeout(` e `withTimeoutOrNull(` in tutto `presentation` e tutto `data`, con eccezioni dichiarate per
 * file e funzione. L'unica attorno a un comando è `TerminalSessionService.shutdown`: alla chiusura dell'app il
 * comando è già annullato e l'attesa serve solo a non bloccare l'uscita se il journal non risponde. Le altre due
 * attendono la chiusura di risorse che non sono comandi (engine del simulatore, scrittore del log traffico).
 */
class NoCommandTimeoutTest {

    private val files = SourceImports.sources("presentation") + SourceImports.sources("data")

    /** Eccezione ammessa: file (relativo a src/main/kotlin), funzione che contiene la chiamata e motivo. */
    private data class Allowed(val file: String, val function: String, val reason: String)

    private val allowed = listOf(
        Allowed(
            file = "it/prime/protocollo17/presentation/state/TerminalSessionService.kt",
            function = "shutdown",
            reason = "chiusura app: comando già annullato, attesa limitata della scrittura del journal",
        ),
        Allowed(
            file = "it/prime/protocollo17/data/local/simulator/SimulatorControllerImpl.kt",
            function = "close",
            reason = "chiusura del controller: arresto dell'engine del simulatore, nessun comando",
        ),
        Allowed(
            file = "it/prime/protocollo17/data/local/traffic/TrafficRecorder.kt",
            function = "close",
            reason = "chiusura del log traffico: svuotamento del canale, nessun comando",
        ),
    )

    private val timeoutCall = Regex("""\bwithTimeout(?:OrNull)?\s*\(""")
    private val timeoutImport = Regex("""^import\s+kotlinx\.coroutines\.withTimeout(?:OrNull)?\b""")
    private val funDecl = Regex("""\bfun\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?(\w+)\s*\(""")

    /** Una chiamata trovata: file, riga e funzione che la contiene (null se fuori da ogni funzione). */
    private data class Call(val file: String, val line: Int, val function: String?) {
        override fun toString(): String = "$file:$line  in ${function ?: "<fuori da una funzione>"}"
    }

    private fun calls(file: File): List<Call> {
        val code = SourceImports.codeWithoutComments(file)
        val functions = funDecl.findAll(code).map { it.range.first to it.groupValues[1] }.toList()
        return timeoutCall.findAll(code).map { match ->
            val enclosing = functions.lastOrNull { (start, _) -> start < match.range.first }?.second
            Call(SourceImports.rel(file), SourceImports.lineAt(code, match.range.first), enclosing)
        }.toList()
    }

    private fun isAllowed(call: Call): Boolean = allowed.any { it.file == call.file && it.function == call.function }

    /** Una cartella di lavoro sbagliata farebbe passare a vuoto i controlli seguenti (zero file, zero violazioni). */
    @Test
    fun `i sorgenti di presentation e data esistono`() {
        assertTrue(files.size >= 40, "sorgenti non trovati (${files.size}); cartella di lavoro: ${File(".").absolutePath}")
    }

    /** Un withTimeout in UI o repository taglierebbe un comando già inviato: l'esito resterebbe sconosciuto (M8). */
    @Test
    fun `nessun withTimeout in presentation e data fuori dalle eccezioni dichiarate`() {
        val offenders = files.flatMap(::calls).filterNot(::isAllowed)
        assertTrue(
            offenders.isEmpty(),
            "M8: niente withTimeout/withTimeoutOrNull attorno ai comandi in presentation e data (§1.5). Il timeout è " +
                "del protocollo; l'unica eccezione attorno a un comando è TerminalSessionService.shutdown. Chiamate:\n" +
                offenders.joinToString("\n"),
        )
    }

    /** Anche l'import conta: un `import … withTimeout as t` sfuggirebbe al controllo sul nome della chiamata. */
    @Test
    fun `withTimeout importato solo dai file delle eccezioni`() {
        val allowedFiles = allowed.map { it.file }.toSet()
        val offenders = files.filter { SourceImports.rel(it) !in allowedFiles }.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                if (timeoutImport.containsMatchIn(line.trim())) "${SourceImports.rel(file)}:${index + 1}  ${line.trim()}" else null
            }
        }
        assertTrue(offenders.isEmpty(), "M8: import di withTimeout fuori dalle eccezioni dichiarate:\n" + offenders.joinToString("\n"))
    }

    /** Un'eccezione senza più la chiamata va tolta: un elenco vecchio coprirebbe in silenzio un timeout nuovo. */
    @Test
    fun `ogni eccezione dichiarata corrisponde a una chiamata`() {
        val found = files.flatMap(::calls)
        val stale = allowed.filter { exception -> found.none { it.file == exception.file && it.function == exception.function } }
        assertTrue(stale.isEmpty(), "eccezioni senza chiamata, da togliere:\n" + stale.joinToString("\n") { "${it.file} ${it.function}: ${it.reason}" })
    }
}
