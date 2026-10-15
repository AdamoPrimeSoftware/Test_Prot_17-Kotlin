package it.prime.protocollo17.presentation.util

import it.prime.protocollo17.domain.model.settings.WindowGeometry

/** Rettangolo in dp (schermo o area di lavoro). */
data class DpRect(val x: Float, val y: Float, val width: Float, val height: Float) {
    fun contains(px: Float, py: Float): Boolean = px >= x && px < x + width && py >= y && py < y + height
}

/** Misure di una finestra (§6.1): default al primo avvio e minimo sotto cui il layout non è più utilizzabile. */
data class WindowSpec(val defaultWidth: Float, val defaultHeight: Float, val minWidth: Float, val minHeight: Float)

/**
 * Pura: dimensioni = salvate o default, forzate in [min, area di lavoro] (il minimo stesso è ridotto all'area se
 * più grande); posizione mantenuta solo se il punto (x + 40, y + 20) cade su uno degli schermi, altrimenti null
 * (finestra centrata); maximized conservato.
 *
 * Perché: un portatile 1920×1080 al 150% ha un'area di lavoro di circa 1280×688 dp. Una geometria salvata su un
 * monitor esterno più grande, o un default più alto dell'area, aprirebbe la finestra con la barra del titolo o i
 * pulsanti fuori schermo; una posizione su un monitor scollegato la renderebbe invisibile ("l'app non si apre").
 * Il punto (x + 40, y + 20) è dentro la barra del titolo: se è visibile, la finestra si può trascinare.
 * Un'area di lavoro con dimensioni non positive (ambiente senza schermi) non limita nulla.
 */
fun coerceGeometry(saved: WindowGeometry?, spec: WindowSpec, screens: List<DpRect>, workArea: DpRect): WindowGeometry {
    val maxWidth = if (workArea.width > 0f) workArea.width else Float.MAX_VALUE
    val maxHeight = if (workArea.height > 0f) workArea.height else Float.MAX_VALUE
    val minWidth = minOf(spec.minWidth, maxWidth)
    val minHeight = minOf(spec.minHeight, maxHeight)
    val width = (saved?.widthDp ?: spec.defaultWidth).coerceIn(minWidth, maxWidth)
    val height = (saved?.heightDp ?: spec.defaultHeight).coerceIn(minHeight, maxHeight)
    val x = saved?.xDp
    val y = saved?.yDp
    val visible = x != null && y != null && screens.any { it.contains(x + 40f, y + 20f) }
    return WindowGeometry(
        widthDp = width,
        heightDp = height,
        xDp = if (visible) x else null,
        yDp = if (visible) y else null,
        maximized = saved?.maximized ?: false,
    )
}

/** Minimo effettivo della finestra: lo stesso limite di [coerceGeometry], per `window.minimumSize`. */
fun effectiveMinimum(spec: WindowSpec, workArea: DpRect): Pair<Float, Float> {
    val maxWidth = if (workArea.width > 0f) workArea.width else Float.MAX_VALUE
    val maxHeight = if (workArea.height > 0f) workArea.height else Float.MAX_VALUE
    return minOf(spec.minWidth, maxWidth) to minOf(spec.minHeight, maxHeight)
}
