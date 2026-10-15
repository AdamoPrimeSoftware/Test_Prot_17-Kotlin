package it.prime.protocollo17.presentation.screen.console.view

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import it.prime.protocollo17.domain.model.simulator.SimulatorStatus
import it.prime.protocollo17.domain.util.RequestFactory
import it.prime.protocollo17.ecr17.model.CommandCode
import it.prime.protocollo17.ecr17.model.ExchangePhase
import it.prime.protocollo17.ecr17.model.StatusRequest
import it.prime.protocollo17.presentation.components.ScrollableColumn
import it.prime.protocollo17.presentation.model.FieldRow
import it.prime.protocollo17.presentation.model.HistoryRowView
import it.prime.protocollo17.presentation.model.ResultTone
import it.prime.protocollo17.presentation.model.ResultView
import it.prime.protocollo17.presentation.screen.console.model.CommandCatalog
import it.prime.protocollo17.presentation.screen.console.view.components.BusyOverlay
import it.prime.protocollo17.presentation.screen.console.view.components.CommandFormCard
import it.prime.protocollo17.presentation.screen.console.view.components.CommandSidebar
import it.prime.protocollo17.presentation.screen.console.view.components.ConnectionHeader
import it.prime.protocollo17.presentation.screen.console.view.components.ConsoleDialogs
import it.prime.protocollo17.presentation.screen.console.view.components.HistoryPanel
import it.prime.protocollo17.presentation.screen.console.view.components.ResultPanel
import it.prime.protocollo17.presentation.screen.console.view.components.UnresolvedBanner
import it.prime.protocollo17.presentation.screen.console.viewModel.ConsoleAction
import it.prime.protocollo17.presentation.screen.console.viewModel.ConsoleUiState
import it.prime.protocollo17.presentation.screen.console.viewModel.LinkTone
import it.prime.protocollo17.presentation.screen.console.viewModel.LinkView
import it.prime.protocollo17.presentation.screen.console.viewModel.ResultTab
import it.prime.protocollo17.presentation.screen.console.viewModel.SimulatorView
import it.prime.protocollo17.presentation.screen.console.viewModel.UnresolvedView
import it.prime.protocollo17.presentation.state.AppWindowId
import it.prime.protocollo17.presentation.state.RunOrigin
import it.prime.protocollo17.presentation.state.RunningCommand
import it.prime.protocollo17.presentation.theme.PreviewTheme

/** Sotto questa larghezza del contenuto form ed esito si impilano (finestra minima 960 dp − sidebar 232 dp, §6.2). */
private val SideBySideMinWidth = 1000.dp

/**
 * Finestra principale (§6.2): intestazione di connessione, sidebar dei comandi, banner dei tentativi da risolvere
 * e i due pannelli form | esito (impilati a larghezza ridotta), con l'overlay di occupato sopra il contenuto.
 *
 * Pura: (state, onAction) e la lambda per aprire le finestre (il ViewModel non conosce le finestre, §6.5).
 */
@Composable
fun ConsoleScreen(
    state: ConsoleUiState,
    onAction: (ConsoleAction) -> Unit,
    onOpenWindow: (AppWindowId) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        ConnectionHeader(
            connection = state.connection,
            simulator = state.simulator,
            unresolvedCount = state.unresolved.size,
            barActionsEnabled = state.barActionsEnabled,
            onAction = onAction,
            onOpenWindow = onOpenWindow,
        )
        HorizontalDivider()
        Row(Modifier.weight(1f).fillMaxWidth()) {
            CommandSidebar(
                selected = state.selected,
                blocked = state.blockedCommands,
                enabled = !state.busy,
                onSelect = { onAction(ConsoleAction.Select(it)) },
            )
            VerticalDivider()
            Box(Modifier.weight(1f).fillMaxHeight()) {
                Column(Modifier.fillMaxSize().padding(12.dp)) {
                    UnresolvedBanner(
                        unresolved = state.unresolved,
                        paymentsBlocked = state.paymentsBlocked,
                        blockedCommands = state.blockedCommands,
                        canRecoverWithG = state.canRecoverWithG,
                        onAction = onAction,
                        onOpenJournal = { onOpenWindow(AppWindowId.JOURNAL) },
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                        if (maxWidth >= SideBySideMinWidth) {
                            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Form(state, onAction, Modifier.weight(1f).fillMaxHeight(), scrollable = true)
                                ResultTabs(state, onAction, Modifier.weight(1f).fillMaxHeight(), scrollable = true)
                            }
                        } else {
                            ScrollableColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(end = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Form(state, onAction, Modifier.fillMaxWidth(), scrollable = false)
                                ResultTabs(state, onAction, Modifier.fillMaxWidth().height(560.dp), scrollable = true)
                            }
                        }
                    }
                }
                state.running?.let { running ->
                    BusyOverlay(running = running, endpoint = state.connection.endpoint, onCancel = { onAction(ConsoleAction.RequestCancel) })
                }
            }
        }
    }
    ConsoleDialogs(state.dialog, onAction)
}

