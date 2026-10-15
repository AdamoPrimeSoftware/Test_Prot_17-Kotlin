package it.prime.protocollo17.integration

import it.prime.protocollo17.data.local.journal.JournalStore
import it.prime.protocollo17.data.repository.JournalRepositoryImpl
import it.prime.protocollo17.data.repository.PosTerminalRepositoryImpl
import it.prime.protocollo17.domain.model.journal.JournalPhase
import it.prime.protocollo17.domain.model.journal.ResolutionKind
import it.prime.protocollo17.domain.model.settings.AppSettings
import it.prime.protocollo17.domain.model.settings.JournalSettings
import it.prime.protocollo17.domain.model.settings.ProtocolSettings
import it.prime.protocollo17.domain.model.settings.TerminalSettings
import it.prime.protocollo17.domain.util.ConfigResolver
import it.prime.protocollo17.domain.util.PaymentBlockPolicy
import it.prime.protocollo17.ecr17.client.Ecr17Client
import it.prime.protocollo17.ecr17.model.CommandCode
import it.prime.protocollo17.ecr17.model.CommandOutcome
import it.prime.protocollo17.ecr17.model.Ecr17Request
import it.prime.protocollo17.ecr17.model.LastResultRequest
import it.prime.protocollo17.ecr17.model.PaymentRequest
import it.prime.protocollo17.ecr17.model.TransactionResponse
import it.prime.protocollo17.ecr17.sim.SimFault
import it.prime.protocollo17.ecr17.sim.SimulatorConfig
import it.prime.protocollo17.ecr17.sim.TerminalSimulator
import it.prime.protocollo17.ecr17.transport.TcpTransport
import it.prime.protocollo17.presentation.state.AppNotifications
import it.prime.protocollo17.presentation.state.FinishedRun
import it.prime.protocollo17.presentation.state.RunOrigin
import it.prime.protocollo17.presentation.state.RunStart
import it.prime.protocollo17.presentation.state.TerminalSessionService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Percorso completo "P sconosciuto → bloccato → G → risolto → P" (§7.7, §8.10 punto 2) con simulatore reale su
 * loopback (porta 0), client e trasporto TCP reali, journal reale su @TempDir e timeout brevi.
 *
 * Richiede WP1-WP4 (wire, message, client, simulatore): gira dopo il merge. Il controllo con GAssessment (WP6) si
 * aggiunge in integrazione finale: qui si verifica che il journal fornisca gli esiti precedenti (knownResultsBefore)
 * e che G restituisca un esito di transazione confrontabile.
 */
class UnknownRecoveryIntegrationTest {

    @TempDir
    lateinit var dir: File

    private val closeables = mutableListOf<AutoCloseable>()

    @AfterEach
    fun tearDown() {
        closeables.asReversed().forEach { runCatching { it.close() } }
    }

    private class Stack(
        val journal: JournalRepositoryImpl,
        val service: TerminalSessionService,
        val client: Ecr17Client,
    )

    private fun settingsFor(port: Int) = AppSettings(
        terminal = TerminalSettings(profile = "INGENICO", host = "127.0.0.1", port = port),
        // Timeout brevi (§7.1): validi per il protocollo, fuori dai range dell'operatore solo come avviso.
        protocol = ProtocolSettings(ackTimeoutMs = 300, responseTimeoutMs = 2_000, postResultDrainMs = 50, preSendQuietMs = 0),
    )

    private fun simulator(fault: SimFault): Pair<TerminalSimulator, Int> {
        val sim = TerminalSimulator(SimulatorConfig(port = 0, fault = fault, ackDelayMs = 5, progressDelayMs = 20, resultDelayMs = 150))
        closeables += sim
        return sim to runBlocking { sim.start() }
    }

    private fun stack(settings: AppSettings): Stack {
        val journal = JournalRepositoryImpl(JournalStore(File(dir, "journal")), settingsProvider = { JournalSettings() })
        runBlocking { journal.recoverOnStartup() }
        val client = Ecr17Client(configProvider = { ConfigResolver.resolve(settings).config }, transport = TcpTransport())
        val repository = PosTerminalRepositoryImpl(client, journal, settingsProvider = { settings })
        val service = TerminalSessionService(repository, journal, AppNotifications())
        closeables += AutoCloseable { journal.close() }
        closeables += client
        closeables += service
        return Stack(journal, service, client)
    }

