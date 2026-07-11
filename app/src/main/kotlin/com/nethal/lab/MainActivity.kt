package com.nethal.lab

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.nethal.lab.ui.common.NetHalViewModelFactory
import com.nethal.lab.ui.navigation.NetHalNavHost
import com.nethal.lab.ui.onboarding.onboardingPermissionsState
import com.nethal.core.designsystem.theme.NetHalLabTheme
import com.nethal.core.designsystem.theme.ThemeMode

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as NetHalApplication
        val viewModelFactory = NetHalViewModelFactory(
            consentRepository = app.consentRepository,
            themeModeRepository = app.themeModeRepository,
        )

        setContent {
            // Recompoe o tema na hora quando o seletor de Configurações muda o modo — sem reiniciar
            // o app. O StateFlow do repositório de tema é a fonte única.
            val themeMode by app.themeModeRepository.observeThemeMode()
                .collectAsState(initial = ThemeMode.SYSTEM)

            NetHalLabTheme(themeMode = themeMode) {
                NetHalNavHost(
                    viewModelFactory = viewModelFactory,
                    driverRegistry = app.driverRegistry,
                    consentRepository = app.consentRepository,
                    onboardingCompletionRepository = app.onboardingCompletionRepository,
                    // Estado real das permissões lido a cada composição da tela `1e` (resumo) — a
                    // permissão de notificação pode ter sido concedida na tela `1d` desde o launch.
                    onboardingPermissionsState = { onboardingPermissionsState(this) },
                    pairingDiscoveryDependencies = app.pairingDiscoveryDependencies,
                    pairingAuthDependencies = app.pairingAuthDependencies,
                )
            }
        }
    }
}
