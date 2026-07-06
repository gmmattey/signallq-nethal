package com.nethal.lab.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.nethal.core.model.NetworkTarget
import com.nethal.lab.ui.common.NetHalViewModelFactory
import com.nethal.lab.ui.discovery.DiscoveryScreen
import com.nethal.lab.ui.discovery.DiscoveryViewModel
import com.nethal.lab.ui.equipment.EquipmentDetectedScreen
import com.nethal.lab.ui.equipment.EquipmentDetectedViewModel
import com.nethal.lab.ui.onboarding.BetaOptInScreen
import com.nethal.lab.ui.onboarding.BetaOptInViewModel
import com.nethal.lab.ui.onboarding.WelcomeScreen
import com.nethal.lab.ui.onboarding.WelcomeViewModel
import com.nethal.lab.ui.privacy.PrivacyScreen
import com.nethal.lab.ui.settings.SettingsScreen
import com.nethal.lab.ui.settings.SettingsViewModel

private object Routes {
    const val WELCOME = "welcome"
    const val PRIVACY = "privacy"
    const val BETA_OPT_IN = "beta_opt_in"
    const val DISCOVERY = "discovery"
    const val TARGET_SELECTED = "target_selected"
    const val SETTINGS = "settings"
}

@Composable
fun NetHalNavHost(
    viewModelFactory: NetHalViewModelFactory,
    navController: NavHostController = rememberNavController(),
) {
    // Guardado no escopo do NavHost (não dentro de um `composable {}`) para sobreviver à
    // navegação de "discovery" para "target_selected". O `EquipmentDetectedViewModel` precisa
    // do `NetworkTarget` no construtor (não dá para injetar via `SavedStateHandle` sem
    // Parcelable/serializer dedicado nesta entrega) — factory por instância cobre isso.
    var selectedTarget by remember { mutableStateOf<NetworkTarget?>(null) }

    NavHost(navController = navController, startDestination = Routes.WELCOME) {
        composable(Routes.WELCOME) {
            val viewModel: WelcomeViewModel = viewModel(factory = viewModelFactory)
            WelcomeScreen(
                viewModel = viewModel,
                onStartDiagnosis = { navController.navigate(Routes.BETA_OPT_IN) },
                onViewPrivacy = { navController.navigate(Routes.PRIVACY) },
                onExit = { /* encerrado pela Activity host */ },
            )
        }

        composable(Routes.PRIVACY) {
            PrivacyScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.BETA_OPT_IN) {
            val viewModel: BetaOptInViewModel = viewModel(factory = viewModelFactory)
            BetaOptInScreen(
                viewModel = viewModel,
                onDecided = {
                    navController.navigate(Routes.DISCOVERY) {
                        popUpTo(Routes.WELCOME) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.DISCOVERY) {
            val viewModel: DiscoveryViewModel = viewModel(factory = viewModelFactory)

            DiscoveryScreen(
                viewModel = viewModel,
                onSingleCandidateReady = { target ->
                    selectedTarget = target
                    navController.navigate(Routes.TARGET_SELECTED)
                },
                onCandidateChosen = { target ->
                    selectedTarget = target
                    navController.navigate(Routes.TARGET_SELECTED)
                },
            )
        }

        composable(Routes.TARGET_SELECTED) {
            val target = selectedTarget
            if (target == null) {
                // Estado perdido (ex.: processo recriado) — volta para a descoberta em vez
                // de mostrar uma tela sem dado nenhum.
                navController.navigate(Routes.DISCOVERY) {
                    popUpTo(Routes.DISCOVERY) { inclusive = true }
                }
            } else {
                val viewModel: EquipmentDetectedViewModel = viewModel(
                    factory = viewModelFactory.forEquipmentDetected(target),
                )
                EquipmentDetectedScreen(
                    viewModel = viewModel,
                    onContinue = { navController.navigate(Routes.SETTINGS) },
                )
            }
        }

        composable(Routes.SETTINGS) {
            val viewModel: SettingsViewModel = viewModel(factory = viewModelFactory)
            SettingsScreen(viewModel = viewModel)
        }
    }
}
