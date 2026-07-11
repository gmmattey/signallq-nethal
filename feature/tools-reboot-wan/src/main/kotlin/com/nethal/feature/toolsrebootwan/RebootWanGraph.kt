package com.nethal.feature.toolsrebootwan

import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.nethal.core.capability.CapabilityEngine

/**
 * Rota desta tela (issue #95). Nenhuma rota compartilhada em `:core:navigation` cobre "Ferramentas"
 * ainda (o Hub em si é escopo de `:feature:tools-common`, issue #89, não conectado ao `NavHost`
 * real até esta rodada) — mesma situação de módulo "solto, pronto para plugar" que
 * `:feature:wifi-network` teve antes da consolidação dos 4 destinos de bottom nav (ver KDoc de
 * `wifiNetworkGraph`). Quando o Hub de Ferramentas existir, ele navega para [ROUTE].
 */
object RebootWanRoute {
    const val ROUTE = "tools/reboot-wan"
}

/**
 * Entrada do módulo `:feature:tools-reboot-wan` (issues #95/#103) no `NavHost` do composition
 * root — mesmo contrato de `NavGraphBuilder.xyzGraph()` da ADR 0002.
 *
 * **Não conectado ao `NavHost` real ainda.** Não existe hoje, no NetHAL Lab, nenhuma tela "Hub de
 * Ferramentas"/"Ferramentas avançadas" (protótipo `3c`, escopo de `:feature:tools-common`, issue
 * #89) de onde o usuário chegaria até aqui — quem implementar esse hub adiciona uma linha "Reiniciar
 * interface WAN" que navega para [RebootWanRoute.ROUTE]. Também não conectado a
 * `SettingsScreen`/`BottomNavHost` por instrução explícita desta tarefa (#95/#103): a linha que
 * levaria o usuário até esta tela fica pendente para uma tarefa futura de Configurações.
 *
 * @param capabilityEngineProvider fornece a sessão ativa (`CapabilityEngine`) para esta tela, ou
 * `null` quando não há sessão — mesmo contrato de `wifiNetworkGraph`.
 * @param onCancelled navegação de volta ao cancelar/sair sem confirmar — tipicamente
 * `navController::popBackStack`.
 */
fun NavGraphBuilder.rebootWanGraph(
    capabilityEngineProvider: () -> CapabilityEngine?,
    onCancelled: () -> Unit,
) {
    composable(RebootWanRoute.ROUTE) {
        val factory = remember(capabilityEngineProvider) { RebootWanViewModelFactory(capabilityEngineProvider()) }
        val viewModel: RebootWanViewModel = viewModel(factory = factory)
        RebootWanScreen(viewModel = viewModel, onCancelled = onCancelled)
    }
}

/** Variante de conveniência para quando o chamador já tem o `NavHostController` à mão (cancelar = voltar). */
fun NavGraphBuilder.rebootWanGraph(
    navController: NavHostController,
    capabilityEngineProvider: () -> CapabilityEngine?,
) {
    rebootWanGraph(capabilityEngineProvider = capabilityEngineProvider, onCancelled = { navController.popBackStack() })
}
