package com.nethal.feature.toolsping

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.nethal.core.navigation.AdvancedToolDestination

/**
 * Expõe as rotas [AdvancedToolDestination.PING] e [AdvancedToolDestination.PORT_CHECK] — um único
 * módulo cobrindo as duas telas (decisão registrada: Ping #91/#99 e Verificação de porta #94/#100
 * compartilham a mesma técnica de TCP probe no backend, ver `com.nethal.core.protocol.tcp`, então
 * não faz sentido duas cópias quase idênticas de módulo/build.gradle/manifesto Android só para
 * separar a UI). Quem monta o `NavHost` (`:app`, `BottomNavHost` — fora do escopo desta entrega,
 * ver nota de wiring pendente na issue #136) decide quando registrar este grafo; este módulo não
 * conhece o host (regra de dependência única da ADR 0002 — `:feature:tools-ping` depende só de
 * `:core:*` e `:feature:tools-common`, nunca de outro `:feature:*`).
 *
 * [defaultTargetHost] é o IP do equipamento pareado nesta sessão (mesmo handoff de
 * `capabilityEngine: CapabilityEngine?` em `StatusGraph`) — entregue pronto por quem compõe o
 * grafo. `null` = nenhum equipamento pareado ainda, tratado honestamente como
 * [PingUiState.Unavailable]/[PortCheckUiState.Unavailable] por cada ViewModel, nunca como motivo
 * para inventar um alvo.
 */
fun NavGraphBuilder.toolsPingGraph(
    onBack: () -> Unit,
    defaultTargetHost: String?,
) {
    composable(AdvancedToolDestination.PING.route) {
        val viewModel: PingViewModel = viewModel(factory = pingViewModelFactory(defaultTargetHost))
        PingScreen(viewModel = viewModel, onBack = onBack)
    }

    composable(AdvancedToolDestination.PORT_CHECK.route) {
        val viewModel: PortCheckViewModel = viewModel(factory = portCheckViewModelFactory(defaultTargetHost))
        PortCheckScreen(viewModel = viewModel, onBack = onBack)
    }
}

/** Sobrecarga de conveniência para quem já tem um [NavHostController] à mão — `onBack` vira `popBackStack()`. */
fun NavGraphBuilder.toolsPingGraph(
    navController: NavHostController,
    defaultTargetHost: String?,
) {
    toolsPingGraph(onBack = { navController.popBackStack() }, defaultTargetHost = defaultTargetHost)
}

private fun pingViewModelFactory(defaultTargetHost: String?): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == PingViewModel::class.java) { "Unknown ViewModel class: $modelClass" }
            return PingViewModel(defaultTargetHost) as T
        }
    }

private fun portCheckViewModelFactory(defaultTargetHost: String?): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == PortCheckViewModel::class.java) { "Unknown ViewModel class: $modelClass" }
            return PortCheckViewModel(defaultTargetHost) as T
        }
    }
