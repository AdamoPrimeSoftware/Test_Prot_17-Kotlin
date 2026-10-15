package it.prime.protocollo17.presentation.screen.console.view.components

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import it.prime.protocollo17.domain.model.command.FieldKey
import it.prime.protocollo17.domain.util.RequestFactory
import it.prime.protocollo17.ecr17.model.CommandCode
import it.prime.protocollo17.presentation.components.CommandLetterChip
import it.prime.protocollo17.presentation.components.EnumDropdown
import it.prime.protocollo17.presentation.components.ScrollableColumn
import it.prime.protocollo17.presentation.components.SwitchRow
import it.prime.protocollo17.presentation.components.WithTooltip
import it.prime.protocollo17.presentation.screen.console.model.CommandCatalog
import it.prime.protocollo17.presentation.screen.console.model.CommandDef
import it.prime.protocollo17.presentation.screen.console.model.FieldDef
import it.prime.protocollo17.presentation.screen.console.model.FieldType
import it.prime.protocollo17.presentation.screen.console.viewModel.ConsoleAction
import it.prime.protocollo17.presentation.theme.PreviewTheme
import it.prime.protocollo17.presentation.theme.WarningAmber

/**
 * Form del comando selezionato (§6.6): resta visibile accanto all'esito, così si rilancia cambiando un campo
 * (nella UI Tauri il dialog dei parametri si chiudeva prima dell'esito).
 *
 * Ctrl+Invio esegue anche con il focus in un campo: un OutlinedTextField può consumare l'Invio prima che arrivi
 * alla finestra, quindi `onPreviewKeyEvent` qui lo intercetta e lo consuma (un solo effetto per pressione: la
 * finestra non lo vede più). [scrollable] = false quando il form sta già in una colonna scrollabile (layout
 * impilato): due scroll verticali annidati non hanno altezza definita.
 */
@Composable
fun CommandFormCard(
    def: CommandDef,
    values: Map<FieldKey, String>,
    errors: Map<FieldKey, String>,
    canRun: Boolean,
    blocked: Boolean,
    onAction: (ConsoleAction) -> Unit,
    modifier: Modifier = Modifier,
    scrollable: Boolean = true,
) {
    Card(
        modifier = modifier.onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.isCtrlPressed && (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
                onAction(ConsoleAction.RunSelected)
                true
            } else {
                false
            }
        },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp)) {
            Header(def)
            HorizontalDivider(Modifier.padding(vertical = 10.dp))
            val fields: @Composable ColumnScope.() -> Unit = {
                if (def.fields.isEmpty()) {
                    Text("Nessun parametro: il comando usa Terminal ID e ECR id delle Opzioni.", style = MaterialTheme.typography.bodySmall)
                }
                def.fields.forEach { field ->
                    FieldInput(
                        field = field,
                        value = values[field.key] ?: RequestFactory.defaults(def.code)[field.key].orEmpty(),
                        error = errors[field.key],
                        onChange = { onAction(ConsoleAction.UpdateField(def.code, field.key, it)) },
                    )
                }
            }
            if (scrollable) {
                ScrollableColumn(
                    modifier = Modifier.weight(1f, fill = false),
                    contentPadding = PaddingValues(end = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    content = fields,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = fields)
            }
            Spacer(Modifier.height(12.dp))
            Footer(def, canRun, blocked, onAction)
        }
    }
}

@Composable
private fun Header(def: CommandDef) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CommandLetterChip(def.code.wire, danger = def.danger, size = 30.dp)
        Spacer(Modifier.width(10.dp))
        Text(def.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (def.danger) {
            Text(
                text = if (def.code.movesMoney) "FINANZIARIO" else "NON RIPETIBILE",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
    Spacer(Modifier.height(6.dp))
    Text(def.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun FieldInput(field: FieldDef, value: String, error: String?, onChange: (String) -> Unit) {
    when (val type = field.type) {
        FieldType.Amount -> OutlinedTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            label = { Text(field.label) },
            suffix = { Text("€") },
            isError = error != null,
            supportingText = (error ?: field.hint)?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
        )
        is FieldType.Digits -> OutlinedTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            label = { Text(if (type.required) "${field.label} *" else field.label) },
            isError = error != null,
            supportingText = {
                Row {
                    Text(error ?: field.hint.orEmpty(), modifier = Modifier.weight(1f))
                    Text("${value.length}/${type.maxLength}")
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        is FieldType.Text -> OutlinedTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            label = { Text(field.label) },
            isError = error != null || value.length > type.maxLength,
            supportingText = {
                Row {
                    Text(error ?: field.hint.orEmpty(), modifier = Modifier.weight(1f))
                    Text("${value.length}/${type.maxLength}")
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        is FieldType.Choice -> EnumDropdown(
            label = field.label,
            options = type.options,
            selected = value,
            onSelect = onChange,
            modifier = Modifier.fillMaxWidth(),
        )
        FieldType.Toggle -> SwitchRow(
            checked = value.equals("true", ignoreCase = true),
            onCheckedChange = { onChange(it.toString()) },
            title = field.label,
            hint = field.hint,
        )
    }
}

@Composable
private fun Footer(def: CommandDef, canRun: Boolean, blocked: Boolean, onAction: (ConsoleAction) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { onAction(ConsoleAction.ResetForm(def.code)) }) { Text("Ripristina") }
        Spacer(Modifier.weight(1f))
        val label = if (def.danger) "Esegui (${if (def.code.movesMoney) "finanziario" else "non ripetibile"})" else "Esegui"
        val button: @Composable () -> Unit = {
            Button(
                onClick = { onAction(ConsoleAction.RunSelected) },
                enabled = canRun,
                colors = if (def.danger) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    )
                } else {
                    ButtonDefaults.buttonColors()
                },
            ) {
                Icon(if (blocked) Icons.Filled.Lock else Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(label)
                Spacer(Modifier.width(8.dp))
                Text("Ctrl+Invio", style = MaterialTheme.typography.labelSmall)
            }
        }
        if (blocked) WithTooltip("Bloccato: esito da risolvere") { button() } else button()
    }
    if (blocked) {
        Text(
            "Comando bloccato: un tentativo precedente ha l'esito da risolvere (banner in alto).",
            style = MaterialTheme.typography.bodySmall,
            color = WarningAmber,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

// ─── Preview ──────────────────────────────────────────────────────────────────────

@Suppress("unused")
@Preview
@Composable
private fun CommandFormCardPreview() {
    PreviewTheme {
        Column(Modifier.width(480.dp).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CommandFormCard(
                def = CommandCatalog.of(CommandCode.PAYMENT),
                values = RequestFactory.defaults(CommandCode.PAYMENT) + (FieldKey.RECEIPT_TEXT to "caffè"),
                errors = mapOf(FieldKey.RECEIPT_TEXT to "Carattere 'è' non ammesso (solo ASCII)"),
                canRun = true, blocked = false, onAction = {}, scrollable = false,
            )
            CommandFormCard(
                def = CommandCatalog.of(CommandCode.REPRINT),
                values = RequestFactory.defaults(CommandCode.REPRINT),
                errors = emptyMap(), canRun = false, blocked = true, onAction = {}, scrollable = false,
            )
        }
    }
}
