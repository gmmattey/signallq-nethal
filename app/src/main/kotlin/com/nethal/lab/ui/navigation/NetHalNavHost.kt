package com.nethal.lab.ui.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.nethal.lab.ui.common.NetHalViewModelFactory
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
    const val SETTINGS = "settings"

    // Placeholder até a Feat 2 (Discovery Engine) existir.
    const val DISCOVERY_PLACEHOLDER = "discovery_placeholder"
}

@Composable
fun NetHalNavHost(
    viewModelFactory: NetHalViewModelFactory,
    navController: NavHostController = rememberNavController(),
) {
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
                    navController.navigate(Routes.DISCOVERY_PLACEHOLDER) {
                        popUpTo(Routes.WELCOME) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.DISCOVERY_PLACEHOLDER) {
            DiscoveryPlaceholderScreen(
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }

        composable(Routes.SETTINGS) {
            val viewModel: SettingsViewModel = viewModel(factory = viewModelFactory)
            SettingsScreen(viewModel = viewModel)
        }
    }
}
