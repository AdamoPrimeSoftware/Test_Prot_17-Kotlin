package it.prime.protocollo17.presentation.components

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import it.prime.protocollo17.presentation.theme.PreviewTheme

/**
 * Campo numerico delle Opzioni e del Simulatore. Mostra il testo così come lo scrive l'operatore, anche se non è
 * valido: l'errore resta accanto al testo sbagliato (§6.11) invece di far "rimbalzare" il valore al precedente,
 * che su desktop sembra un campo che non accetta l'input. [enabled] = false per la porta del Simulatore durante un
 * comando (§6.14).
 */
@Composable
fun NumberTextField(
    label: String,
    text: String,
    onTextChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    suffix: String? = "ms",
    error: String? = null,
    hint: String? = null,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = text,
        onValueChange = onTextChange,
        enabled = enabled,
        singleLine = true,
        label = { Text(label) },
        suffix = suffix?.let { { Text(it) } },
        isError = error != null,
        supportingText = (error ?: hint)?.let { { Text(it) } },
        modifier = modifier,
    )
}

@Suppress("unused")
@Preview
@Composable
private fun NumberTextFieldPreview() {
    PreviewTheme {
        Column(Modifier.padding(12.dp).width(280.dp)) {
            NumberTextField(label = "Timeout ACK", text = "2000", onTextChange = {}, hint = "Default 2000")
            NumberTextField(label = "Timeout ACK", text = "abc", onTextChange = {}, error = "Timeout ACK: valore tra 100 e 30000 ms")
            NumberTextField(label = "Reinvii su NAK", text = "3", onTextChange = {}, suffix = null)
            NumberTextField(label = "Porta", text = "15577", onTextChange = {}, suffix = null, hint = "Disabilitato", enabled = false)
        }
    }
}
