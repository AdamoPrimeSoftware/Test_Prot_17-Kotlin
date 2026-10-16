package it.prime.protocollo17.presentation.screen.journal.viewModel

import it.prime.protocollo17.domain.model.journal.JournalEntry
import it.prime.protocollo17.domain.model.journal.JournalPhase
import it.prime.protocollo17.domain.model.journal.ResolutionKind
import it.prime.protocollo17.domain.repository.JournalRepository
import it.prime.protocollo17.ecr17.model.CommandCode
import it.prime.protocollo17.ecr17.model.LastResultRequest
import it.prime.protocollo17.presentation.FakeCommandRunner
import it.prime.protocollo17.presentation.UiFixtures
import it.prime.protocollo17.presentation.popStandaloneScreenModels
import it.prime.protocollo17.presentation.state.RunOrigin
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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Journal (§6.13): l'operatore deve trovare i tentativi da risolvere (anche quelli "da verificare" e quelli con il
 * CREATED illeggibile) e chiuderli con G o con una nota, senza che G parta mai da solo.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JournalViewModelTest {

    private val runner = FakeCommandRunner()
    private val entries = MutableStateFlow<List<JournalEntry>>(emptyList())
    private val unresolvedFlow = MutableStateFlow<List<JournalEntry>>(emptyList())
    private lateinit var journal: JournalRepository

    private val unknownPayment = UiFixtures.entry(attemptId = "a-unknown", createdAt = 3_000)
    private val review = UiFixtures.entry(attemptId = "a-review", phase = JournalPhase.RESULT, needsReview = true, createdAt = 2_000)
    private val approved = UiFixtures.entry(attemptId = "a-ok", phase = JournalPhase.RESULT, createdAt = 1_000)
    private val notSent = UiFixtures.entry(attemptId = "a-notsent", phase = JournalPhase.NOT_SENT, createdAt = 500)
    private val incomplete = UiFixtures.entry(attemptId = "a-incomplete", command = null, createdAt = 4_000)

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        journal = mockk(relaxed = true) {
            every { this@mockk.entries } returns this@JournalViewModelTest.entries
            every { unresolved } returns unresolvedFlow
            every { knownResultsBefore(any()) } returns emptyList()
            every { journalFile() } returns File("journal/journal.jsonl")
            coEvery { resolve(any(), any(), any(), any()) } just runs
        }
        val all = listOf(incomplete, unknownPayment, review, approved, notSent)
        entries.value = all
        unresolvedFlow.value = all.filter { it.isUnresolved }
    }

    @AfterEach
    fun tearDown() {
        popStandaloneScreenModels()
        Dispatchers.resetMain()
    }

    private fun vm() = JournalViewModel(journal = journal, runner = runner)

    @Test
    fun `selezione e dettaglio del tentativo`() {
        val vm = vm()
        assertEquals(5, vm.uiState.value.rows.size)
        assertEquals(3, vm.uiState.value.unresolvedCount)
        vm.onAction(JournalAction.Select("a-unknown"))
        val detail = vm.uiState.value.detail!!
        assertEquals("Pagamento 0,10 €", detail.title)
        assertTrue(detail.isUnresolved && detail.blocksPayments)
        assertTrue(detail.json.contains("\"attemptId\":\"a-unknown\""), detail.json)
        assertTrue(detail.summary.any { it.label == "Motivo" && it.value.contains("nessun esito entro 120 s") }, detail.summary.toString())
        assertEquals(3, detail.timeline.size)
    }

    /** Il filtro "da risolvere" comprende anche gli esiti da verificare (possibile doppio addebito). */
    @Test
    fun `filtro UNRESOLVED con i da verificare`() {
        val vm = vm()
        vm.onAction(JournalAction.SetFilter(JournalFilter.UNRESOLVED))
        assertEquals(setOf("a-unknown", "a-review", "a-incomplete"), vm.uiState.value.rows.map { it.attemptId }.toSet())
        assertEquals("Da verificare", vm.uiState.value.rows.first { it.attemptId == "a-review" }.phaseLabel)
    }

    /** Un CREATED illeggibile non fa sparire il tentativo: resta, bloccante, con un titolo esplicito (R19). */
    @Test
    fun `tentativo con dati incompleti`() {
        val vm = vm()
        val row = vm.uiState.value.rows.first { it.attemptId == "a-incomplete" }
        assertEquals("Tentativo con dati incompleti", row.title)
        assertNull(row.letter)
        vm.onAction(JournalAction.Select("a-incomplete"))
        assertEquals("Tentativo con dati incompleti", vm.uiState.value.detail?.title)
        assertTrue(vm.uiState.value.detail!!.blocksPayments)
    }

    /** G dal Journal passa dal runner con origine JOURNAL; al termine si apre il dialog per il tentativo scelto. */
    @Test
    fun `RunLastResult esegue G dal Journal e apre il dialog per il tentativo selezionato`() {
        // Il tentativo scelto è l'unico (quindi il più recente) da risolvere: G si lega a lui.
        unresolvedFlow.value = listOf(review)
        val vm = vm()
        vm.onAction(JournalAction.Select("a-review"))
        vm.onAction(JournalAction.RunLastResult)
        assertEquals(LastResultRequest() to RunOrigin.JOURNAL, runner.runs.single())
        assertFalse(vm.uiState.value.canRunG, "G non si rilancia mentre un comando è in corso")
        val g = UiFixtures.answered(CommandCode.LAST_RESULT, UiFixtures.payment())
        runner.finish(g)
        val dialog = vm.uiState.value.dialog as JournalDialog.Resolve
        assertEquals("a-review", dialog.model.attemptId)
        assertTrue(dialog.model.reviewOnly)
        assertNotNull(dialog.model.assessment)

        vm.onAction(JournalAction.Resolve("a-review", ResolutionKind.REVIEWED, "Totali controllati"))
        coVerify { journal.resolve("a-review", ResolutionKind.REVIEWED, "Totali controllati", g) }
        assertNull(vm.uiState.value.dialog)
    }

    /**
     * G riporta solo l'ultima transazione del terminale (§6.8): con un tentativo più recente da risolvere, il G
     * lanciato su uno più vecchio NON si lega a quello selezionato. Il dialog si apre per il selezionato senza G
     * (chiusura a mano) e spiega a chi appartiene l'esito; nessun "L'esito G appartiene al tentativo".
     */
    @Test
    fun `G lanciato su un tentativo piu' vecchio non si lega a lui`() {
        unresolvedFlow.value = listOf(unknownPayment, review)
        val vm = vm()
        vm.onAction(JournalAction.Select("a-review"))
        vm.onAction(JournalAction.RunLastResult)
        runner.finish(UiFixtures.answered(CommandCode.LAST_RESULT, UiFixtures.payment()))
        val model = (vm.uiState.value.dialog as JournalDialog.Resolve).model
        assertEquals("a-review", model.attemptId)
        assertNull(model.gResult, "G non va mostrato come esito di un tentativo più vecchio")
        assertNull(model.assessment)
        assertTrue(model.notice!!.contains("più recente"), model.notice)
        assertEquals(1, model.otherUnresolved)
        verify(exactly = 0) { journal.knownResultsBefore("a-review") }

        vm.onAction(JournalAction.Resolve("a-review", ResolutionKind.REVIEWED, "Totali controllati"))
        coVerify { journal.resolve("a-review", ResolutionKind.REVIEWED, "Totali controllati", null) }
    }

    /** Senza selezione (o con il più recente selezionato) G si lega al più recente non risolto, come nella Console. */
    @Test
    fun `G senza selezione si lega al piu' recente non risolto`() {
        unresolvedFlow.value = listOf(unknownPayment, review)
        val vm = vm()
        vm.onAction(JournalAction.RunLastResult)
        val g = UiFixtures.answered(CommandCode.LAST_RESULT, UiFixtures.payment())
        runner.finish(g)
        val model = (vm.uiState.value.dialog as JournalDialog.Resolve).model
        assertEquals("a-unknown", model.attemptId)
        assertNotNull(model.gResult)
        assertNotNull(model.assessment)
        assertNull(model.notice)
        vm.onAction(JournalAction.Resolve("a-unknown", ResolutionKind.CONFIRMED_BY_G, "Verificato con G"))
        coVerify { journal.resolve("a-unknown", ResolutionKind.CONFIRMED_BY_G, "Verificato con G", g) }
    }

    /** Doppio clic: una sola chiusura nel journal mentre la prima è in scrittura. */
    @Test
    fun `Resolve ignorato mentre una chiusura e' in corso`() {
        val gate = CompletableDeferred<Unit>()
        coEvery { journal.resolve(any(), any(), any(), any()) } coAnswers { gate.await() }
        val vm = vm()
        vm.onAction(JournalAction.OpenResolve("a-unknown"))
        vm.onAction(JournalAction.Resolve("a-unknown", ResolutionKind.DISMISSED, "scontrino verificato"))
        assertTrue((vm.uiState.value.dialog as JournalDialog.Resolve).model.resolving)
        vm.onAction(JournalAction.Resolve("a-unknown", ResolutionKind.DISMISSED, "scontrino verificato"))
        coVerify(exactly = 1) { journal.resolve(any(), any(), any(), any()) }
        gate.complete(Unit)
        assertNull(vm.uiState.value.dialog)
    }

    @Test
    fun `Resolve senza G con nota`() {
        val vm = vm()
        vm.onAction(JournalAction.OpenResolve("a-unknown"))
        assertNull((vm.uiState.value.dialog as JournalDialog.Resolve).model.gResult)
        vm.onAction(JournalAction.Resolve("a-unknown", ResolutionKind.DISMISSED, "scontrino verificato"))
        coVerify { journal.resolve("a-unknown", ResolutionKind.DISMISSED, "scontrino verificato", null) }
    }
}
