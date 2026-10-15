package it.prime.protocollo17.presentation.screen.main

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.koin.koinScreenModel
import it.prime.protocollo17.presentation.screen.console.view.ConsoleScreen
import it.prime.protocollo17.presentation.screen.console.view.ConsoleScreenRoot
import it.prime.protocollo17.presentation.screen.console.viewModel.ConsoleUiState
import it.prime.protocollo17.presentation.screen.console.viewModel.ConsoleViewModel
import it.prime.protocollo17.presentation.theme.PreviewTheme

/**
 * Schermata radice della finestra principale (§6.17). Voyager serve solo al ciclo di vita del ConsoleViewModel:
 * non c'è navigazione a stack, le "pagine" secondarie sono finestre vere. La chiave è unica tra le finestre,
 * così lo ScreenModelStore non confonde i modelli di finestre diverse.
 */
class MainScreen : Screen {
    override val key: ScreenKey = "screen-main"

    @Composable
    override fun Content() {
        val viewModel: ConsoleViewModel = koinScreenModel()
        ConsoleScreenRoot(viewModel = viewModel, modifier = Modifier.fillMaxSize())
    }
}

@Suppress("unused")
@Preview
@Composable
private fun MainScreenPreview() {
    // MainScreen risolve il ViewModel con Koin: l'anteprima mostra la Console pura nello stato iniziale.
    PreviewTheme { Box(Modifier.size(1280.dp, 800.dp)) { ConsoleScreen(state = ConsoleUiState(), onAction = {}, onOpenWindow = {}) } }
}
