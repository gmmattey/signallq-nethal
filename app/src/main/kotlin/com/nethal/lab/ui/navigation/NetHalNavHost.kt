package com.nethal.lab.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.nethal.core.catalog.DriverRegistry
import com.nethal.core.consent.ConsentRepository
import com.nethal.core.model.NetworkTarget
import com.nethal.feature.onboarding.OnboardingPermissionsState
import com.nethal.feature.onboarding.navigation.OnboardingRoutes
import com.nethal.feature.onboarding.navigation.onboardingGraph
import com.nethal.feature.pairingauth.PairingAuthDependencies
import com.nethal.feature.pairingauth.PairingAuthRoutes
import com.nethal.feature.pairingauth.pairingAuthGraph
import com.nethal.feature.pairingdiscovery.PairingDiscoveryDependencies
import com.nethal.feature.pairingdiscovery.PairingDiscoveryRoutes
import com.nethal.feature.pairingdiscovery.pairingDiscoveryGraph
import com.nethal.lab.data.onboarding.OnboardingCompletionDataStoreRepository
import com.nethal.lab.ui.common.NetHalViewModelFactory
import kotlinx.coroutines.launch

private object Routes {
    // Host do "modo uso diário" (#67, ADR 0002 Fase 2) — `BottomNavHost`, com as abas Status, Rede,
    // Dispositivos e Configurações. Único destino próprio deste NavHost raiz; os três blocos de
    // funil (onboarding, pareamento por descoberta, pareamento por autenticação) entram como grafos
    // de módulo (`onboardingGraph`/`pairingDiscoveryGraph`/`pairingAuthGraph`).
    const val HOME = "home"
}

/**
 * Grafo de navegação único do NetHAL Lab (issue #113) — costura os três blocos do redesenho num só
 * `NavHost`:
 *
 * ```
 * ┌──────────── primeira instalação (onboarding ainda não concluído) ────────────┐
 * │ onboardingGraph  (1a→1b→1c→1d→1e)          :feature:onboarding, #68-73        │
 * │   1a Boas-vindas ── "Ver dispositivos compatíveis" ──▶ 1f  (volta com back)    │
 * │   1e Resumo de permissões ── onPermissionsSummaryContinue ─────────┐           │
 * └────────────────────────────────────────────────────────────────────┼──────────┘
 *                                                                        │
 *      já onboarded → startDestination pula direto para ────────────────▼──────────┐
 * ┌──────────────────────────── Pareamento ─────────────────────────────────────────┐
 * │ pairingDiscoveryGraph (2a/2b/2g/2h/2i)     :feature:pairing-discovery, #74-82    │
 * │   equipamento confirmado ─▶ pairingAuthGraph (2c/2d/2e/2f)  :feature:pairing-auth│
 * │   autenticado (onAuthenticated) ───────────────────────────────────┐            │
 * └─────────────────────────────────────────────────────────────────────┼───────────┘
 *                                                                         ▼
 * ┌──────────────────────────── Uso diário ─────────────────────────────────────────┐
 * │ Routes.HOME → BottomNavHost (Status/Rede/Dispositivos/Configurações)  #67        │
 * └──────────────────────────────────────────────────────────────────────────────────┘
 * ```
 *
 * Regras da costura (critérios de aceite da #113):
 *
 * - **Onboarding roda uma vez.** [onboardingCompletionRepository] guarda o marcador persistido
 *   (`nethal_onboarding`). Na primeira instalação o `startDestination` é `OnboardingRoutes.WELCOME`;
 *   depois de concluído (última tela `1e`), o próximo launch entra direto em
 *   `PairingDiscoveryRoutes.GRAPH`, sem repetir boas-vindas.
 * - **Handoff onboarding → pareamento:** `1e` (resumo de permissões) é a última tela do onboarding.
 *   Ao concluir, marca o onboarding e navega para o pareamento removendo o onboarding da back stack.
 * - **Handoff pareamento → uso diário:** `2e` (Conectando) confirma sucesso via `onAuthenticated` e
 *   entra **direto** no bottom nav. As telas Capabilities/Report foram descontinuadas (decisão #66):
 *   seu conteúdo migra para os cards ao vivo da tela Status (`:feature:status`, #83).
 * - **Trocar de equipamento a partir de Configurações** (ação ainda inexistente — #85 cortou
 *   "EQUIPAMENTO" do protótipo): `pairingDiscoveryGraph` é irmão de `Routes.HOME` no mesmo `NavHost`,
 *   então quando a ação existir basta `navigate(PairingDiscoveryRoutes.GRAPH)` a partir de Home — sem
 *   reexecutar o onboarding, que fica atrás do marcador persistido.
 *
 * Documentação completa do grafo em `docs/architecture/navigation-graph.md`.
 */
