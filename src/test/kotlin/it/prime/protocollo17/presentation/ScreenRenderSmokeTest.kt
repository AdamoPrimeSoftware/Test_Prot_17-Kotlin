package it.prime.protocollo17.presentation

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import it.prime.protocollo17.domain.model.command.FieldKey
import it.prime.protocollo17.domain.model.settings.AppSettings
import it.prime.protocollo17.domain.model.settings.OptionKey
import it.prime.protocollo17.domain.model.settings.ProtocolSettings
import it.prime.protocollo17.domain.model.simulator.SimLogEntry
import it.prime.protocollo17.domain.model.simulator.SimStats
import it.prime.protocollo17.domain.model.simulator.SimulatorFailure
import it.prime.protocollo17.domain.model.simulator.SimulatorStatus
import it.prime.protocollo17.domain.model.traffic.TrafficEntry
import it.prime.protocollo17.domain.model.traffic.TrafficKind
import it.prime.protocollo17.domain.model.traffic.TrafficSeverity
import it.prime.protocollo17.domain.util.RequestFactory
import it.prime.protocollo17.ecr17.model.CommandCode
import it.prime.protocollo17.ecr17.model.ExchangePhase
import it.prime.protocollo17.presentation.model.ResultViewMapper
import it.prime.protocollo17.presentation.screen.console.view.ConsoleScreen
import it.prime.protocollo17.domain.util.GAssessmentResult
import it.prime.protocollo17.domain.util.GVerdict
import it.prime.protocollo17.presentation.components.ResolveDialogModel
import it.prime.protocollo17.presentation.screen.console.viewModel.ConsoleDialog
import it.prime.protocollo17.presentation.screen.console.viewModel.ConsoleUiState
import it.prime.protocollo17.presentation.screen.console.viewModel.ResultTab
import it.prime.protocollo17.presentation.screen.console.viewModel.UnresolvedView
import it.prime.protocollo17.presentation.screen.journal.view.JournalScreen
import it.prime.protocollo17.presentation.screen.journal.viewModel.JournalDetailView
import it.prime.protocollo17.presentation.screen.journal.viewModel.JournalRowView
import it.prime.protocollo17.presentation.screen.journal.viewModel.JournalUiState
import it.prime.protocollo17.presentation.screen.options.view.OptionsScreen
import it.prime.protocollo17.presentation.screen.options.viewModel.OptionsUiState
import it.prime.protocollo17.presentation.screen.simulator.view.SimulatorScreen
import it.prime.protocollo17.presentation.screen.simulator.viewModel.SimulatorUiState
import it.prime.protocollo17.presentation.screen.traffic.view.TrafficLogScreen
import it.prime.protocollo17.presentation.screen.traffic.viewModel.TrafficLogUiState
import it.prime.protocollo17.presentation.model.FieldRow
import it.prime.protocollo17.presentation.model.ResultTone
import it.prime.protocollo17.presentation.theme.PreviewTheme
import org.junit.jupiter.api.Test

/**
 * Le schermate pure si compongono e si misurano senza eccezioni alla dimensione minima delle finestre e a quella di
 * default, in chiaro e in scuro. Un errore di layout (uno scroll verticale annidato senza altezza, una LazyColumn in
 * una colonna scrollabile) a schermo diventa una finestra bianca: qui diventa un test rosso.
 */
class ScreenRenderSmokeTest {

    private fun render(widthDp: Int, heightDp: Int, dark: Boolean = false, content: @Composable () -> Unit) {
        // Densità 1.5: una finestra di 960×600 dp al 150% (§6.1).
        val density = Density(1.5f)
        val scene = ImageComposeScene(width = (widthDp * 1.5f).toInt(), height = (heightDp * 1.5f).toInt(), density = density) {
            PreviewTheme(darkTheme = dark) { content() }
        }
        try {
            scene.render()
            scene.render(nanoTime = 600_000_000)
        } finally {
            scene.close()
        }
    }

    private val payment = ResultViewMapper.map(UiFixtures.finished(UiFixtures.answered(CommandCode.PAYMENT, UiFixtures.payment())))

    private val consoleState = ConsoleUiState(
        drafts = CommandCode.entries.associateWith { RequestFactory.defaults(it) },
        results = mapOf(CommandCode.PAYMENT to payment),
        history = listOf(ResultViewMapper.historyRow(UiFixtures.finished(UiFixtures.answered(CommandCode.PAYMENT, UiFixtures.payment())))),
    )

    @Test
    fun `console affiancata, impilata, occupata e con il banner`() {
        render(1280, 800) { ConsoleScreen(consoleState, onAction = {}, onOpenWindow = {}) }
        render(960, 600, dark = true) {
            ConsoleScreen(
                consoleState.copy(
                    unresolved = List(4) { UnresolvedView("a$it", "Pagamento 0,10 €", "14/10 09:35:0$it", "nessun esito entro 120 s", false, true) },
                    running = UiFixtures.running(CommandCode.PAYMENT, ExchangePhase.AWAITING_RESULT),
                    fieldErrors = mapOf(FieldKey.AMOUNT to "Inserisci un importo (es. 6,50)"),
                ),
                onAction = {},
                onOpenWindow = {},
            )
        }
        render(960, 600) { ConsoleScreen(consoleState.copy(resultTab = ResultTab.HISTORY), onAction = {}, onOpenWindow = {}) }
    }

