package it.prime.protocollo17.presentation.screen.console.view.components

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import it.prime.protocollo17.ecr17.model.CommandCode
import it.prime.protocollo17.presentation.components.CommandLetterChip
import it.prime.protocollo17.presentation.components.ScrollableColumn
import it.prime.protocollo17.presentation.components.SectionLabel
import it.prime.protocollo17.presentation.components.WithTooltip
import it.prime.protocollo17.presentation.screen.console.model.CommandCatalog
import it.prime.protocollo17.presentation.screen.console.model.CommandDef
import it.prime.protocollo17.presentation.theme.PreviewTheme
import it.prime.protocollo17.presentation.theme.WarningAmber

/**
 * Barra laterale con un comando per voce (§6.2, stile AppNavigationSidebar di LocalDesktopAi): 13 comandi in tre
 * gruppi familiari agli utenti dell'app WPF si leggono tutti a colpo d'occhio. Lettera in un chip (rosso per i
 * comandi che muovono denaro), lucchetto sui comandi bloccati da un esito da risolvere. Durante un comando la
 * barra è disabilitata: cambiare selezione non fermerebbe nulla e confonderebbe l'overlay.
 */
@Composable
fun CommandSidebar(
    selected: CommandCode,
    blocked: Set<CommandCode>,
    enabled: Boolean,
    onSelect: (CommandCode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = modifier.width(232.dp).fillMaxHeight()) {
        ScrollableColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
            CommandCatalog.all.groupBy { it.group }.forEach { (group, commands) ->
                SectionLabel(group.title, modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 4.dp))
                commands.forEach { def ->
                    SidebarRow(
                        def = def,
                        selected = def.code == selected,
                        blocked = def.code in blocked,
                        enabled = enabled,
                        onClick = { onSelect(def.code) },
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun SidebarRow(def: CommandDef, selected: Boolean, blocked: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
    DisableSelection {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // Margine destro più largo: è la corsia della scrollbar (8 dp).
                .padding(start = 8.dp, end = 12.dp, top = 1.dp, bottom = 1.dp)
                .clip(RoundedCornerShape(8.dp))
                .then(if (selected) Modifier.background(MaterialTheme.colorScheme.secondaryContainer) else Modifier)
                .clickable(enabled = enabled, onClick = onClick)
                .alpha(if (enabled || selected) 1f else 0.6f)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CommandLetterChip(def.code.wire, danger = def.danger)
            Spacer(Modifier.width(10.dp))
            Text(
                text = def.title,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (blocked) {
                WithTooltip("Bloccato: esito da risolvere") {
                    Icon(Icons.Filled.Lock, contentDescription = "Bloccato: esito da risolvere", tint = WarningAmber, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Suppress("unused")
@Preview
@Composable
private fun CommandSidebarPreview() {
    PreviewTheme {
        CommandSidebar(
            selected = CommandCode.PAYMENT,
            blocked = setOf(CommandCode.PAYMENT, CommandCode.PAYMENT_EXTENDED, CommandCode.REVERSAL, CommandCode.CARD_VERIFICATION),
            enabled = true,
            onSelect = {},
            modifier = Modifier.height(640.dp),
        )
    }
}
