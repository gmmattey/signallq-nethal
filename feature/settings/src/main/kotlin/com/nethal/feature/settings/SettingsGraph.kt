package com.nethal.feature.settings

import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.nethal.core.navigation.AdvancedToolDestination
import com.nethal.core.navigation.BottomNavDestination

/**
 * Rotas do fluxo de Configurações. A raiz usa [BottomNavDestination.SETTINGS] (contrato
 * compartilhado em `:core:navigation`, consumido pelo `BottomNavHost` em `:app`); a rota de
 * privacidade é interna a este módulo — nenhum outro `:feature:*` navega direto para ela (regra de
 * dependência única, ADR 0002). Se o botão "Ver privacidade" da tela de Boas-vindas (#68) precisar
 * apontar para cá, isso se resolve no composition root (`:app`), não por dependência cruzada entre
 * módulos de feature.
 */
private object SettingsRoutes {
    val ROOT = BottomNavDestination.SETTINGS.route
    const val PRIVACY = "home/settings/privacy"
}

/**
 * Ponto de entrada do módulo `:feature:settings` (issue #85) — o `BottomNavHost` (`:app`) monta
 * este grafo dentro do seu próprio `NavHost`, sem saber do que ele é feito por dentro.
 *
 * `appVersionLabel` vem do composition root (`BuildConfig.VERSION_NAME`/`VERSION_CODE` de `:app`,
 * o único módulo que tem acesso a isso) — este módulo não inventa número de versão.
 */
fun NavGraphBuilder.settingsGraph(
    navController: NavHostController,
    viewModelFactory: ViewModelProvider.Factory,
    appVersionLabel: String,
) {
    composable(SettingsRoutes.ROOT) {
        val viewModel: SettingsViewModel = viewModel(factory = viewModelFactory)
        // Rota registrada de fato no NavHost compartilhado (mesmo `NavHostController` do
        // `BottomNavHost`, `:app`) — computado uma vez por composição da tela raiz, não a cada
        // recomposição de linha. Issue #136: nenhuma entrada de "Ferramentas avançadas" aparece
        // aqui antes de o composition root registrar o grafo `:feature:tools-ping` de verdade
        // (`toolsPingGraph`, fora do escopo desta entrega) — sem link morto, sem hardcode das 7
        // entradas do protótipo de uma vez.
        val availableTools = remember(navController) {
            AdvancedToolDestination.entries.filter { navController.graph.findNode(it.route) != null }
        }
        SettingsScreen(
            viewModel = viewModel,
            appVersionLabel = appVersionLabel,
            availableTools = availableTools,
            onOpenPrivacy = { navController.navigate(SettingsRoutes.PRIVACY) },
            onOpenTool = { route -> navController.navigate(route) },
        )
    }

    composable(SettingsRoutes.PRIVACY) {
        SettingsPrivacyScreen(onBack = { navController.popBackStack() })
    }
}