    private fun runAndAwait(service: TerminalSessionService, request: Ecr17Request): FinishedRun {
        val before = service.state.value.history.size
        assertEquals(RunStart.Started, service.run(request, RunOrigin.CONSOLE), "avvio di ${request.command}")
        val deadline = System.currentTimeMillis() + 15_000
        while (service.state.value.history.size == before) {
            check(System.currentTimeMillis() < deadline) { "timeout: ${request.command} non concluso" }
            Thread.sleep(20)
        }
        return service.state.value.history.first()
    }

    @Test
    fun `P sconosciuto blocca i pagamenti finche G non lo risolve`() {
        val (sim, port) = simulator(SimFault.SILENT)
        val stack = stack(settingsFor(port))

        // 1. Pagamento senza risposta: esito sconosciuto, tentativo UNKNOWN nel journal.
        val first = runAndAwait(stack.service, PaymentRequest(amountCents = 10))
        assertTrue(first.outcome is CommandOutcome.Unknown, "P ⇒ Unknown: ${first.outcome}")
        val attemptId = first.attemptId!!
        assertEquals(JournalPhase.UNKNOWN, stack.journal.unresolved.value.single().phase, "UNKNOWN nel journal")

        // 2. Un nuovo pagamento è rifiutato prima di partire.
        val blocked = stack.service.run(PaymentRequest(amountCents = 10), RunOrigin.CONSOLE)
        assertTrue(blocked is RunStart.Blocked, "P ⇒ Blocked: $blocked")

        // 3. Il terminale torna a rispondere; l'operatore esegue G (sempre ammesso).
        sim.updateConfig { it.copy(fault = SimFault.NONE) }
        val g = runAndAwait(stack.service, LastResultRequest())
        val gOutcome = g.outcome
        assertTrue(gOutcome is CommandOutcome.Answered, "G ⇒ Answered: $gOutcome")
        gOutcome as CommandOutcome.Answered
        assertTrue(gOutcome.response is TransactionResponse, "G restituisce un esito di transazione")

        // 4. Esiti precedenti per la valutazione di G (GAssessment, WP6): nessun tentativo prima di questo.
        val earlier = stack.journal.knownResultsBefore(attemptId)
        assertTrue(earlier.isEmpty(), "nessun esito precedente: $earlier")

        // 5. L'operatore chiude il tentativo e i pagamenti si sbloccano.
        runBlocking { stack.journal.resolve(attemptId, ResolutionKind.CONFIRMED_BY_G, "G controllato dall'operatore", gOutcome) }
        assertTrue(stack.journal.unresolved.value.isEmpty(), "nessun tentativo da risolvere")
        val resolved = stack.journal.entries.value.first { it.attemptId == attemptId }
        assertEquals(gOutcome.response.raw.text, resolved.resolutionRecord?.gRawResult, "esito di G registrato")

        val next = runAndAwait(stack.service, PaymentRequest(amountCents = 10))
        assertTrue(next.outcome is CommandOutcome.Answered, "P dopo la risoluzione ⇒ Answered: ${next.outcome}")
    }

    /** Il blocco sopravvive al riavvio dell'app: il journal riletto da disco tiene il tentativo UNKNOWN. */
    @Test
    fun `dopo un riavvio recoverOnStartup mantiene il blocco`() {
        val (_, port) = simulator(SimFault.SILENT)
        val settings = settingsFor(port)
        val first = stack(settings)
        val outcome = runAndAwait(first.service, PaymentRequest(amountCents = 10)).outcome
        assertTrue(outcome is CommandOutcome.Unknown, "P ⇒ Unknown: $outcome")
        first.service.close()
        first.journal.close()

        val restarted = stack(settings)

        val unresolved = restarted.journal.unresolved.value
        assertEquals(1, unresolved.size, "tentativo ancora da risolvere: $unresolved")
        assertTrue(PaymentBlockPolicy.isBlocked(CommandCode.PAYMENT, unresolved), "pagamenti bloccati dopo il riavvio")
        assertTrue(restarted.service.run(PaymentRequest(amountCents = 10), RunOrigin.CONSOLE) is RunStart.Blocked, "P rifiutato")
    }
}
