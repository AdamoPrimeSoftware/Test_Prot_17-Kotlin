package it.prime.protocollo17.presentation.screen.simulator.viewModel

import it.prime.protocollo17.domain.model.simulator.SimLogEntry
import it.prime.protocollo17.domain.model.simulator.SimStats
import it.prime.protocollo17.domain.model.simulator.SimulatorFailure
import it.prime.protocollo17.domain.model.simulator.SimulatorStatus
import it.prime.protocollo17.domain.repository.SimulatorController
import it.prime.protocollo17.presentation.FakeSettings
import it.prime.protocollo17.presentation.popStandaloneScreenModels
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Simulatore (§6.14, R26): la porta occupata è uno stato visibile accanto al campo, non un'eccezione; le azioni
 * "Usa come terminale"/"Ripristina" sono quelle del controller, le stesse del menu Strumenti.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SimulatorViewModelTest {

    private val status = MutableStateFlow<SimulatorStatus>(SimulatorStatus.Stopped)
    private lateinit var settings: FakeSettings
    private lateinit var simulator: SimulatorController

    /** Main in tempo virtuale: il debounce della porta si fa avanzare con advanceTimeBy. */
    private val main = UnconfinedTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(main)
        settings = FakeSettings()
        simulator = mockk(relaxed = true) {
            every { this@mockk.status } returns this@SimulatorViewModelTest.status
            every { stats } returns MutableStateFlow(SimStats())
            every { log } returns MutableStateFlow(emptyList<SimLogEntry>())
            every { isTarget } returns MutableStateFlow(false)
            coEvery { start() } answers {
                this@SimulatorViewModelTest.status.value = SimulatorStatus.Running(15577)
                SimulatorStatus.Running(15577)
            }
            every { useAsTarget() } returns true
            every { restoreTarget() } returns false
        }
    }

    @AfterEach
    fun tearDown() {
        popStandaloneScreenModels()
        Dispatchers.resetMain()
    }

    private fun vm() = SimulatorViewModel(simulator = simulator, settings = settings.repository)

    @Test
    fun `Start porta lo stato a Running`() {
        val vm = vm()
        vm.onAction(SimulatorAction.Start)
        assertTrue(vm.uiState.value.running)
        assertNull(vm.uiState.value.statusError)
    }

    /** Porta occupata: messaggio rosso accanto al campo porta, nessuna eccezione. */
    @Test
    fun `Failed PortInUse diventa statusError`() {
        val vm = vm()
        status.value = SimulatorStatus.Failed(SimulatorFailure.PortInUse(15577))
        assertEquals("La porta 15577 è già in uso: scegline un'altra.", vm.uiState.value.statusError)
    }

    @Test
    fun `UseAsTarget e RestoreTarget delegano al controller`() {
        val vm = vm()
        vm.onAction(SimulatorAction.UseAsTarget)
        vm.onAction(SimulatorAction.RestoreTarget)
        verify { simulator.useAsTarget() }
        verify { simulator.restoreTarget() }
    }

    /** L'LRC applicativo del simulatore è salvato come wireName (§4.2); un valore fuori catalogo no. */
    @Test
    fun `SetAppLrcMode salva simulator appLrcMode`() {
        val vm = vm()
        vm.onAction(SimulatorAction.SetAppLrcMode("std"))
        assertEquals("std", settings.value.simulator.appLrcMode)
        vm.onAction(SimulatorAction.SetAppLrcMode("sxt"))
        assertEquals("std", settings.value.simulator.appLrcMode)
    }

    /** La porta sbagliata resta scritta con l'errore; quella valida è salvata dopo la pausa (il controller riavvia). */
    @Test
    fun `porta non valida resta visibile e non si salva`() {
        val vm = vm()
        vm.onAction(SimulatorAction.SetNumber("port", "99999"))
        assertEquals("99999", vm.uiState.value.texts["port"])
        assertNotNull(vm.uiState.value.fieldErrors["port"])
        assertEquals(15577, settings.value.simulator.port)
        vm.onAction(SimulatorAction.SetNumber("port", "15600"))
        assertNull(vm.uiState.value.fieldErrors["port"])
        main.scheduler.advanceTimeBy(SimulatorViewModel.PORT_COMMIT_DELAY_MS + 1)
        assertEquals(15600, settings.value.simulator.port)
    }

    /**
     * Ogni cambio di porta riavvia il simulatore attivo: scrivendo 16000 non deve riavviarsi su 1, 16, 160 e 1600
     * (una porta intermedia occupata darebbe un errore e troncherebbe un comando in corso). Una sola scrittura.
     */
    @Test
    fun `la porta si salva una volta sola a fine digitazione`() {
        val vm = vm()
        val before = settings.updates
        listOf("1", "16", "160", "1600", "16000").forEach { text ->
            vm.onAction(SimulatorAction.SetNumber("port", text))
            main.scheduler.advanceTimeBy(200)
        }
        assertEquals(15577, settings.value.simulator.port, "nessun salvataggio durante la digitazione")
        assertEquals("16000", vm.uiState.value.texts["port"])
        main.scheduler.advanceTimeBy(SimulatorViewModel.PORT_COMMIT_DELAY_MS)
        assertEquals(16000, settings.value.simulator.port)
        assertEquals(before + 1, settings.updates, "una sola scrittura della porta")
    }

    /** Il testo in attesa non torna al valore vecchio se nel frattempo cambia un'altra impostazione del simulatore. */
    @Test
    fun `porta in attesa non sovrascritta da altre impostazioni`() {
        val vm = vm()
        vm.onAction(SimulatorAction.SetNumber("port", "16000"))
        vm.onAction(SimulatorAction.SetAutoStart(true))
        assertEquals("16000", vm.uiState.value.texts["port"])
        vm.onAction(SimulatorAction.SetNumber("ackDelayMs", "50"))
        assertEquals(50L, settings.value.simulator.ackDelayMs, "i ritardi si salvano subito")
        main.scheduler.advanceTimeBy(SimulatorViewModel.PORT_COMMIT_DELAY_MS + 1)
        assertEquals(16000, settings.value.simulator.port)
    }

    /** Avvia usa la porta appena scritta, senza aspettare la pausa; un testo sbagliato annulla il salvataggio in attesa. */
    @Test
    fun `Avvia salva subito la porta in attesa e un errore la annulla`() {
        val vm = vm()
        vm.onAction(SimulatorAction.SetNumber("port", "16000"))
        vm.onAction(SimulatorAction.SetNumber("port", "160000"))
        main.scheduler.advanceTimeBy(SimulatorViewModel.PORT_COMMIT_DELAY_MS + 1)
        assertEquals(15577, settings.value.simulator.port, "porta non valida: niente salvataggio del valore precedente")
        vm.onAction(SimulatorAction.SetNumber("port", "16001"))
        vm.onAction(SimulatorAction.Start)
        assertEquals(16001, settings.value.simulator.port)
        coVerify { simulator.start() }
    }

    /** Chiudendo la finestra subito dopo aver scritto, la porta non va persa. */
    @Test
    fun `onDispose salva la porta in attesa`() {
        val vm = vm()
        vm.onAction(SimulatorAction.SetNumber("port", "16002"))
        vm.onDispose()
        assertEquals(16002, settings.value.simulator.port)
    }
}
