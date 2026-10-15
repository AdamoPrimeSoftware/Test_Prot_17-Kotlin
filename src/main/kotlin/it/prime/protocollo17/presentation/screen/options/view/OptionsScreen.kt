package it.prime.protocollo17.presentation.screen.options.view

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import it.prime.protocollo17.domain.model.settings.AppSettings
import it.prime.protocollo17.domain.model.settings.OptionKey
import it.prime.protocollo17.domain.model.settings.ProtocolSettings
import it.prime.protocollo17.domain.model.settings.ThemeMode
import it.prime.protocollo17.domain.util.ConfigResolver
import it.prime.protocollo17.ecr17.profile.Evidence
import it.prime.protocollo17.ecr17.profile.TerminalProfiles
import it.prime.protocollo17.presentation.components.EnumDropdown
import it.prime.protocollo17.presentation.components.NumberTextField
import it.prime.protocollo17.presentation.components.ScrollableColumn
import it.prime.protocollo17.presentation.components.SettingsCard
import it.prime.protocollo17.presentation.components.SwitchRow
import it.prime.protocollo17.presentation.components.TooltipIconButton
import it.prime.protocollo17.presentation.screen.options.viewModel.OptionsAction
import it.prime.protocollo17.presentation.screen.options.viewModel.OptionsUiState
import it.prime.protocollo17.presentation.theme.PreviewTheme
import it.prime.protocollo17.presentation.theme.WarningAmber
import it.prime.protocollo17.presentation.util.FailureMessages

/**
 * Finestra Opzioni (§6.11): sezioni filtrabili per parola chiave (stile impostazioni di LocalDesktopAi), salvataggio
 * automatico, switch U4/U5 in evidenza con la loro conseguenza spiegata. I campi numerici mostrano il testo in
 * modifica e il suo errore; il valore salvato resta quello valido precedente.
 */
