package it.prime.protocollo17.presentation.util

import it.prime.protocollo17.domain.model.settings.WindowGeometry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Geometria delle finestre (R24): al 150% su un 1920×1080 l'area di lavoro è circa 1280×688 dp. Una finestra più
 * grande dell'area, o posizionata su un monitor scollegato, non si potrebbe usare (barra del titolo fuori schermo).
 */
class GeometryRulesTest {

    private val main = WindowSpec(defaultWidth = 1280f, defaultHeight = 800f, minWidth = 960f, minHeight = 600f)
    private val bigScreen = DpRect(0f, 0f, 2560f, 1440f)
    private val bigWorkArea = DpRect(0f, 0f, 2560f, 1400f)

    /** Laptop 1920×1080 al 150%. */
    private val laptopWorkArea = DpRect(0f, 0f, 1280f, 688f)

    @Test
    fun `senza salvataggio si usano i default`() {
        val g = coerceGeometry(null, main, listOf(bigScreen), bigWorkArea)
        assertEquals(WindowGeometry(widthDp = 1280f, heightDp = 800f, xDp = null, yDp = null, maximized = false), g)
    }

    /** Il default 1280×800 non entra nei 688 dp di altezza del portatile al 150%. */
    @Test
    fun `dimensioni piu' grandi dell'area di lavoro sono ridotte`() {
        val saved = WindowGeometry(widthDp = 2400f, heightDp = 1300f, xDp = 0f, yDp = 0f)
        val g = coerceGeometry(saved, main, listOf(DpRect(0f, 0f, 1280f, 720f)), laptopWorkArea)
        assertEquals(1280f, g.widthDp)
        assertEquals(688f, g.heightDp)
    }

    @Test
    fun `dimensioni piu' piccole del minimo sono portate al minimo`() {
        val saved = WindowGeometry(widthDp = 300f, heightDp = 200f)
        val g = coerceGeometry(saved, main, listOf(bigScreen), bigWorkArea)
        assertEquals(960f, g.widthDp)
        assertEquals(600f, g.heightDp)
    }

    /** Un minimo più grande dell'area (1280×688) è ridotto all'area: la finestra resta tutta visibile. */
    @Test
    fun `minimo piu' grande dell'area di lavoro e' ridotto all'area`() {
        val wide = WindowSpec(defaultWidth = 1500f, defaultHeight = 900f, minWidth = 1400f, minHeight = 700f)
        val g = coerceGeometry(null, wide, listOf(DpRect(0f, 0f, 1280f, 720f)), laptopWorkArea)
        assertEquals(1280f, g.widthDp)
        assertEquals(688f, g.heightDp)
        assertEquals(1280f to 688f, effectiveMinimum(wide, laptopWorkArea))
    }

    /** Una posizione sul secondo monitor scollegato renderebbe la finestra invisibile: si centra. */
    @Test
    fun `posizione su uno schermo scollegato diventa null`() {
        val saved = WindowGeometry(widthDp = 1000f, heightDp = 700f, xDp = 2700f, yDp = 100f)
        val g = coerceGeometry(saved, main, listOf(bigScreen), bigWorkArea)
        assertNull(g.xDp)
        assertNull(g.yDp)
        val visible = coerceGeometry(saved.copy(xDp = 100f, yDp = 50f), main, listOf(bigScreen), bigWorkArea)
        assertEquals(100f, visible.xDp)
        assertEquals(50f, visible.yDp)
    }

    @Test
    fun `maximized e' conservato`() {
        val saved = WindowGeometry(widthDp = 1000f, heightDp = 700f, maximized = true)
        assertTrue(coerceGeometry(saved, main, listOf(bigScreen), bigWorkArea).maximized)
    }
}