@Composable
fun NetHalNavHost(
    viewModelFactory: NetHalViewModelFactory,
    driverRegistry: DriverRegistry,
    consentRepository: ConsentRepository,
    onboardingCompletionRepository: OnboardingCompletionDataStoreRepository,
    onboardingPermissionsState: () -> OnboardingPermissionsState,
    pairingDiscoveryDependencies: PairingDiscoveryDependencies,
    pairingAuthDependencies: PairingAuthDependencies,
    navController: NavHostController = rememberNavController(),
) {
    // O `startDestination` depende do marcador persistido de onboarding, que chega de forma assíncrona
    // pelo DataStore. Enquanto não temos o primeiro valor (`null`), não compomos o `NavHost`:
    // `startDestination` só é lido na primeira composição, então compor com um palpite e "corrigir"
    // depois não troca o destino inicial — resultaria em piscar o onboarding para quem já concluiu.
    val onboardingCompleted by onboardingCompletionRepository.observeCompleted()
        .collectAsState(initial = null)
    val completed = onboardingCompleted ?: return

    val scope = rememberCoroutineScope()

    // Estado compartilhado entre o grafo de descoberta e o de autenticação — guardado no escopo do
    // NavHost (não dentro de um `composable {}`) para sobreviver à navegação entre grafos. Os
    // ViewModels dessas telas recebem esses valores no construtor.
    var selectedTarget by remember { mutableStateOf<NetworkTarget?>(null) }
    var matchedProfileId by remember { mutableStateOf<String?>(null) }

    val startDestination = if (completed) {
        PairingDiscoveryRoutes.GRAPH
    } else {
        OnboardingRoutes.WELCOME
    }

    NavHost(navController = navController, startDestination = startDestination) {
        // ── Onboarding (1a→1f) — :feature:onboarding, #68-73 ────────────────────────────────────
        onboardingGraph(
            navController = navController,
            driverRegistry = driverRegistry,
            consentRepository = consentRepository,
            onboardingPermissionsState = onboardingPermissionsState,
            onPermissionsSummaryContinue = {
                // Última tela do onboarding: marca como concluído (não repete no próximo launch) e
                // entra no pareamento, tirando o onboarding inteiro da back stack.
                scope.launch { onboardingCompletionRepository.markCompleted() }
                navController.navigate(PairingDiscoveryRoutes.GRAPH) {
                    popUpTo(OnboardingRoutes.WELCOME) { inclusive = true }
                }
            },
            // Destinos reais de "Ver privacidade" e "Recomendar modelo" vivem em Configurações
            // (#85/#66) e ainda não existem neste grafo — no-op documentado, não navegação morta.
            onViewPrivacy = {},
            onRecommendModel = {},
        )

        // ── Pareamento por descoberta (2a/2b/2g/2h/2i) — :feature:pairing-discovery, #74-82 ──────
        pairingDiscoveryGraph(
            navController = navController,
            dependencies = pairingDiscoveryDependencies,
            onEquipmentConfirmed = { target, profileId ->
                selectedTarget = target
                matchedProfileId = profileId
                navController.navigate(PairingAuthRoutes.GRAPH)
            },
        )

        // ── Pareamento por autenticação (2c/2d/2e/2f) — :feature:pairing-auth, #76-79 ───────────
        pairingAuthGraph(
            navController = navController,
            target = selectedTarget,
            matchedProfileId = matchedProfileId,
            dependencies = pairingAuthDependencies,
            onAuthenticated = { engine ->
                // Handoff pareamento → uso diário. As telas Capabilities/Report foram descontinuadas
                // (decisão #66) — o consumidor da sessão ao vivo passa a ser a tela Status
                // (`:feature:status`, #83), hoje ainda uma casca (placeholder). Como NÃO há consumidor
                // da sessão neste momento, encerramos determinísticamente a sessão autenticada
                // (`closeSession()` descarta a credencial em memória) em vez de deixá-la pendurada sem
                // dono até o GC — alinhado ao não-negociável "sem credencial armazenada".
                //
                // QUANDO #83 for real: NÃO fechar aqui — encaminhar `engine` para o host de uso diário
                // (Status) alimentar os cards ao vivo, transferindo a posse do `closeSession()` para a
                // tela Status. Ponto de revisão de segurança da Marisa (ciclo de vida da credencial em
                // memória).
                engine.closeSession()
                selectedTarget = null
                matchedProfileId = null
                navController.navigate(Routes.HOME) {
                    popUpTo(PairingDiscoveryRoutes.GRAPH) { inclusive = true }
                }
            },
            onTargetMissing = {
                // Estado perdido: volta para a descoberta em vez de tentar autenticar sem
                // `NetworkTarget` nenhum.
                navController.navigate(PairingDiscoveryRoutes.GRAPH) {
                    popUpTo(PairingDiscoveryRoutes.GRAPH) { inclusive = true }
                }
            },
        )

        // ── Uso diário (bottom nav) — #67 ───────────────────────────────────────────────────────
        composable(Routes.HOME) {
            BottomNavHost(viewModelFactory = viewModelFactory)
        }
    }
}
