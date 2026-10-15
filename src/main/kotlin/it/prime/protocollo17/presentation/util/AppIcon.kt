package it.prime.protocollo17.presentation.util

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.VectorPainter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp
import it.prime.protocollo17.presentation.theme.Indigo600
import it.prime.protocollo17.presentation.theme.PreviewTheme

/**
 * Icona di tutte le finestre (§6.1): quadrato arrotondato Indigo600 con il glifo PointOfSale in bianco.
 *
 * Il glifo nudo, senza tinta, viene disegnato nero: quasi invisibile sulla barra del titolo e sulla taskbar scure
 * (R38). Disegnarlo bianco su un fondo pieno lo rende leggibile su qualsiasi tema, senza un PNG da produrre in v1.
 */
@Composable
fun rememberAppIcon(): Painter {
    val glyph = rememberVectorPainter(Icons.Filled.PointOfSale)
    return remember(glyph) { AppIconPainter(glyph) }
}

private class AppIconPainter(private val glyph: VectorPainter) : Painter() {
    override val intrinsicSize: Size = Size(256f, 256f)

    override fun DrawScope.onDraw() {
        drawRoundRect(color = Indigo600, cornerRadius = CornerRadius(size.minDimension * 0.22f))
        inset(size.minDimension * 0.17f) {
            with(glyph) { draw(size = size, colorFilter = ColorFilter.tint(Color.White)) }
        }
    }
}

@Suppress("unused")
@Preview
@Composable
private fun AppIconPreview() {
    PreviewTheme(darkTheme = true) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Image(rememberAppIcon(), contentDescription = "Icona dell'app", modifier = Modifier.size(64.dp))
            Image(rememberAppIcon(), contentDescription = "Icona dell'app", modifier = Modifier.size(16.dp))
        }
    }
}
