package it.prime.protocollo17.presentation.state

import app.cash.turbine.test
import it.prime.protocollo17.domain.model.settings.AppSettings
import it.prime.protocollo17.domain.model.settings.UiSettings
import it.prime.protocollo17.domain.model.settings.WindowGeometry
import it.prime.protocollo17.presentation.FakeSettings
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Finestre secondarie (R23): una finestra già aperta deve venire in primo piano invece di duplicarsi, e l'insieme
 * delle aperte deve restare salvato perché all'avvio si riaprano anche se la principale le ha distrutte.
 */
class AppWindowsTest {

    /** ui.openWindows inizializza lo stato; nomi sconosciuti (finestre rinominate) sono ignorati. */
    @Test
    fun `lo stato iniziale viene da ui openWindows`() {
        val settings = FakeSettings(AppSettings(ui = UiSettings(openWindows = setOf("JOURNAL", "SPARITA"))))
        val windows = AppWindows(settings.repository)
        assertEquals(setOf(AppWindowId.JOURNAL), windows.open.value)
    }

    /** open aggiunge e salva subito: la chiusura della principale non passa dagli onCloseRequest delle secondarie. */
    @Test
    fun `open aggiunge e aggiorna ui openWindows`() {
        val settings = FakeSettings()
        val windows = AppWindows(settings.repository)
        windows.open(AppWindowId.TRAFFIC_LOG)
        windows.open(AppWindowId.SIMULATOR)
        assertEquals(setOf(AppWindowId.TRAFFIC_LOG, AppWindowId.SIMULATOR), windows.open.value)
        assertEquals(setOf("TRAFFIC_LOG", "SIMULATOR"), settings.value.ui.openWindows)
    }

    /** Prima Ctrl+L su un Log minimizzato non faceva nulla: ora la finestra riceve la richiesta di primo piano. */
    @Test
    fun `open su una finestra gia' aperta emette focusRequests e non duplica`() = runTest {
        val settings = FakeSettings()
        val windows = AppWindows(settings.repository)
        windows.open(AppWindowId.TRAFFIC_LOG)
        val updatesAfterFirstOpen = settings.updates
        windows.focusRequests.test {
            windows.open(AppWindowId.TRAFFIC_LOG)
            assertEquals(AppWindowId.TRAFFIC_LOG, awaitItem())
            expectNoEvents()
        }
        assertEquals(setOf(AppWindowId.TRAFFIC_LOG), windows.open.value)
        assertEquals(updatesAfterFirstOpen, settings.updates, "riportare in primo piano non riscrive le impostazioni")
    }

    /** close toglie dallo stato e dalle impostazioni; toggle alterna. */
    @Test
    fun `close toglie e toggle alterna`() {
        val settings = FakeSettings()
        val windows = AppWindows(settings.repository)
        windows.open(AppWindowId.OPTIONS)
        windows.close(AppWindowId.OPTIONS)
        assertEquals(emptySet<AppWindowId>(), windows.open.value)
        assertEquals(emptySet<String>(), settings.value.ui.openWindows)
        windows.toggle(AppWindowId.JOURNAL)
        assertEquals(setOf(AppWindowId.JOURNAL), windows.open.value)
        windows.toggle(AppWindowId.JOURNAL)
        assertEquals(emptySet<AppWindowId>(), windows.open.value)
    }

    /** La geometria è salvata per chiave ("MAIN" o il nome della finestra) e riletta da ui.windows. */
    @Test
    fun `saveGeometry e geometry`() {
        val settings = FakeSettings()
        val windows = AppWindows(settings.repository)
        assertNull(windows.geometry("MAIN"))
        val geometry = WindowGeometry(widthDp = 1100f, heightDp = 680f, xDp = 10f, yDp = 20f)
        windows.saveGeometry("JOURNAL", geometry)
        assertEquals(geometry, windows.geometry("JOURNAL"))
        assertEquals(geometry, settings.value.ui.windows["JOURNAL"])
        val updates = settings.updates
        windows.saveGeometry("JOURNAL", geometry)
        assertEquals(updates, settings.updates, "una geometria uguale non viene riscritta")
    }
}
