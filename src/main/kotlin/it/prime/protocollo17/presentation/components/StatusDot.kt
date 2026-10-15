package it.prime.protocollo17.presentation.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import it.prime.protocollo17.presentation.model.ResultTone
import it.prime.protocollo17.presentation.theme.PreviewTheme
import it.prime.protocollo17.presentation.theme.SuccessGreen
import it.prime.protocollo17.presentation.theme.WarningAmber

/**
 * Pallino di stato da 12 dp (collegamento, simulatore, esito), colore animato in 300 ms.
 *
 * [pulsing] per gli stati transitori ("Connessione…"): un pallino fermo durante un'attesa di 120 s sembra un'app
 * bloccata. L'animazione è solo di trasparenza, non sposta il layout.
 */
@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier, pulsing: Boolean = false) {
    val animated by animateColorAsState(color, tween(durationMillis = 300), label = "statusDot")
    val alpha = if (pulsing) {
        val transition = rememberInfiniteTransition(label = "statusDotPulse")
        val value by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 700, easing = LinearEasing), RepeatMode.Reverse),
            label = "statusDotAlpha",
        )
        value
    } else {
        1f
    }
    Box(modifier.size(12.dp).alpha(alpha).background(animated, CircleShape))
}

/**
 * Colore di un tono d'esito (§6.7): verde approvato, rosso rifiutato, ambra sconosciuto o da verificare, primario
 * per le risposte informative, neutro per "non inviato". Un solo punto, così Console, Journal e dialog di
 * risoluzione colorano allo stesso modo lo stesso esito.
 */
@Composable
fun toneColor(tone: ResultTone): Color = when (tone) {
    ResultTone.SUCCESS -> SuccessGreen
    ResultTone.DANGER -> MaterialTheme.colorScheme.error
    ResultTone.WARNING -> WarningAmber
    ResultTone.INFO -> MaterialTheme.colorScheme.primary
    ResultTone.NEUTRAL -> MaterialTheme.colorScheme.outline
}

@Suppress("unused")
@Preview
@Composable
private fun StatusDotPreview() {
    PreviewTheme {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusDot(SuccessGreen)
            StatusDot(WarningAmber, pulsing = true)
            StatusDot(MaterialTheme.colorScheme.error)
            StatusDot(MaterialTheme.colorScheme.outline)
        }
    }
}
