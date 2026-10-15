package it.prime.protocollo17.presentation.screen.options.viewModel

import it.prime.protocollo17.data.local.AppDirs
import it.prime.protocollo17.domain.model.settings.OptionKey
import it.prime.protocollo17.domain.util.ConfigResolver
import it.prime.protocollo17.ecr17.profile.TerminalProfileId
import it.prime.protocollo17.ecr17.profile.TerminalProfiles
import it.prime.protocollo17.presentation.FakeCommandRunner
import it.prime.protocollo17.presentation.FakeSettings
import it.prime.protocollo17.presentation.StubFallbacks
import it.prime.protocollo17.presentation.popStandaloneScreenModels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Opzioni (§6.11, R31). Gira dopo il merge di WP5 (ConfigResolver): i range dell'operatore e la configurazione
 * effettiva sono suoi. Un testo sbagliato resta visibile con il suo errore e non viene mai salvato: un timeout
 * ACK "abc" o 40 ms finito nel file cambierebbe il comportamento sul terminale senza che l'operatore lo sappia.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OptionsViewModelTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var settings: FakeSettings
    private val runner = FakeCommandRunner()

    @BeforeEach
    fun setUp() {
        // Prima del merge di WP5 ConfigResolver è uno stub: il collect delle impostazioni nel ViewModel morirebbe con
        // NotImplementedError fuori da runTest, e kotlinx-coroutines-test la riconsegnerebbe al primo runTest di
        // un'ALTRA classe, facendolo fallire senza colpa. Meglio saltare in modo esplicito: dopo il merge girano.
        assumeTrue(configResolverReady(), "ConfigResolver (WP5) ancora stub: OptionsViewModelTest gira dopo il merge")
        Dispatchers.setMain(UnconfinedTestDispatcher())
        StubFallbacks.install()
        settings = FakeSettings()
    }

    private fun configResolverReady(): Boolean = try {
        ConfigResolver.profileDefaults(TerminalProfiles.INGENICO)
        true
    } catch (e: NotImplementedError) {
        false
    }

    @AfterEach
    fun tearDown() {
        if (!::settings.isInitialized) return     // test saltato dall'assunzione
        popStandaloneScreenModels()
        StubFallbacks.uninstall()
        Dispatchers.resetMain()
    }

    private fun vm() = OptionsViewModel(settings = settings.repository, dirs = AppDirs(tempDir), runner = runner)

    @Test
    fun `valore numerico non valido resta visibile con l'errore e non si salva`() {
        val vm = vm()
        vm.onAction(OptionsAction.SetOption(OptionKey.ACK_TIMEOUT_MS, "abc"))
        assertEquals("abc", vm.uiState.value.texts[OptionKey.ACK_TIMEOUT_MS])
        assertNotNull(vm.uiState.value.fieldErrors[OptionKey.ACK_TIMEOUT_MS])
        assertEquals(2_000L, settings.value.protocol.ackTimeoutMs, "nulla salvato")
    }

    @Test
    fun `valore valido salvato nel protocollo`() {
        val vm = vm()
        vm.onAction(OptionsAction.SetOption(OptionKey.ACK_TIMEOUT_MS, "abc"))
        vm.onAction(OptionsAction.SetOption(OptionKey.ACK_TIMEOUT_MS, "3000"))
        assertEquals(3_000L, settings.value.protocol.ackTimeoutMs)
        assertFalse(OptionKey.ACK_TIMEOUT_MS in vm.uiState.value.fieldErrors)
    }

    @Test
    fun `ripristino dei valori del profilo`() {
        val vm = vm()
        vm.onAction(OptionsAction.SetOption(OptionKey.ACK_TIMEOUT_MS, "3000"))
        vm.onAction(OptionsAction.SetOption(OptionKey.RESPONSE_TIMEOUT_MS, "zzz"))
        vm.onAction(OptionsAction.ResetToProfile)
        assertEquals(ConfigResolver.profileDefaults(TerminalProfiles.INGENICO), settings.value.protocol)
        assertTrue(vm.uiState.value.texts.isEmpty())
        assertTrue(vm.uiState.value.fieldErrors.isEmpty())
    }

    /** CCV non ha porta predefinita: senza porta la configurazione non è utilizzabile e il problema si vede. */
    @Test
    fun `profilo CCV senza porta mostra il problema`() {
        val vm = vm()
        vm.onAction(OptionsAction.SetProfile(TerminalProfileId.CCV))
        val resolution = checkNotNull(vm.uiState.value.resolution) { "configurazione effettiva non calcolata" }
        assertFalse(resolution.usable)
        assertTrue(resolution.problems.any { it.key == "port" }, resolution.problems.toString())
    }

    /** L'anteprima dei frame di controllo segue la regola scelta (U3). */
    @Test
    fun `controlPreview cambia con CONTROL_LRC_TX`() {
        val vm = vm()
        assertEquals("ACK 06 03 7A · NAK 15 03 69", vm.uiState.value.controlPreview)
        vm.onAction(OptionsAction.SetOption(OptionKey.CONTROL_LRC_TX, "SAME_AS_APP"))
        assertEquals("ACK 06 03 78 · NAK 15 03 6B", vm.uiState.value.controlPreview)
    }

    @Test
    fun `Terminal ID non numerico resta visibile e non si salva`() {
        val vm = vm()
        vm.onAction(OptionsAction.SetTerminalId("12AB"))
        assertEquals("12AB", vm.uiState.value.idTexts["terminalId"])
        assertNotNull(vm.uiState.value.idErrors["terminalId"])
        assertEquals("00000000", settings.value.terminal.terminalId)
        vm.onAction(OptionsAction.SetTerminalId("02575733"))
        assertEquals("02575733", settings.value.terminal.terminalId)
    }

    @Test
    fun `host vuoto e porta fuori range sono errori`() {
        val vm = vm()
        vm.onAction(OptionsAction.SetHost("  "))
        assertNotNull(vm.uiState.value.hostError)
        assertEquals("192.168.1.131", settings.value.terminal.host)
        vm.onAction(OptionsAction.SetPort("70000"))
        assertNotNull(vm.uiState.value.portError)
        vm.onAction(OptionsAction.SetPort(""))
        assertEquals(null, settings.value.terminal.port, "vuoto = porta del profilo")
    }
}
