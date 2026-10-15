package it.prime.protocollo17.presentation.components

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import it.prime.protocollo17.presentation.theme.PreviewTheme

/**
 * Scelta tra valori noti (tipo carta, LRC, decisione del simulatore) con etichette italiane.
 *
 * Un campo di sola lettura con un menu sopra invece di ExposedDropdownMenuBox: il clic su tutta l'area apre il
 * menu, e l'API non cambia tra versioni di Material 3. [selected] non presente tra le opzioni ⇒ mostra il valore
 * grezzo, così un valore illeggibile nelle impostazioni resta visibile invece di sparire.
 */
@Composable
fun <T> EnumDropdown(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == selected }?.second ?: selected.toString()
    Box(modifier) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
            modifier = Modifier.fillMaxWidth(),
        )
        // Strato trasparente sopra il campo: prende il clic (il campo readOnly lo userebbe per il cursore).
        Box(
            Modifier
                .matchParentSize()
                .padding(top = 8.dp)
                .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
                .clickable(enabled = enabled) { expanded = true },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        expanded = false
                        onSelect(value)
                    },
                )
            }
        }
    }
}

@Suppress("unused")
@Preview
@Composable
private fun EnumDropdownPreview() {
    PreviewTheme {
        EnumDropdown(
            label = "Tipo carta",
            options = listOf("AUTO" to "Automatico", "DEBIT" to "Debito", "CREDIT" to "Credito", "OTHER" to "Altro"),
            selected = "DEBIT",
            onSelect = {},
            modifier = Modifier.padding(12.dp).width(260.dp),
        )
    }
}
