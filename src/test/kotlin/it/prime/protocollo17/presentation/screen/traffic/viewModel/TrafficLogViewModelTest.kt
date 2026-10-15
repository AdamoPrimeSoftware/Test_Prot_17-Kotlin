package it.prime.protocollo17.presentation.screen.traffic.viewModel

import it.prime.protocollo17.domain.model.traffic.TrafficEntry
import it.prime.protocollo17.domain.model.traffic.TrafficKind
import it.prime.protocollo17.domain.model.traffic.TrafficSeverity
import it.prime.protocollo17.domain.repository.TrafficRepository
import it.prime.protocollo17.presentation.awaitTrue
import it.prime.protocollo17.presentation.popStandaloneScreenModels
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.job
import kotlinx.coroutines.withTimeoutOrNull
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Log traffico (§6.12): il filtro gira su Dispatchers.Default (tempo reale), quindi si attende la condizione con
 * un tetto invece di avanzare il tempo virtuale. La pausa congela la vista ma non il registratore.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrafficLogViewModelTest {

    private val entries = MutableStateFlow<List<TrafficEntry>>(emptyList())
    private lateinit var traffic: TrafficRepository

    @Volatile
    private var exported: Pair<File, List<TrafficEntry>>? = null

    private fun entry(id: Long, kind: TrafficKind, summary: String, severity: TrafficSeverity = TrafficSeverity.NORMAL, hex: String? = null) =
        TrafficEntry(id = id, at = 1_000 + id, exchangeId = 1, kind = kind, command = 'P', hex = hex, ascii = null, summary = summary, severity = severity)

    private val tx = entry(1, TrafficKind.TX, "Richiesta 'P'", hex = "02 30 30 50 03 2F")
    private val rx = entry(2, TrafficKind.RX, "Esito 'E' atteso, LRC 21 ok (stx)", hex = "02 30 45 03 21")
    private val phase = entry(3, TrafficKind.PHASE, "fase SENDING invio 1", TrafficSeverity.INFO)
    private val warning = entry(4, TrafficKind.WARNING, "IMPLICIT_GO_AHEAD ACK non ricevuto", TrafficSeverity.WARNING)
    private val discarded = entry(5, TrafficKind.DISCARDED, "Byte scartati", TrafficSeverity.WARNING, hex = "FF 00")

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        traffic = mockk(relaxed = true) {
            every { this@mockk.entries } returns this@TrafficLogViewModelTest.entries
            every { currentFile } returns MutableStateFlow(File("2026_10_14-p17-traffic.txt"))
            every { directory } returns File("traffic")
            coEvery { export(any(), any()) } answers {
                exported = firstArg<File>() to secondArg()
                Result.success(secondArg<List<TrafficEntry>>().size)
            }
        }
        entries.value = listOf(tx, rx, phase, warning, discarded)
    }

    private val created = mutableListOf<TrafficLogViewModel>()

    private fun vm() = TrafficLogViewModel(traffic).also { created += it }

    /**
     * Il filtro gira su Dispatchers.Default: annullato lo scope, il produttore termina su un worker e riprende il
     * collector su Main. Se nel frattempo resetMain (o il setMain del test dopo) ha cambiato Main, quella ripresa
     * fallisce e kotlinx-coroutines-test consegna l'eccezione al primo runTest di un'altra classe. Si attende
     * quindi la fine dello scope PRIMA di togliere il Main di test.
     */
    @AfterEach
    fun tearDown() {
        val jobs = created.map { it.screenModelScope.coroutineContext.job }
        popStandaloneScreenModels()
        runBlocking { withTimeoutOrNull(2_000) { jobs.joinAll() } }
        Dispatchers.resetMain()
    }

    private suspend fun awaitVisible(vm: TrafficLogViewModel, expected: List<TrafficEntry>) =
        awaitTrue(message = { "visibili ${expected.map { it.id }}, ora ${vm.uiState.value.visible.map { it.id }}" }) {
            vm.uiState.value.visible == expected
        }

    @Test
    fun `filtri per frame, eventi, avvisi e testo`() = runBlocking {
        val vm = vm()
        awaitVisible(vm, listOf(tx, rx, phase, warning, discarded))
        assertEquals(5, vm.uiState.value.totalInMemory)

        vm.onAction(TrafficLogAction.ToggleEvents)
        awaitVisible(vm, listOf(tx, rx, discarded))
        vm.onAction(TrafficLogAction.ToggleEvents)
        vm.onAction(TrafficLogAction.ToggleFrames)
        awaitVisible(vm, listOf(phase, warning))
        vm.onAction(TrafficLogAction.ToggleFrames)

        vm.onAction(TrafficLogAction.ToggleOnlyWarnings)
        awaitVisible(vm, listOf(warning, discarded))
        vm.onAction(TrafficLogAction.ToggleOnlyWarnings)

        vm.onAction(TrafficLogAction.SetText("lrc 21"))
        awaitVisible(vm, listOf(rx))
        vm.onAction(TrafficLogAction.SetText("ff 00"))
        awaitVisible(vm, listOf(discarded))
    }

    /** In pausa la vista non cambia, ma le voci continuano ad arrivare nel registratore. */
    @Test
    fun `la pausa congela visible`() = runBlocking {
        val vm = vm()
        awaitVisible(vm, listOf(tx, rx, phase, warning, discarded))
        vm.onAction(TrafficLogAction.TogglePause)
        val newer = entry(6, TrafficKind.RX, "ACK (LRC 7A, WIRE)")
        entries.value = entries.value + newer
        awaitTrue(message = { "totale aggiornato" }) { vm.uiState.value.totalInMemory == 6 }
        assertEquals(listOf(tx, rx, phase, warning, discarded), vm.uiState.value.visible, "vista congelata")
        vm.onAction(TrafficLogAction.TogglePause)
        awaitVisible(vm, listOf(tx, rx, phase, warning, discarded, newer))
    }

    /** "Esporta…" scrive esattamente le righe che l'operatore vede (filtro compreso). */
    @Test
    fun `Export passa al repository le voci visibili`() = runBlocking {
        val vm = vm()
        vm.onAction(TrafficLogAction.ToggleOnlyWarnings)
        awaitVisible(vm, listOf(warning, discarded))
        val target = File("export.txt")
        vm.onAction(TrafficLogAction.Export(target))
        awaitTrue(message = { "export chiamato" }) { exported != null }
        assertEquals(target to listOf(warning, discarded), exported)
    }
}
