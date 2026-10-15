package it.prime.protocollo17.presentation.components

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import it.prime.protocollo17.presentation.theme.PreviewTheme

/**
 * Pulsante con la sola icona e un tooltip al passaggio del mouse.
 *
 * Su desktop un'icona senza etichetta è un indovinello: `contentDescription` serve agli screen reader ma non
 * compare a schermo. [badge] (es. tentativi da risolvere sull'icona Journal) appare solo se > 0.
 */
@Composable
fun TooltipIconButton(
    icon: ImageVector,
    tooltip: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = LocalContentColor.current,
    iconSize: Dp = 20.dp,
    badge: Int? = null,
) {
    WithTooltip(tooltip) {
        IconButton(onClick = onClick, enabled = enabled, modifier = modifier) {
            val iconContent: @Composable () -> Unit = {
                Icon(
                    imageVector = icon,
                    // Resta per l'accessibilità: il tooltip è un'aggiunta, non un rimpiazzo.
                    contentDescription = tooltip,
                    tint = if (enabled) tint else tint.copy(alpha = 0.38f),
                    modifier = Modifier.size(iconSize),
                )
            }
            if (badge != null && badge > 0) {
                BadgedBox(badge = { Badge { Text(if (badge > 99) "99+" else badge.toString()) } }) { iconContent() }
            } else {
                iconContent()
            }
        }
    }
}

/**
 * Avvolge un contenuto qualsiasi con un tooltip testuale: chip di stato, badge e indicatori il cui significato
 * non è ovvio ("OSSERVATO", il pallino del simulatore, il lucchetto dei comandi bloccati).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WithTooltip(text: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        // Il tooltip è un popup: il suo testo non deve registrarsi nella selezione della finestra.
        tooltip = { DisableSelection { PlainTooltip { Text(text) } } },
        state = rememberTooltipState(),
        modifier = modifier,
    ) {
        content()
    }
}

@Suppress("unused")
@Preview
@Composable
private fun TooltipIconButtonPreview() {
    PreviewTheme {
        Row {
            TooltipIconButton(icon = Icons.Filled.Settings, tooltip = "Opzioni (Ctrl+,)", onClick = {})
            TooltipIconButton(icon = Icons.AutoMirrored.Filled.ListAlt, tooltip = "Journal (Ctrl+J)", onClick = {}, badge = 2)
            TooltipIconButton(icon = Icons.Filled.Settings, tooltip = "Disabilitato", onClick = {}, enabled = false)
        }
    }
}
