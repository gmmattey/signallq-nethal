package com.nethal.feature.toolstraceroute

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.nethal.feature.toolstraceroute.data.AndroidTracerouteEngine
import com.nethal.feature.toolstraceroute.ui.TracerouteScreen
import com.nethal.feature.toolstraceroute.ui.TracerouteViewModel

/**
 * Rota da tela Traceroute (issue #92/#102, protótipo `4d`). String de rota própria — não existe
 * um destino de bottom nav para "Ferramentas avançadas" (essas telas são navegadas a partir da
 * lista em `SettingsScreen`, protótipo `#3c`→`#4d`).
 *
 * **Não wireada ainda.** `SettingsScreen`/`BottomNavHost` (fora do escopo desta entrega — ver
 * nota em `SettingsScreen.kt` sobre `FERRAMENTAS AVANÇADAS` apontar para módulos `:feature:tools-*`
 * que ainda não existiam) continuam sem link para cá. Quando o composition root (`:app`) passar a
 * depender deste módulo, a linha "Traceroute" da lista "FERRAMENTAS AVANÇADAS" em `SettingsScreen`
 * precisa chamar `navController.navigate(TracerouteRoutes.ROOT)` — só isso, este grafo já está
 * pronto para ser montado dentro do `NavHost` existente do `:app` (mesmo host que hospeda
 * `settingsGraph`), sem precisar de host próprio.
 */
object TracerouteRoutes {
    const val ROOT = "tools/traceroute"
}

/**
 * Auto-contido (mesmo padrão de `devicesGraph`/`settingsGraph`): constrói o próprio
 * [AndroidTracerouteEngine] sem depender de `Context` (a técnica é `ProcessBuilder` puro, não usa
 * nenhuma API de `android.content.Context`) nem de `NetHalViewModelFactory` (que vive em `:app`).
 */
fun NavGraphBuilder.tracerouteGraph(navController: NavHostController) {
    composable(TracerouteRoutes.ROOT) {
        val viewModel: TracerouteViewModel = viewModel(factory = tracerouteViewModelFactory())
        TracerouteScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
    }
}

private fun tracerouteViewModelFactory(): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == TracerouteViewModel::class.java) { "Unknown ViewModel class: $modelClass" }
            return TracerouteViewModel(AndroidTracerouteEngine()) as T
        }
    }