@Composable
private fun Form(state: ConsoleUiState, onAction: (ConsoleAction) -> Unit, modifier: Modifier, scrollable: Boolean) {
    val def = CommandCatalog.of(state.selected)
    CommandFormCard(
        def = def,
        values = state.drafts[state.selected] ?: RequestFactory.defaults(state.selected),
        errors = state.fieldErrors,
        canRun = state.canRunSelected,
        blocked = state.isBlocked(state.selected),
        onAction = onAction,
        modifier = modifier,
        scrollable = scrollable,
    )
}

@Composable
private fun ResultTabs(state: ConsoleUiState, onAction: (ConsoleAction) -> Unit, modifier: Modifier, scrollable: Boolean) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        TabRow(selectedTabIndex = state.resultTab.ordinal, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
            Tab(selected = state.resultTab == ResultTab.RESULT, onClick = { onAction(ConsoleAction.SetResultTab(ResultTab.RESULT)) }) {
                Text("Esito", modifier = Modifier.padding(vertical = 10.dp))
            }
            Tab(selected = state.resultTab == ResultTab.HISTORY, onClick = { onAction(ConsoleAction.SetResultTab(ResultTab.HISTORY)) }) {
                Text("Cronologia (${state.history.size})", modifier = Modifier.padding(vertical = 10.dp))
            }
        }
        when (state.resultTab) {
            ResultTab.RESULT -> ResultPanel(
                result = state.viewedHistory ?: state.results[state.selected],
                viewingHistory = state.viewedHistory != null,
                onAction = onAction,
                scrollable = scrollable,
            )
            ResultTab.HISTORY -> HistoryPanel(rows = state.history, historyText = state.historyText, onAction = onAction)
        }
    }
}

// ─── Preview ──────────────────────────────────────────────────────────────────────

private val previewResult = ResultView(
    command = CommandCode.PAYMENT, tone = ResultTone.SUCCESS, headline = "APPROVATO", detail = null,
    fields = listOf(FieldRow("PAN", "***************4768", "13/19"), FieldRow("STAN", "000004", "60/6")),
    exchange = emptyList(), timeline = emptyList(), rawPayload = null, receipt = emptyList(), warnings = emptyList(),
    reviewNotice = null, quickActions = emptyList(), journalError = null, finishedAt = 1_760_000_000_000,
)

private val previewState = ConsoleUiState(
    drafts = CommandCode.entries.associateWith { RequestFactory.defaults(it) },
    results = mapOf(CommandCode.PAYMENT to previewResult),
    history = listOf(HistoryRowView(1, "09:35:14", 'P', true, "APPROVATO", ResultTone.SUCCESS, "0,01 €")),
    simulator = SimulatorView(SimulatorStatus.Running(15577), isTarget = true, label = "Simulatore attivo su 127.0.0.1:15577"),
)

@Suppress("unused")
@Preview
@Composable
private fun ConsoleScreenIdlePreview() {
    PreviewTheme {
        Box(Modifier.size(1280.dp, 800.dp)) { ConsoleScreen(previewState, onAction = {}, onOpenWindow = {}) }
    }
}

@Suppress("unused")
@Preview
@Composable
private fun ConsoleScreenBusyUnknownPreview() {
    val unresolved = listOf(UnresolvedView("a1", "Pagamento 0,10 €", "14/10 09:35:05", "nessun esito entro 120 s", false, true))
    val running = RunningCommand(
        command = CommandCode.STATUS, request = StatusRequest, origin = RunOrigin.CONSOLE,
        attemptId = null, startedAt = 0, phase = ExchangePhase.CONNECTING, phaseAt = 0, sends = 0, implicitGoAhead = false,
        resultWaitStartedAt = null, lastProgress = null, receipt = emptyList(), warnings = emptyList(), cancelRequested = false,
        ackTimeoutMs = 2_000, responseTimeoutMs = 120_000,
    )
    PreviewTheme(darkTheme = true) {
        Box(Modifier.size(960.dp, 600.dp)) {
            ConsoleScreen(
                previewState.copy(
                    unresolved = unresolved,
                    blockedCommands = setOf(CommandCode.PAYMENT, CommandCode.PAYMENT_EXTENDED, CommandCode.REVERSAL),
                    running = running,
                    connection = previewState.connection.copy(link = LinkView(LinkTone.CONNECTING, "Connessione…")),
                ),
                onAction = {}, onOpenWindow = {},
            )
        }
    }
}