    /** I dialog di risoluzione (con e senza G) e di blocco si compongono anche alla finestra minima. */
    @Test
    fun `dialog di risoluzione e di blocco`() {
        val withG = ResolveDialogModel(
            attemptId = "a1", attemptTitle = "Pagamento 0,10 €", attemptTime = "14/10 09:35:05", reason = "nessun esito entro 120 s",
            reviewOnly = false, gResult = payment, assessment = GAssessmentResult(GVerdict.LIKELY_THIS_ATTEMPT, "spiegazione"),
            evidence = listOf(it.prime.protocollo17.ecr17.testing.Frames.E22), otherUnresolved = 2,
        )
        render(960, 600) { ConsoleScreen(consoleState.copy(dialog = ConsoleDialog.Resolve(withG)), onAction = {}, onOpenWindow = {}) }
        render(960, 600) {
            ConsoleScreen(consoleState.copy(dialog = ConsoleDialog.Resolve(withG.copy(gResult = null, assessment = null, reviewOnly = true))), onAction = {}, onOpenWindow = {})
        }
        render(960, 600) {
            ConsoleScreen(
                consoleState.copy(dialog = ConsoleDialog.BlockedInfo(listOf(UnresolvedView("a1", "Pagamento 0,10 €", "14/10 09:35:05", "motivo", false, true)))),
                onAction = {},
                onOpenWindow = {},
            )
        }
    }

    @Test
    fun `opzioni log journal e simulatore alla dimensione minima`() {
        val settings = AppSettings()
        render(560, 420) {
            OptionsScreen(
                OptionsUiState(settings = settings, texts = allTexts(settings.protocol), hostText = settings.terminal.host),
                onAction = {},
                onOpenDataFolder = {},
            )
        }
        val entries = List(50) {
            TrafficEntry(it.toLong(), 1_760_000_000_000 + it, 1, if (it % 2 == 0) TrafficKind.TX else TrafficKind.WARNING, 'P', "02 30 03 2F", "<STX>0<ETX>/", "voce $it", TrafficSeverity.NORMAL)
        }
        render(640, 420) { TrafficLogScreen(TrafficLogUiState(visible = entries, totalInMemory = 50), onAction = {}, onExport = {}, onOpenFolder = {}) }
        render(720, 420) {
            JournalScreen(
                JournalUiState(
                    rows = List(20) { JournalRowView("a$it", "14/10 09:35:05", 'P', "Pagamento 0,10 €", "Esito sconosciuto", ResultTone.WARNING, 1) },
                    selectedId = "a1",
                    detail = JournalDetailView(
                        "a1", "Pagamento 0,10 €", listOf(FieldRow("Stato", "Esito sconosciuto")), listOf(FieldRow("creato", "x", "#1")),
                        null, emptyList(), isUnresolved = true, needsReview = false, blocksPayments = true, persisted = true, json = "{}",
                    ),
                    unresolvedCount = 20,
                ),
                onAction = {},
                onOpenFolder = {},
            )
        }
        render(520, 420) {
            SimulatorScreen(
                SimulatorUiState(
                    status = SimulatorStatus.Failed(SimulatorFailure.PortInUse(15577)),
                    stats = SimStats(requests = 3),
                    log = List(30) { SimLogEntry(it.toLong(), 1_760_000_000_000, "IN", "richiesta", "02 30") },
                ),
                onAction = {},
            )
        }
    }

    private fun allTexts(p: ProtocolSettings): Map<OptionKey, String> = mapOf(
        OptionKey.ACK_TIMEOUT_MS to p.ackTimeoutMs.toString(), OptionKey.RESPONSE_TIMEOUT_MS to p.responseTimeoutMs.toString(),
        OptionKey.CONNECT_TIMEOUT_MS to p.connectTimeoutMs.toString(), OptionKey.POST_RESULT_DRAIN_MS to p.postResultDrainMs.toString(),
        OptionKey.RECEIPT_DRAIN_MS to p.receiptDrainMs.toString(), OptionKey.PRE_SEND_QUIET_MS to p.preSendQuietMs.toString(),
        OptionKey.WRITE_TIMEOUT_MS to p.writeTimeoutMs.toString(), OptionKey.RETRY_DELAY_MS to p.retryDelayMs.toString(),
        OptionKey.NAK_RETRY_COUNT to p.nakRetryCount.toString(), OptionKey.ACK_TIMEOUT_RETRANSMIT_COUNT to p.ackTimeoutRetransmitCount.toString(),
    )
}