@Composable
fun OptionsScreen(
    state: OptionsUiState,
    onAction: (OptionsAction) -> Unit,
    onOpenDataFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmReset by remember { mutableStateOf(false) }
    val profileName = state.resolution?.profile?.displayName
        ?: TerminalProfiles.all.firstOrNull { it.id.name == state.settings.terminal.profile }?.displayName
        ?: state.settings.terminal.profile
    fun show(vararg keywords: String): Boolean = matchesFilter(state.filter, keywords)

    Column(modifier.fillMaxSize()) {
        TopBar(state.filter, profileName, onAction, onRequestReset = { confirmReset = true })
        HorizontalDivider()
        ScrollableColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (state.commandRunning) {
                Notice("Un comando è in corso: le modifiche valgono dal prossimo comando.", WarningAmber)
            }
            if (show(*TERMINAL_KEYWORDS)) TerminalSection(state, onAction)
            if (show(*BEHAVIOR_KEYWORDS)) BehaviorSection(state.settings.protocol, onAction)
            if (show(*TIMING_KEYWORDS)) TimingSection(state, onAction)
            if (show(*LRC_KEYWORDS)) LrcSection(state, onAction)
            if (show(*RECEIPT_KEYWORDS)) {
                SettingsCard(title = "Scontrini") {
                    SwitchRow(
                        checked = state.settings.protocol.ecrPrinting,
                        onCheckedChange = { onAction(OptionsAction.SetOption(OptionKey.ECR_PRINTING, it.toString())) },
                        title = "Stampa su ECR",
                        hint = "Invia E1 prima di ogni procedura e attende le righe S dello scontrino.",
                    )
                }
            }
            if (show(*LOG_KEYWORDS)) LogSection(state, onAction, onOpenDataFolder)
            if (show(*APPEARANCE_KEYWORDS)) AppearanceSection(state.settings, onAction)
            if (state.filter.isNotBlank() && ALL_KEYWORDS.none { matchesFilter(state.filter, it) }) {
                Text("Nessuna sezione corrisponde a «${state.filter}».", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SelectionContainer {
                Text(
                    "Le impostazioni vengono salvate automaticamente in ${state.settingsPath}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Ripristinare i valori del profilo $profileName?") },
            text = { Text("Tutte le opzioni di protocollo (LRC, tempi, ritrasmissioni, switch) tornano ai valori del profilo. Terminale, porta e aspetto non cambiano.") },
            confirmButton = {
                Button(onClick = {
                    confirmReset = false
                    onAction(OptionsAction.ResetToProfile)
                }) { Text("Ripristina") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Annulla") } },
        )
    }
}

@Composable
private fun TopBar(filter: String, profileName: String, onAction: (OptionsAction) -> Unit, onRequestReset: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Opzioni", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        OutlinedTextField(
            value = filter,
            onValueChange = { onAction(OptionsAction.SetFilter(it)) },
            singleLine = true,
            placeholder = { Text("Filtra…", style = MaterialTheme.typography.bodySmall) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
            trailingIcon = {
                if (filter.isNotEmpty()) {
                    TooltipIconButton(icon = Icons.Filled.Close, tooltip = "Azzera filtro", onClick = { onAction(OptionsAction.SetFilter("")) }, iconSize = 16.dp)
                }
            },
            textStyle = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(220.dp),
        )
        Spacer(Modifier.width(12.dp))
        OutlinedButton(onClick = onRequestReset) { Text("Ripristina valori del profilo $profileName") }
    }
}

// ─── Sezioni ──────────────────────────────────────────────────────────────────────

@Composable
private fun TerminalSection(state: OptionsUiState, onAction: (OptionsAction) -> Unit) {
    val terminal = state.settings.terminal
    val profile = TerminalProfiles.all.firstOrNull { it.id.name == terminal.profile }
    SettingsCard(title = "Terminale") {
        TerminalProfiles.all.forEach { p ->
            Row(
                modifier = Modifier.fillMaxWidth().selectable(
                    selected = p.id.name == terminal.profile,
                    role = Role.RadioButton,
                    onClick = { onAction(OptionsAction.SetProfile(p.id)) },
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = p.id.name == terminal.profile, onClick = null)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (p.evidence == Evidence.CAPTURE) "OSSERVATO" else "IPOTESI",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (p.evidence == Evidence.CAPTURE) MaterialTheme.colorScheme.primary else WarningAmber,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Text(p.notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = state.hostText,
                onValueChange = { onAction(OptionsAction.SetHost(it)) },
                singleLine = true,
                label = { Text("Indirizzo IP o nome") },
                isError = state.hostError != null,
                supportingText = state.hostError?.let { { Text(it) } },
                modifier = Modifier.weight(2f),
            )
            OutlinedTextField(
                value = state.portText,
                onValueChange = { onAction(OptionsAction.SetPort(it)) },
                singleLine = true,
                label = { Text("Porta") },
                placeholder = { Text(profile?.defaultPort?.let { "$it (profilo)" } ?: "obbligatoria") },
                isError = state.portError != null,
                supportingText = state.portError?.let { { Text(it) } },
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = state.idTexts["terminalId"] ?: terminal.terminalId,
                onValueChange = { onAction(OptionsAction.SetTerminalId(it)) },
                singleLine = true,
                label = { Text("Terminal ID") },
                isError = state.idErrors["terminalId"] != null,
                supportingText = { Text(state.idErrors["terminalId"] ?: "8 cifre: 00000000 = qualsiasi terminale") },
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = state.idTexts["cashRegisterId"] ?: terminal.cashRegisterId,
                onValueChange = { onAction(OptionsAction.SetCashRegisterId(it)) },
                singleLine = true,
                label = { Text("ECR id (cassa)") },
                isError = state.idErrors["cashRegisterId"] != null,
                supportingText = { Text(state.idErrors["cashRegisterId"] ?: "Default dei comandi; il form può sovrascriverlo") },
                modifier = Modifier.weight(1f),
            )
        }
        state.resolution?.let { resolution ->
            resolution.issues.forEach { Notice(it.message, MaterialTheme.colorScheme.error) }
            resolution.problems.forEach { Notice(FailureMessages.forConfigProblem(it), MaterialTheme.colorScheme.error) }
            resolution.warnings.forEach { Notice(it.message, WarningAmber) }
        }
    }
}

@Composable
private fun BehaviorSection(protocol: ProtocolSettings, onAction: (OptionsAction) -> Unit) {
    SettingsCard(title = "Comportamento") {
        SwitchRow(
            checked = protocol.financialAckPolicy == "IMPLICIT_GO_AHEAD",
            onCheckedChange = { onAction(OptionsAction.SetOption(OptionKey.FINANCIAL_ACK_POLICY, if (it) "IMPLICIT_GO_AHEAD" else "RETRANSMIT")) },
            title = "Go-ahead implicito sui comandi finanziari",
            hint = "Se l'ACK non arriva entro il timeout NON reinvia il pagamento e attende l'esito, come la DLL Ingenico. " +
                "Disattivato: ritrasmette fino a N volte come i repo (rischio di doppio addebito; l'esito sarà da verificare).",
        )
        SwitchRow(
            checked = protocol.connectionMode == "PER_COMMAND",
            onCheckedChange = { onAction(OptionsAction.SetOption(OptionKey.CONNECTION_MODE, if (it) "PER_COMMAND" else "PERSISTENT")) },
            title = "Una connessione TCP per comando",
            hint = "Apre e chiude il socket a ogni operazione, come la DLL. Disattivato: connessione persistente.",
        )
        SwitchRow(
            checked = protocol.autoReconnectSafeCommands,
            onCheckedChange = { onAction(OptionsAction.SetOption(OptionKey.AUTO_RECONNECT_SAFE_COMMANDS, it.toString())) },
            title = "Ripeti una volta Stato/Totali/G/E dopo una caduta",
            hint = "Solo i comandi che non muovono denaro: un pagamento non viene mai ripetuto dopo una caduta.",
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimingSection(state: OptionsUiState, onAction: (OptionsAction) -> Unit) {
    val goAhead = state.settings.protocol.financialAckPolicy == "IMPLICIT_GO_AHEAD"
    @Composable
    fun field(key: OptionKey, label: String, suffix: String? = "ms", hint: String? = null, modifier: Modifier = Modifier) {
        NumberTextField(
            label = label,
            text = state.texts[key] ?: ConfigResolver.textOf(state.settings.protocol, key),
            onTextChange = { onAction(OptionsAction.SetOption(key, it)) },
            suffix = suffix,
            error = state.fieldErrors[key],
            hint = hint,
            modifier = modifier.width(230.dp),
        )
    }
    SettingsCard(title = "Tempi e ritrasmissioni") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            field(OptionKey.ACK_TIMEOUT_MS, "Timeout ACK", hint = "Default 2000")
            field(OptionKey.RESPONSE_TIMEOUT_MS, "Timeout esito", hint = "Default 120000 (mai esteso)")
            field(OptionKey.CONNECT_TIMEOUT_MS, "Timeout connessione", hint = "Default 5000")
            field(OptionKey.POST_RESULT_DRAIN_MS, "Drain post-esito", hint = "Silenzio dopo l'esito, default 100")
            field(OptionKey.RECEIPT_DRAIN_MS, "Drain scontrini", hint = "Con righe S attese, default 1500")
            field(OptionKey.PRE_SEND_QUIET_MS, "Attesa pre-invio", hint = "Raccoglie byte stantii, default 50")
            field(OptionKey.WRITE_TIMEOUT_MS, "Timeout scrittura", hint = "Default 5000")
            field(OptionKey.RETRY_DELAY_MS, "Pausa tra ritrasmissioni", hint = "Default 200")
            field(
                OptionKey.NAK_RETRY_COUNT, "Reinvii su NAK", suffix = null,
                hint = "Il terminale ha rifiutato il messaggio: sempre attivo, default 3",
            )
            field(
                OptionKey.ACK_TIMEOUT_RETRANSMIT_COUNT, "Reinvii su ACK mancante", suffix = null,
                hint = if (goAhead) "Con il go-ahead implicito: solo comandi di servizio, non applicabile ai finanziari" else
                    "Solo con 'Go-ahead implicito' disattivato e per i comandi di servizio",
                modifier = Modifier.alpha(if (goAhead) 0.6f else 1f),
            )
        }
    }
}

@Composable
private fun LrcSection(state: OptionsUiState, onAction: (OptionsAction) -> Unit) {
    val protocol = state.settings.protocol
    SettingsCard(title = "LRC e controllo") {
        EnumDropdown(
            label = "LRC applicativo (esempio: LRC del frame s)",
            options = listOf(
                "std" to "std (s: 3C)", "stx" to "stx (s: 3D) — Ingenico",
                "noext" to "noext (s: 3F)", "stx_noext" to "stx_noext (s: 3E)",
            ),
            selected = protocol.appLrcMode,
            onSelect = { onAction(OptionsAction.SetOption(OptionKey.APP_LRC_MODE, it)) },
            modifier = Modifier.width(320.dp),
        )
        Text("ACK/NAK in uscita", style = MaterialTheme.typography.labelLarge)
        listOf("WIRE" to "06 03 7A (come DLL/Ingenico)", "SAME_AS_APP" to "06 03 78 (come i repo padosoft)").forEach { (value, label) ->
            Row(
                modifier = Modifier.selectable(
                    selected = protocol.controlLrcTx == value,
                    role = Role.RadioButton,
                    onClick = { onAction(OptionsAction.SetOption(OptionKey.CONTROL_LRC_TX, value)) },
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = protocol.controlLrcTx == value, onClick = null)
                Spacer(Modifier.width(8.dp))
                Text(label, style = MaterialTheme.typography.bodyMedium)
            }
        }
        SelectionContainer { Text("Anteprima: ${state.controlPreview}", style = MaterialTheme.typography.bodySmall) }
        SwitchRow(
            checked = protocol.controlLrcRx == "STRICT",
            onCheckedChange = { onAction(OptionsAction.SetOption(OptionKey.CONTROL_LRC_RX, if (it) "STRICT" else "TOLERANT")) },
            title = "ACK in ingresso: LRC rigido",
            hint = "Spento = tollerante con avviso nel log. Un NAK ambiguo non provoca mai un reinvio.",
        )
        SwitchRow(
            checked = protocol.appLrcRx == "ACCEPT_ANY_MODE",
            onCheckedChange = { onAction(OptionsAction.SetOption(OptionKey.APP_LRC_RX, if (it) "ACCEPT_ANY_MODE" else "STRICT")) },
            title = "Frame applicativi: accetta LRC valido in un'altra modalità",
            hint = "Con avviso nel log; mai per gli esiti dei pagamenti e di G.",
        )
    }
}

@Composable
private fun LogSection(state: OptionsUiState, onAction: (OptionsAction) -> Unit, onOpenDataFolder: () -> Unit) {
    var retention by remember(state.settings.traffic.retentionDays) { mutableStateOf(state.settings.traffic.retentionDays.toString()) }
    SettingsCard(title = "Log e journal") {
        SwitchRow(
            checked = state.settings.traffic.fileEnabled,
            onCheckedChange = { onAction(OptionsAction.SetTrafficFileEnabled(it)) },
            title = "Scrivi il log traffico su file",
            hint = "Un file al giorno in traffic/, nello stesso formato del log della DLL.",
        )
        NumberTextField(
            label = "Giorni di conservazione del log",
            text = retention,
            onTextChange = {
                retention = it
                onAction(OptionsAction.SetTrafficRetentionDays(it))
            },
            suffix = "giorni",
            modifier = Modifier.width(260.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onOpenDataFolder) {
                Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Apri cartella dati")
            }
            Spacer(Modifier.width(12.dp))
            SelectionContainer { Text(state.dataFolder, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun AppearanceSection(settings: AppSettings, onAction: (OptionsAction) -> Unit) {
    SettingsCard(title = "Aspetto") {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            listOf(ThemeMode.System to "Sistema", ThemeMode.Light to "Chiaro", ThemeMode.Dark to "Scuro").forEach { (mode, label) ->
                Row(
                    modifier = Modifier.selectable(
                        selected = settings.ui.themeMode == mode,
                        role = Role.RadioButton,
                        onClick = { onAction(OptionsAction.SetTheme(mode)) },
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = settings.ui.themeMode == mode, onClick = null)
                    Spacer(Modifier.width(6.dp))
                    Text(label)
                }
            }
        }
        SwitchRow(
            checked = settings.ui.confirmCloseSession,
            onCheckedChange = { onAction(OptionsAction.SetConfirmCloseSession(it)) },
            title = "Chiedi conferma prima della chiusura contabile",
            hint = "La chiusura contabile azzera i totali del terminale e non è ripetibile.",
        )
    }
}

@Composable
private fun Notice(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.12f), border = BorderStroke(1.dp, color), shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
        SelectionContainer { Text(text, modifier = Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall) }
    }
}

// ─── Filtro ───────────────────────────────────────────────────────────────────────

/** Una sezione resta visibile se il filtro è vuoto o compare in una delle sue parole chiave (sottostringa). */
internal fun matchesFilter(filter: String, keywords: Array<out String>): Boolean {
    val query = filter.trim().lowercase()
    if (query.isEmpty()) return true
    return keywords.any { query in it.lowercase() }
}

private val TERMINAL_KEYWORDS = arrayOf("terminale", "profilo", "ingenico", "ccv", "host", "indirizzo", "ip", "porta", "terminal id", "tid", "ecr id", "cassa")
private val BEHAVIOR_KEYWORDS = arrayOf("comportamento", "go-ahead", "implicito", "ack", "ritrasmissione", "connessione", "persistente", "per comando", "caduta", "ripeti")
private val TIMING_KEYWORDS = arrayOf("tempi", "timeout", "ack", "esito", "drain", "attesa", "scrittura", "ritrasmissioni", "reinvii", "nak", "pausa")
private val LRC_KEYWORDS = arrayOf("lrc", "std", "stx", "noext", "controllo", "ack", "nak", "wire", "7a", "78", "rigido")
private val RECEIPT_KEYWORDS = arrayOf("scontrini", "scontrino", "stampa", "ecr", "e1", "righe s")
private val LOG_KEYWORDS = arrayOf("log", "traffico", "journal", "file", "conservazione", "giorni", "cartella", "dati")
private val APPEARANCE_KEYWORDS = arrayOf("aspetto", "tema", "chiaro", "scuro", "sistema", "conferma", "chiusura contabile")
private val ALL_KEYWORDS: List<Array<String>> = listOf(
    TERMINAL_KEYWORDS, BEHAVIOR_KEYWORDS, TIMING_KEYWORDS, LRC_KEYWORDS, RECEIPT_KEYWORDS, LOG_KEYWORDS, APPEARANCE_KEYWORDS,
)

// ─── Preview ──────────────────────────────────────────────────────────────────────

/** Testi di tutti i campi numerici: l'anteprima non deve chiamare ConfigResolver (WP5). */
private fun previewTexts(p: ProtocolSettings): Map<OptionKey, String> = mapOf(
    OptionKey.ACK_TIMEOUT_MS to p.ackTimeoutMs.toString(), OptionKey.RESPONSE_TIMEOUT_MS to p.responseTimeoutMs.toString(),
    OptionKey.CONNECT_TIMEOUT_MS to p.connectTimeoutMs.toString(), OptionKey.POST_RESULT_DRAIN_MS to p.postResultDrainMs.toString(),
    OptionKey.RECEIPT_DRAIN_MS to p.receiptDrainMs.toString(), OptionKey.PRE_SEND_QUIET_MS to p.preSendQuietMs.toString(),
    OptionKey.WRITE_TIMEOUT_MS to p.writeTimeoutMs.toString(), OptionKey.RETRY_DELAY_MS to p.retryDelayMs.toString(),
    OptionKey.NAK_RETRY_COUNT to p.nakRetryCount.toString(), OptionKey.ACK_TIMEOUT_RETRANSMIT_COUNT to p.ackTimeoutRetransmitCount.toString(),
)

@Suppress("unused")
@Preview
@Composable
private fun OptionsScreenPreview() {
    val settings = AppSettings()
    PreviewTheme {
        Box(Modifier.size(760.dp, 720.dp)) {
            OptionsScreen(
                state = OptionsUiState(
                    settings = settings,
                    texts = previewTexts(settings.protocol) + (OptionKey.ACK_TIMEOUT_MS to "abc"),
                    fieldErrors = mapOf(OptionKey.ACK_TIMEOUT_MS to "Timeout ACK: valore tra 100 e 30000 ms"),
                    hostText = settings.terminal.host, portText = "", idTexts = mapOf("terminalId" to "12AB"),
                    idErrors = mapOf("terminalId" to "Terminal ID: da 1 a 8 cifre (es. 00000000)"),
                    settingsPath = "C:\\Users\\utente\\.protocollo17\\settings.json", dataFolder = "C:\\Users\\utente\\.protocollo17",
                    commandRunning = true,
                ),
                onAction = {},
                onOpenDataFolder = {},
            )
        }
    }
}
