package com.nethal.lab

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.nethal.lab.ui.common.NetHalViewModelFactory
import com.nethal.lab.ui.navigation.NetHalNavHost
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
            driverRegistry = app.driverRegistry,
            driverFamilyRegistry = app.driverFamilyRegistry,
        )

        setContent {
            // Recompoe o tema na hora quando o seletor de Configurações muda o modo — sem reiniciar
            // o app. O StateFlow do repositório de tema é a fonte única.
            val themeMode by app.themeModeRepository.observeThemeMode()
                .collectAsState(initial = ThemeMode.SYSTEM)

            NetHalLabTheme(themeMode = themeMode) {
                NetHalNavHost(
                    viewModelFactory = viewModelFactory,
                    pairingDiscoveryDependencies = app.pairingDiscoveryDependencies,
                    pairingAuthDependencies = app.pairingAuthDependencies,
                )
            }
        }
    }
}
