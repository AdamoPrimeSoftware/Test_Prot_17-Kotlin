package it.prime.protocollo17.presentation.components

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import it.prime.protocollo17.presentation.theme.PreviewTheme

/**
 * Interruttore con titolo e spiegazione. Tutta la riga è cliccabile (non solo lo switch): su desktop si clicca
 * sul testo. [hint] spiega la conseguenza, che per gli switch U4/U5 è il punto (rischio di doppio addebito).
 */
@Composable
fun SwitchRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    title: String,
    hint: String?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 4.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (hint != null) {
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(12.dp))
        // onCheckedChange = null: il clic lo gestisce la riga (un solo bersaglio, nessun doppio evento).
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Suppress("unused")
@Preview
@Composable
private fun SwitchRowPreview() {
    PreviewTheme {
        Column(Modifier.padding(12.dp).width(520.dp)) {
            SwitchRow(
                checked = true, onCheckedChange = {}, title = "Go-ahead implicito sui comandi finanziari",
                hint = "Se l'ACK non arriva entro il timeout NON reinvia il pagamento e attende l'esito, come la DLL Ingenico.",
            )
            SwitchRow(checked = false, onCheckedChange = {}, title = "Disabilitato", hint = null, enabled = false)
        }
    }
}
