package com.nethal.feature.toolsspeedtest

import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.nethal.feature.toolsspeedtest.connectivity.hasInternetConnectivity
import com.nethal.feature.toolsspeedtest.engine.CloudflareSpeedtestEngine
import com.nethal.feature.toolsspeedtest.engine.SpeedtestEngine

/**
 * Rota do módulo `:feature:tools-speedtest` (issues #90/#98) — "Configurações → Ferramentas
 * avançadas → Teste de velocidade" (protótipos `4a`/`4b`, `docs/design/prototypes.dc.html`).
 *
 * **Nota para quem consolidar (Rafael/próxima rodada):** este módulo ainda não está linkado em
 * nenhum lugar do app — nem `:feature:settings` (`SettingsScreen`/seção "FERRAMENTAS AVANÇADAS",
 * hoje um gap conhecido documentado no KDoc daquela tela) nem `BottomNavHost` (`:app`) chamam
 * [speedtestGraph]. Faltando: (1) uma entrada `SettingsRow("Teste de velocidade", onClick = { ... })`
 * na seção "FERRAMENTAS AVANÇADAS" de `SettingsScreen.kt` navegando para
 * [SpeedtestRoutes.ROOT], e (2) montar [speedtestGraph] dentro do `NavHost` que já hospeda
 * `settingsGraph` em `:app` (mesmo `NavHostController`, sem precisar de host próprio). Não fiz essa
 * parte porque a task deste PR proibiu tocar `SettingsScreen.kt`/`BottomNavHost` (outro agente,
 * #136, é quem mexe em Configurações nesta rodada).
 *
 * Regra de dependência única da ADR 0002: `:feature:tools-speedtest` depende só de `:core:model`,
 * `:core:navigation`, `:core:designsystem` e `:feature:tools-common` — nunca de outro `:feature:*`.
 */
object SpeedtestRoutes {
    const val ROOT = "tools/speedtest"
}

fun NavGraphBuilder.speedtestGraph(navController: NavHostController) {
    composable(SpeedtestRoutes.ROOT) {
        val appContext = LocalContext.current.applicationContext
        val viewModel: SpeedtestViewModel = viewModel(factory = speedtestViewModelFactory(appContext = appContext))
        SpeedtestScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
    }
}

private fun speedtestViewModelFactory(appContext: Context): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == SpeedtestViewModel::class.java) { "Unknown ViewModel class: $modelClass" }
            val engine: SpeedtestEngine = CloudflareSpeedtestEngine()
            return SpeedtestViewModel(
                engine = engine,
                hasNetworkConnectivity = { hasInternetConnectivity(appContext) },
            ) as T
        }
    }
