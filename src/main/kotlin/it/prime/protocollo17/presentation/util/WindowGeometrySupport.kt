package it.prime.protocollo17.presentation.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import it.prime.protocollo17.domain.model.settings.WindowGeometry
import it.prime.protocollo17.presentation.state.AppWindowId
import it.prime.protocollo17.presentation.state.AppWindows
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import kotlin.math.roundToInt

// Unità: su Windows con JDK 9+ AWT lavora in coordinate logiche, già scalate dal sistema (al 150% uno schermo
// 1920×1080 vale 1280×720). Compose Desktop imposta la finestra con `dp.value` direttamente in queste unità
// (Window.setSizeSafely ⇒ setSize(size.width.value.roundToInt(), …)), quindi 1 unità AWT = 1 dp e la "conversione
// con la densità" di §6.1 ha fattore 1. Moltiplicare per la densità darebbe al 150% un minimo di 1440 dp, più largo
// dell'area di lavoro del portatile.

/** Schermi collegati in dp; lista vuota se l'ambiente grafico non risponde. */
fun readScreens(): List<DpRect> = runCatching {
    GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { device ->
        val b = device.defaultConfiguration.bounds
        DpRect(x = b.x.toFloat(), y = b.y.toFloat(), width = b.width.toFloat(), height = b.height.toFloat())
    }
}.getOrDefault(emptyList())

/** Area di lavoro (schermo principale meno la barra delle applicazioni) in dp; 0×0 = sconosciuta, nessun limite. */
fun readWorkArea(): DpRect = runCatching {
    val b = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
    DpRect(x = b.x.toFloat(), y = b.y.toFloat(), width = b.width.toFloat(), height = b.height.toFloat())
}.getOrDefault(DpRect(0f, 0f, 0f, 0f))

/** Geometria corrente da salvare. Con la finestra massimizzata dimensione e posizione non sono quelle da ripristinare. */
fun WindowState.toGeometry(): WindowGeometry {
    val absolute = position as? WindowPosition.Absolute
    return WindowGeometry(
        widthDp = size.width.value,
        heightDp = size.height.value,
        xDp = absolute?.x?.value,
        yDp = absolute?.y?.value,
        maximized = placement == WindowPlacement.Maximized,
    )
}

/**
 * Salva subito la geometria di [key] (ultimo salvataggio alla chiusura della principale). Massimizzata o a schermo
 * intero: si tiene l'ultima geometria "flottante" salvata e si aggiorna solo il flag, così togliendo la
 * massimizzazione al prossimo avvio la finestra torna alla misura scelta dall'operatore.
 */
fun persistWindowGeometry(key: String, state: WindowState, windows: AppWindows) {
    val current = state.toGeometry()
    val previous = windows.geometry(key)
    val toSave = when {
        state.placement == WindowPlacement.Floating -> current
        previous != null -> previous.copy(maximized = state.placement == WindowPlacement.Maximized)
        else -> current
    }
    windows.saveGeometry(key, toSave)
}

/**
 * Legge schermi e area di lavoro (GraphicsEnvironment, in runCatching) e applica coerceGeometry; salva in
 * continuo (§6.1): `snapshotFlow` di dimensione, posizione e stato con debounce di 500 ms. Così anche le
 * secondarie distrutte dalla chiusura della principale (senza il loro onCloseRequest) hanno la geometria salvata.
 */
@OptIn(FlowPreview::class)
@Composable
fun rememberPersistedWindowState(key: String, spec: WindowSpec, windows: AppWindows): WindowState {
    val geometry = remember(key) { coerceGeometry(windows.geometry(key), spec, readScreens(), readWorkArea()) }
    val x = geometry.xDp
    val y = geometry.yDp
    val state = rememberWindowState(
        placement = if (geometry.maximized) WindowPlacement.Maximized else WindowPlacement.Floating,
        position = if (x != null && y != null) WindowPosition(x.dp, y.dp) else WindowPosition(Alignment.Center),
        size = DpSize(geometry.widthDp.dp, geometry.heightDp.dp),
    )
    LaunchedEffect(state, key) {
        snapshotFlow { Triple(state.size, state.position, state.placement) }
            .debounce(500)
            .collect { persistWindowGeometry(key, state, windows) }
    }
    return state
}

/** Porta in primo piano la finestra a ogni AppWindows.focusRequests per id (anche se minimizzata o coperta). */
@Composable
fun FrameWindowScope.BringToFrontOn(id: AppWindowId, state: WindowState, windows: AppWindows) {
    LaunchedEffect(id, windows) {
        windows.focusRequests.filter { it == id }.collect {
            state.isMinimized = false
            window.toFront()
            window.requestFocus()
        }
    }
}

/**
 * Minimo imposto dal gestore finestre (non solo al ripristino): sotto, i pannelli si sovrappongono. Ridotto
 * all'area di lavoro se più grande (stessa regola di coerceGeometry).
 */
@Composable
fun FrameWindowScope.ApplyMinimumSize(spec: WindowSpec) {
    LaunchedEffect(spec) {
        val (minWidth, minHeight) = effectiveMinimum(spec, readWorkArea())
        window.minimumSize = Dimension(minWidth.roundToInt(), minHeight.roundToInt())
    }
}
