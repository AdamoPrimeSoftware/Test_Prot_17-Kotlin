package it.prime.protocollo17.presentation.screen.console.viewModel

import app.cash.turbine.test
import it.prime.protocollo17.domain.model.command.ConfigIssue
import it.prime.protocollo17.domain.model.command.ConfigResolution
import it.prime.protocollo17.domain.model.command.FieldKey
import it.prime.protocollo17.domain.model.journal.JournalEntry
import it.prime.protocollo17.domain.model.journal.ResolutionKind
import it.prime.protocollo17.domain.model.settings.AppSettings
import it.prime.protocollo17.domain.model.settings.UiSettings
import it.prime.protocollo17.domain.model.simulator.SimulatorFailure
import it.prime.protocollo17.domain.model.simulator.SimulatorStatus
import it.prime.protocollo17.domain.repository.JournalRepository
import it.prime.protocollo17.domain.repository.PosTerminalRepository
import it.prime.protocollo17.domain.repository.SimulatorController
import it.prime.protocollo17.ecr17.error.Ecr17Failure
import it.prime.protocollo17.ecr17.model.CommandCode
import it.prime.protocollo17.ecr17.model.CommandOutcome
import it.prime.protocollo17.ecr17.model.ConnectionTestResult
import it.prime.protocollo17.ecr17.model.ExchangePhase
import it.prime.protocollo17.ecr17.model.LastResultRequest
import it.prime.protocollo17.ecr17.model.LinkState
import it.prime.protocollo17.ecr17.model.PaymentRequest
import it.prime.protocollo17.ecr17.model.StatusRequest
import it.prime.protocollo17.ecr17.model.TerminalIds
import it.prime.protocollo17.ecr17.profile.Ecr17Config
import it.prime.protocollo17.ecr17.profile.ProtocolOptions
import it.prime.protocollo17.ecr17.profile.TerminalProfileId
import it.prime.protocollo17.ecr17.profile.TerminalProfiles
import it.prime.protocollo17.presentation.FakeCommandRunner
import it.prime.protocollo17.presentation.FakeSettings
import it.prime.protocollo17.presentation.StubFallbacks
import it.prime.protocollo17.presentation.UiFixtures
import it.prime.protocollo17.presentation.popStandaloneScreenModels
import it.prime.protocollo17.presentation.state.RunnerState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Console (§6.5, §7.8): blocco dei pagamenti, conferme, annullamento e G sono le difese che l'operatore vede.
 * Il runner è un fake con gate (un comando resta in corso finché il test non lo conclude), gli altri
 * collaboratori sono mock; gli stub degli altri WP passano da StubFallbacks.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConsoleViewModelTest {

    private lateinit var runner: FakeCommandRunner
    private lateinit var settings: FakeSettings
    private val link = MutableStateFlow<LinkState>(LinkState.Idle)
    private val unresolvedFlow = MutableStateFlow<List<JournalEntry>>(emptyList())
    private val simStatus = MutableStateFlow<SimulatorStatus>(SimulatorStatus.Stopped)
    private val simTarget = MutableStateFlow(false)
    private lateinit var pos: PosTerminalRepository
    private lateinit var journal: JournalRepository
    private lateinit var simulator: SimulatorController

    private fun resolution(issues: List<ConfigIssue> = emptyList()) = ConfigResolution(
        profile = TerminalProfiles.INGENICO,
        config = Ecr17Config(TerminalProfileId.INGENICO, UiFixtures.ENDPOINT, TerminalIds(), ProtocolOptions()),
        issues = issues,
        warnings = emptyList(),
    )

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        StubFallbacks.install()
        runner = FakeCommandRunner()
        settings = FakeSettings()
        pos = mockk(relaxed = true) {
            every { linkState } returns link
            every { effectiveConfig() } returns resolution()
        }
        journal = mockk(relaxed = true) {
            every { unresolved } returns unresolvedFlow
            every { knownResultsBefore(any()) } returns emptyList()
            coEvery { resolve(any(), any(), any(), any()) } just runs
        }
        simulator = mockk(relaxed = true) {
            every { status } returns simStatus
            every { isTarget } returns simTarget
            coEvery { start() } returns SimulatorStatus.Running(15577)
        }
    }

    @AfterEach
    fun tearDown() {
        popStandaloneScreenModels()
        StubFallbacks.uninstall()
        Dispatchers.resetMain()
    }

    private fun vm() = ConsoleViewModel(runner = runner, pos = pos, journal = journal, settings = settings.repository, simulator = simulator)

    // ─── Form e bozze ─────────────────────────────────────────────────────────────

    /** L'ultimo comando scelto si ritrova al riavvio (§6.2). */
    @Test
    fun `Select salva lastCommand`() {
        val vm = vm()
        vm.onAction(ConsoleAction.Select(CommandCode.TOTALS))
        assertEquals(CommandCode.TOTALS, vm.uiState.value.selected)
        assertEquals("T", settings.value.ui.lastCommand)
    }

    /** Lo stato si aggiorna subito (nessun carattere perso nel TextField) e la bozza è salvata. */
    @Test
    fun `UpdateField aggiorna lo stato in modo sincrono e salva i drafts`() {
        val vm = vm()
        vm.onAction(ConsoleAction.UpdateField(CommandCode.REVERSAL, FieldKey.STAN, "000042"))
        assertEquals("000042", vm.uiState.value.drafts[CommandCode.REVERSAL]?.get(FieldKey.STAN))
        assertEquals("000042", settings.value.drafts["S"]?.get("STAN"))
    }

    /** Importo condiviso tra P, X e p come nell'app WPF; integrazione e chiusura hanno importi propri. */
    @Test
    fun `AMOUNT di P copiato in X e p ma non in i`() {
        val vm = vm()
        vm.onAction(ConsoleAction.UpdateField(CommandCode.PAYMENT, FieldKey.AMOUNT, "12,34"))
        val drafts = vm.uiState.value.drafts
        assertEquals("12,34", drafts[CommandCode.PAYMENT_EXTENDED]?.get(FieldKey.AMOUNT))
        assertEquals("12,34", drafts[CommandCode.PRE_AUTH]?.get(FieldKey.AMOUNT))
        assertEquals("0,01", drafts[CommandCode.INCREMENTAL_AUTH]?.get(FieldKey.AMOUNT))
        assertEquals("12,34", settings.value.drafts["p"]?.get("AMOUNT"))
    }

    /** Rileggere le bozze dal flow a ogni salvataggio farebbe saltare il cursore: si leggono solo all'avvio. */
    @Test
    fun `drafts letti da settings solo all'init`() {
        settings.state.value = AppSettings(drafts = mapOf("P" to mapOf("AMOUNT" to "5,00")), ui = UiSettings(lastCommand = "G"))
        val vm = vm()
        assertEquals("5,00", vm.uiState.value.drafts[CommandCode.PAYMENT]?.get(FieldKey.AMOUNT))
        assertEquals(CommandCode.LAST_RESULT, vm.uiState.value.selected)
        settings.state.value = settings.value.copy(drafts = mapOf("P" to mapOf("AMOUNT" to "9,99")))
        assertEquals("5,00", vm.uiState.value.drafts[CommandCode.PAYMENT]?.get(FieldKey.AMOUNT))
    }

    /** Il valore va nel form di destinazione, che diventa il comando selezionato. */
    @Test
    fun `CopyToForm scrive il draft della destinazione e la seleziona`() = runTest {
        val vm = vm()
        vm.events.test {
            vm.onAction(ConsoleAction.CopyToForm(CommandCode.INCREMENTAL_AUTH, FieldKey.PRE_AUTH_CODE, "123456789"))
            assertEquals(ConsoleEvent.ShowInfo("Codice copiato in Integrazione (i)"), awaitItem())
        }
        assertEquals(CommandCode.INCREMENTAL_AUTH, vm.uiState.value.selected)
        assertEquals("123456789", vm.uiState.value.drafts[CommandCode.INCREMENTAL_AUTH]?.get(FieldKey.PRE_AUTH_CODE))
    }

    // ─── Esecuzione ───────────────────────────────────────────────────────────────

    @Test
    fun `RunSelected con importo invalido mostra gli errori e non esegue`() = runTest {
        val vm = vm()
        vm.onAction(ConsoleAction.UpdateField(CommandCode.PAYMENT, FieldKey.AMOUNT, "abc"))
        vm.events.test {
            vm.onAction(ConsoleAction.RunSelected)
            assertEquals(ConsoleEvent.ShowError("Correggi i campi evidenziati"), awaitItem())
        }
        assertTrue(FieldKey.AMOUNT in vm.uiState.value.fieldErrors)
        assertTrue(runner.runs.isEmpty(), "nessun comando deve partire con un importo non valido")
    }

    @Test
    fun `RunSelected valido esegue il pagamento dalla Console`() {
        val vm = vm()
        vm.onAction(ConsoleAction.UpdateField(CommandCode.PAYMENT, FieldKey.AMOUNT, "0,10"))
        vm.onAction(ConsoleAction.RunSelected)
        assertEquals(PaymentRequest(amountCents = 10), runner.runs.single().first)
        assertTrue(vm.uiState.value.busy)
        assertFalse(vm.uiState.value.barActionsEnabled, "barra disabilitata durante un comando")
        assertFalse(vm.uiState.value.canRunSelected)
    }

    /** La chiusura contabile non è ripetibile: conferma prima, e solo ConfirmRun la esegue. */
    @Test
    fun `chiusura contabile chiede conferma`() {
        val vm = vm()
        vm.onAction(ConsoleAction.Select(CommandCode.CLOSE_SESSION))
        vm.onAction(ConsoleAction.RunSelected)
        val dialog = vm.uiState.value.dialog
        assertTrue(dialog is ConsoleDialog.ConfirmRun, "dialog: $dialog")
        assertTrue(runner.runs.isEmpty())
        assertFalse(vm.uiState.value.shortcutsEnabled, "Ctrl+Invio ignorato con il dialog aperto")
        vm.onAction(ConsoleAction.ConfirmRun)
        assertEquals(CommandCode.CLOSE_SESSION, runner.runs.single().first.command)
        assertNull(vm.uiState.value.dialog)
    }

    // ─── Blocco dei pagamenti (U7, M6) ────────────────────────────────────────────

    /** Un pagamento sconosciuto blocca i comandi journaled; Stato resta sempre disponibile. */
    @Test
    fun `con UNKNOWN bloccante P non si esegue e s si`() {
        unresolvedFlow.value = listOf(UiFixtures.entry(command = CommandCode.PAYMENT))
        val vm = vm()
        assertFalse(vm.uiState.value.canRunSelected, "P bloccato")
        assertTrue(vm.uiState.value.paymentsBlocked)
        vm.onAction(ConsoleAction.RunSelected)
        assertTrue(runner.runs.isEmpty())
        assertTrue(vm.uiState.value.dialog is ConsoleDialog.BlockedInfo)
        vm.onAction(ConsoleAction.DismissDialog)
        vm.onAction(ConsoleAction.Select(CommandCode.STATUS))
        assertTrue(vm.uiState.value.canRunSelected, "s mai bloccato")
    }

    /** Un H sconosciuto blocca solo la sua famiglia di risposta: un 'E' tardivo potrebbe sembrare un pagamento. */
    @Test
    fun `con UNKNOWN di H sono bloccati P X S H`() {
        unresolvedFlow.value = listOf(UiFixtures.entry(command = CommandCode.CARD_VERIFICATION, amountCents = null))
        val vm = vm()
        assertEquals(
            setOf(CommandCode.PAYMENT, CommandCode.PAYMENT_EXTENDED, CommandCode.REVERSAL, CommandCode.CARD_VERIFICATION),
            vm.uiState.value.blockedCommands,
        )
        assertFalse(vm.uiState.value.paymentsBlocked, "H non muove denaro")
    }

    // ─── Annullamento ─────────────────────────────────────────────────────────────

    /** Dopo l'invio di un pagamento, annullare lascia l'esito sconosciuto: si conferma con il testo del blocco. */
    @Test
    fun `RequestCancel dopo SENDING di P chiede conferma col blocco dei pagamenti`() {
        val vm = vm()
        vm.onAction(ConsoleAction.UpdateField(CommandCode.PAYMENT, FieldKey.AMOUNT, "0,10"))
        vm.onAction(ConsoleAction.RunSelected)
        runner.setPhase(ExchangePhase.SENDING)
        assertTrue(vm.uiState.value.escapeCancels)
        vm.onAction(ConsoleAction.RequestCancel)
        val dialog = vm.uiState.value.dialog as ConsoleDialog.ConfirmCancelAfterSend
        assertTrue(dialog.message.contains("pagamenti saranno bloccati"), dialog.message)
        assertEquals(0, runner.cancelCalls, "nessun annullamento senza conferma")
        assertFalse(vm.uiState.value.escapeCancels, "con il dialog aperto Esc chiude solo il dialog")
        assertFalse(vm.uiState.value.shortcutsEnabled)
        vm.onAction(ConsoleAction.ConfirmCancel)
        assertEquals(1, runner.cancelCalls)
        assertNull(vm.uiState.value.dialog)
    }

    /** H non muove denaro: il testo non parla del blocco dei pagamenti. */
    @Test
    fun `RequestCancel dopo SENDING di H non parla dei pagamenti`() {
        val vm = vm()
        vm.onAction(ConsoleAction.Select(CommandCode.CARD_VERIFICATION))
        vm.onAction(ConsoleAction.RunSelected)
        runner.setPhase(ExchangePhase.AWAITING_ACK)
        vm.onAction(ConsoleAction.RequestCancel)
        val dialog = vm.uiState.value.dialog as ConsoleDialog.ConfirmCancelAfterSend
        assertFalse(dialog.message.contains("pagamenti"), dialog.message)
        assertTrue(dialog.message.contains("Verifica carta"), dialog.message)
    }

    /** Se il pagamento finisce mentre l'operatore decide, la domanda "interrompere?" sparisce: non c'è più nulla da annullare. */
    @Test
    fun `la conferma di annullamento si chiude se il comando finisce`() {
        val vm = vm()
        vm.onAction(ConsoleAction.RunSelected)
        runner.setPhase(ExchangePhase.AWAITING_RESULT)
        vm.onAction(ConsoleAction.RequestCancel)
        assertTrue(vm.uiState.value.dialog is ConsoleDialog.ConfirmCancelAfterSend)
        runner.finish(UiFixtures.answered(CommandCode.PAYMENT, UiFixtures.payment()))
        assertNull(vm.uiState.value.dialog)
        assertEquals(0, runner.cancelCalls)
    }

    /** Prima dell'invio annullare è innocuo: nessuna conferma. */
    @Test
    fun `RequestCancel prima dell'invio annulla subito`() {
        val vm = vm()
        vm.onAction(ConsoleAction.RunSelected)
        assertTrue(vm.uiState.value.canCancel)
        vm.onAction(ConsoleAction.RequestCancel)
        assertEquals(1, runner.cancelCalls)
        assertNull(vm.uiState.value.dialog)
        assertFalse(vm.uiState.value.canCancel, "dopo la richiesta il pulsante diventa 'Annullamento…'")
    }

    /**
     * La copia dello stato nel VM arriva con un dispatch di ritardo: se Esc arriva proprio mentre il pagamento passa
     * a SENDING, la decisione si prende sullo stato vero del runner e chiede conferma invece di interrompere.
     */
    @Test
    fun `RequestCancel decide sullo stato corrente del runner, non sulla copia in ritardo`() {
        val main = StandardTestDispatcher()
        Dispatchers.setMain(main)
        val vm = vm()
        main.scheduler.runCurrent()
        vm.onAction(ConsoleAction.RunSelected)
        main.scheduler.runCurrent()
        runner.setPhase(ExchangePhase.SENDING)
        assertFalse(vm.uiState.value.running!!.requestSent, "precondizione: la copia del VM è ancora prima dell'invio")
        vm.onAction(ConsoleAction.RequestCancel)
        assertEquals(0, runner.cancelCalls, "un pagamento già inviato non si interrompe senza conferma")
        assertTrue(vm.uiState.value.dialog is ConsoleDialog.ConfirmCancelAfterSend)
    }

    /** Il servizio passa a SENDING tra la lettura e l'annullamento: l'operatore sa subito che l'esito è sconosciuto. */
    @Test
    fun `annullamento finito dopo l'invio avvisa che l'esito e' sconosciuto`() = runTest {
        val vm = vm()
        vm.onAction(ConsoleAction.RunSelected)
        runner.phaseOnCancel = ExchangePhase.SENDING
        vm.events.test {
            vm.onAction(ConsoleAction.RequestCancel)
            val event = awaitItem() as ConsoleEvent.ShowError
            assertTrue(event.message.contains("SCONOSCIUTO"), event.message)
        }
        assertEquals(1, runner.cancelCalls)
    }

    /** La conferma vale per il comando del dialog: se nel frattempo ne è partito un altro, non si annulla nulla. */
    @Test
    fun `ConfirmCancel non annulla un comando diverso da quello del dialog`() = runTest {
        val vm = vm()
        vm.onAction(ConsoleAction.RunSelected)
        runner.setPhase(ExchangePhase.AWAITING_RESULT)
        vm.onAction(ConsoleAction.RequestCancel)
        assertTrue(vm.uiState.value.dialog is ConsoleDialog.ConfirmCancelAfterSend)
        // Il pagamento finisce e parte un H prima che il VM chiuda il dialog (stato saltato direttamente).
        runner.setState(RunnerState(running = UiFixtures.running(CommandCode.CARD_VERIFICATION, ExchangePhase.AWAITING_RESULT)))
        vm.events.test {
            vm.onAction(ConsoleAction.ConfirmCancel)
            assertEquals(ConsoleEvent.ShowInfo("Il comando è già terminato: nulla da annullare"), awaitItem())
        }
        assertEquals(0, runner.cancelCalls)
        assertNull(vm.uiState.value.dialog)
    }

    /** ConfirmCancel senza il dialog aperto (azione stantia) non annulla nulla. */
    @Test
    fun `ConfirmCancel senza dialog non annulla`() {
        val vm = vm()
        vm.onAction(ConsoleAction.RunSelected)
        runner.setPhase(ExchangePhase.SENDING)
        vm.onAction(ConsoleAction.ConfirmCancel)
        assertEquals(0, runner.cancelCalls)
    }

    // ─── Stato (F5) ───────────────────────────────────────────────────────────────

    @Test
    fun `PingStatus mostra lo stato all'arrivo dell'esito ed e' ignorato se occupato`() = runTest {
        val vm = vm()
        vm.events.test {
            vm.onAction(ConsoleAction.PingStatus)
            assertEquals(StatusRequest, runner.runs.single().first)
            assertEquals(CommandCode.PAYMENT, vm.uiState.value.selected, "F5 non cambia selezione")
            runner.finish(UiFixtures.answered(CommandCode.STATUS, UiFixtures.status(2)), finishedAt = 1_140)
            assertEquals(ConsoleEvent.ShowInfo("Terminale: operativo (2) in 140 ms"), awaitItem())
        }
        vm.onAction(ConsoleAction.RunSelected)
        vm.onAction(ConsoleAction.PingStatus)
        assertEquals(2, runner.runs.size, "F5 durante un comando non parte")
    }

    // ─── G e risoluzione ──────────────────────────────────────────────────────────

    /** G dalla Console con un tentativo sconosciuto ⇒ dialog di risoluzione con il suggerimento di GAssessment. */
    @Test
    fun `G concluso con non risolti apre il dialog con assessment`() {
        unresolvedFlow.value = listOf(UiFixtures.entry(attemptId = "attempt-1", command = CommandCode.PAYMENT))
        val vm = vm()
        assertTrue(vm.uiState.value.canRecoverWithG)
        vm.onAction(ConsoleAction.RecoverWithG)
        assertEquals(LastResultRequest(), runner.runs.single().first)
        val g = UiFixtures.answered(CommandCode.LAST_RESULT, UiFixtures.payment())
        runner.finish(g)
        val dialog = vm.uiState.value.dialog as ConsoleDialog.Resolve
        assertEquals("attempt-1", dialog.model.attemptId)
        assertNotNull(dialog.model.gResult)
        assertNotNull(dialog.model.assessment)
        verify { journal.knownResultsBefore("attempt-1") }

        vm.onAction(ConsoleAction.Resolve("attempt-1", ResolutionKind.CONFIRMED_BY_G, "Verificato con G alle 09:40"))
        coVerify { journal.resolve("attempt-1", ResolutionKind.CONFIRMED_BY_G, "Verificato con G alle 09:40", g) }
        assertNull(vm.uiState.value.dialog)
    }

    /** G senza risposta: nessun dialog, l'esito resta nel pannello. */
    @Test
    fun `G senza risposta non apre il dialog`() {
        unresolvedFlow.value = listOf(UiFixtures.entry())
        val vm = vm()
        vm.onAction(ConsoleAction.RecoverWithG)
        runner.finish(
            CommandOutcome.Failed(
                CommandCode.LAST_RESULT, Ecr17Failure.ResponseTimeout(120_000),
                ExchangePhase.AWAITING_RESULT, UiFixtures.trace(CommandCode.LAST_RESULT),
            ),
        )
        assertNull(vm.uiState.value.dialog)
        assertNotNull(vm.uiState.value.results[CommandCode.LAST_RESULT])
    }

    /** Chiusura senza G: nota obbligatoria, nessun esito G collegato. */
    @Test
    fun `OpenResolve e Resolve senza G`() = runTest {
        unresolvedFlow.value = listOf(UiFixtures.entry(attemptId = "attempt-9"))
        val vm = vm()
        vm.onAction(ConsoleAction.OpenResolve("attempt-9"))
        val model = (vm.uiState.value.dialog as ConsoleDialog.Resolve).model
        assertNull(model.gResult)
        vm.events.test {
            vm.onAction(ConsoleAction.Resolve("attempt-9", ResolutionKind.DISMISSED, "  "))
            assertTrue(awaitItem() is ConsoleEvent.ShowError, "nota vuota rifiutata")
        }
        coVerify(exactly = 0) { journal.resolve(any(), any(), any(), any()) }
        vm.onAction(ConsoleAction.Resolve("attempt-9", ResolutionKind.DISMISSED, "scontrino controllato"))
        coVerify { journal.resolve("attempt-9", ResolutionKind.DISMISSED, "scontrino controllato", null) }
    }

    /** Doppio clic su una decisione: una sola scrittura nel journal, decisioni disabilitate mentre scrive. */
    @Test
    fun `Resolve ignorato mentre una chiusura e' in corso`() = runTest {
        unresolvedFlow.value = listOf(UiFixtures.entry(attemptId = "attempt-9"))
        val gate = CompletableDeferred<Unit>()
        coEvery { journal.resolve(any(), any(), any(), any()) } coAnswers { gate.await() }
        val vm = vm()
        vm.onAction(ConsoleAction.OpenResolve("attempt-9"))
        vm.onAction(ConsoleAction.Resolve("attempt-9", ResolutionKind.DISMISSED, "scontrino controllato"))
        assertTrue((vm.uiState.value.dialog as ConsoleDialog.Resolve).model.resolving, "decisioni disabilitate")
        vm.onAction(ConsoleAction.Resolve("attempt-9", ResolutionKind.NOT_EXECUTED, "secondo clic"))
        coVerify(exactly = 1) { journal.resolve(any(), any(), any(), any()) }
        vm.events.test {
            gate.complete(Unit)
            assertTrue(awaitItem() is ConsoleEvent.ShowSuccess)
        }
        assertNull(vm.uiState.value.dialog)
    }

    /** Chiusura fallita: il dialog resta aperto e le decisioni tornano cliccabili per riprovare. */
    @Test
    fun `Resolve fallito riabilita le decisioni`() = runTest {
        unresolvedFlow.value = listOf(UiFixtures.entry(attemptId = "attempt-9"))
        coEvery { journal.resolve(any(), any(), any(), any()) } throws IllegalStateException("disco pieno")
        val vm = vm()
        vm.onAction(ConsoleAction.OpenResolve("attempt-9"))
        vm.events.test {
            vm.onAction(ConsoleAction.Resolve("attempt-9", ResolutionKind.DISMISSED, "scontrino controllato"))
            assertTrue((awaitItem() as ConsoleEvent.ShowError).message.contains("resta bloccante"))
        }
        val model = (vm.uiState.value.dialog as ConsoleDialog.Resolve).model
        assertFalse(model.resolving)
    }

    // ─── Cronologia ───────────────────────────────────────────────────────────────

    @Test
    fun `cronologia mappata, voce visualizzata e svuotata`() {
        val first = UiFixtures.finished(UiFixtures.answered(CommandCode.STATUS, UiFixtures.status()), id = 1)
        val second = UiFixtures.finished(UiFixtures.answered(CommandCode.PAYMENT, UiFixtures.payment()), id = 2)
        runner.setState(RunnerState(history = listOf(second, first), lastByCommand = mapOf(CommandCode.PAYMENT to second)))
        val vm = vm()
        assertEquals(listOf(2L, 1L), vm.uiState.value.history.map { it.id })
        assertTrue(vm.uiState.value.historyText.contains("APPROVATO"))
        assertEquals("APPROVATO", vm.uiState.value.results[CommandCode.PAYMENT]?.headline)
        vm.onAction(ConsoleAction.SetResultTab(ResultTab.HISTORY))
        vm.onAction(ConsoleAction.ShowHistoryItem(1))
        assertEquals(CommandCode.STATUS, vm.uiState.value.viewedHistory?.command)
        assertEquals(ResultTab.RESULT, vm.uiState.value.resultTab)
        vm.onAction(ConsoleAction.ShowHistoryItem(null))
        assertNull(vm.uiState.value.viewedHistory)
        // Un nuovo comando riporta in vista il proprio esito, non la voce di cronologia che si stava guardando.
        vm.onAction(ConsoleAction.ShowHistoryItem(1))
        vm.onAction(ConsoleAction.SetResultTab(ResultTab.HISTORY))
        vm.onAction(ConsoleAction.RunSelected)
        assertNull(vm.uiState.value.viewedHistory)
        assertEquals(ResultTab.RESULT, vm.uiState.value.resultTab)
        runner.finish(UiFixtures.answered(CommandCode.PAYMENT, UiFixtures.payment()))
        vm.onAction(ConsoleAction.ClearHistory)
        assertEquals(1, runner.clearHistoryCalls)
        assertTrue(vm.uiState.value.history.isEmpty())
        assertNotNull(vm.uiState.value.results[CommandCode.PAYMENT], "svuotare la cronologia non cancella l'ultimo esito per comando")
    }

    // ─── Intestazione e simulatore ────────────────────────────────────────────────

    @Test
    fun `intestazione dalla configurazione effettiva`() {
        every { pos.effectiveConfig() } returns resolution(issues = listOf(ConfigIssue("appLrcMode", "Valore 'sxt' non valido per LRC applicativo")))
        link.value = LinkState.Connected(UiFixtures.ENDPOINT, 1_000)
        val vm = vm()
        val connection = vm.uiState.value.connection
        assertEquals("192.168.1.131:5577", connection.endpoint)
        assertEquals("stx · ACK 06 03 7A", connection.lrcSummary)
        assertFalse(connection.configUsable)
        assertTrue(connection.configMessages.any { it.contains("sxt") })
        assertEquals(LinkTone.CONNECTED, connection.link.tone)
        assertFalse(vm.uiState.value.canRunSelected, "configurazione non utilizzabile")
    }

    @Test
    fun `test connessione con esito come evento`() = runTest {
        coEvery { pos.testConnection() } returns ConnectionTestResult(UiFixtures.ENDPOINT, ok = true, millis = 14, failure = null)
        val vm = vm()
        vm.events.test {
            vm.onAction(ConsoleAction.TestConnection)
            assertEquals(ConsoleEvent.ShowSuccess("Connessione riuscita in 14 ms"), awaitItem())
        }
        assertFalse(vm.uiState.value.connection.testing)
    }

    /**
     * Il test connessione tiene il lock del client: prima i comandi restavano attivi e un pagamento lanciato in quel
     * momento scriveva il tentativo nel journal e finiva NotSent(Busy). Ora sono disabilitati e non partono.
     */
    @Test
    fun `durante Test connessione i comandi sono disabilitati e non partono`() {
        val gate = CompletableDeferred<ConnectionTestResult>()
        coEvery { pos.testConnection() } coAnswers { gate.await() }
        val vm = vm()
        vm.onAction(ConsoleAction.UpdateField(CommandCode.PAYMENT, FieldKey.AMOUNT, "0,10"))
        assertTrue(vm.uiState.value.canRunSelected, "prima del test")

        vm.onAction(ConsoleAction.TestConnection)
        val during = vm.uiState.value
        assertTrue(during.connection.testing)
        assertTrue(during.linkBusy)
        assertFalse(during.canRunSelected, "P disabilitato durante il test")
        assertFalse(during.barActionsEnabled, "Stato e Connetti disabilitati durante il test")
        vm.onAction(ConsoleAction.RunSelected)
        vm.onAction(ConsoleAction.PingStatus)
        vm.onAction(ConsoleAction.TestConnection)
        assertTrue(runner.runs.isEmpty(), "nessun comando durante il test: finirebbe NotSent(Busy) dopo il journal")
        coVerify(exactly = 1) { pos.testConnection() }

        gate.complete(ConnectionTestResult(UiFixtures.ENDPOINT, ok = true, millis = 14, failure = null))
        assertFalse(vm.uiState.value.linkBusy)
        assertTrue(vm.uiState.value.canRunSelected, "di nuovo attivo a test concluso")
        vm.onAction(ConsoleAction.RunSelected)
        assertEquals(PaymentRequest(amountCents = 10), runner.runs.single().first)
    }

    /** Lo stesso durante Connetti/Disconnetti (PERSISTENT): anche G e Stato, che il journal non blocca mai. */
    @Test
    fun `durante Connetti G e i comandi sono disabilitati`() {
        val gate = CompletableDeferred<Ecr17Failure?>()
        coEvery { pos.connect() } coAnswers { gate.await() }
        unresolvedFlow.value = listOf(UiFixtures.entry(attemptId = "attempt-1", command = CommandCode.PAYMENT))
        val vm = vm()
        assertTrue(vm.uiState.value.canRecoverWithG, "prima di Connetti")

        vm.onAction(ConsoleAction.Connect)
        assertTrue(vm.uiState.value.connection.switching)
        assertFalse(vm.uiState.value.canRecoverWithG, "G disabilitato durante Connetti")
        vm.onAction(ConsoleAction.RecoverWithG)
        vm.onAction(ConsoleAction.Select(CommandCode.STATUS))
        assertFalse(vm.uiState.value.canRunSelected, "s disabilitato durante Connetti")
        vm.onAction(ConsoleAction.RunSelected)
        vm.onAction(ConsoleAction.Disconnect)
        vm.onAction(ConsoleAction.TestConnection)
        assertTrue(runner.runs.isEmpty(), "nessun comando durante Connetti")
        coVerify(exactly = 0) { pos.disconnect() }
        coVerify(exactly = 0) { pos.testConnection() }

        gate.complete(null)
        assertFalse(vm.uiState.value.connection.switching)
        assertTrue(vm.uiState.value.canRecoverWithG, "G di nuovo attivo")
        assertTrue(vm.uiState.value.canRunSelected, "s di nuovo attivo")
    }

    /** Un ConfirmRun arrivato durante il test (dialog aperto prima) non parte: avviso invece di NotSent(Busy). */
    @Test
    fun `ConfirmRun durante Test connessione non esegue`() = runTest {
        val gate = CompletableDeferred<ConnectionTestResult>()
        coEvery { pos.testConnection() } coAnswers { gate.await() }
        val vm = vm()
        vm.onAction(ConsoleAction.Select(CommandCode.CLOSE_SESSION))
        vm.onAction(ConsoleAction.RunSelected)
        assertTrue(vm.uiState.value.dialog is ConsoleDialog.ConfirmRun)
        vm.onAction(ConsoleAction.TestConnection)
        vm.events.test {
            vm.onAction(ConsoleAction.ConfirmRun)
            assertEquals(ConsoleEvent.ShowError("Operazione di collegamento in corso: attendi che finisca"), awaitItem())
        }
        assertTrue(runner.runs.isEmpty(), "la chiusura contabile non parte durante il test")
        gate.complete(ConnectionTestResult(UiFixtures.ENDPOINT, ok = true, millis = 14, failure = null))
    }

    /** Le azioni del menu Strumenti arrivano come ConsoleAction e chiamano il controller. */
    @Test
    fun `azioni del simulatore delegano al controller`() = runTest {
        every { simulator.useAsTarget() } returns false
        every { simulator.restoreTarget() } returns true
        val vm = vm()
        vm.onAction(ConsoleAction.StartSimulator)
        coVerify { simulator.start() }
        vm.onAction(ConsoleAction.StopSimulator)
        coVerify { simulator.stop() }
        vm.events.test {
            vm.onAction(ConsoleAction.UseSimulatorAsTarget)
            assertEquals(ConsoleEvent.ShowError("Avvia prima il simulatore"), awaitItem())
            vm.onAction(ConsoleAction.RestoreTerminal)
            assertTrue(awaitItem() is ConsoleEvent.ShowInfo)
        }
        verify { simulator.useAsTarget() }
        verify { simulator.restoreTarget() }
        simStatus.value = SimulatorStatus.Failed(SimulatorFailure.PortInUse(15577))
        assertEquals("Porta 15577 in uso: cambiala nel Simulatore", vm.uiState.value.simulator.label)
    }
}
