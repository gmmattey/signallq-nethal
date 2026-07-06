package com.nethal.lab

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.nethal.lab.ui.common.NetHalViewModelFactory
import com.nethal.lab.ui.navigation.NetHalNavHost
import com.nethal.lab.ui.theme.NetHalLabTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as NetHalApplication
        val viewModelFactory = NetHalViewModelFactory(
            consentRepository = app.consentRepository,
            discoveryEngine = app.discoveryEngine,
            networkEnvironmentReader = app.networkEnvironmentReader,
            fingerprintEngine = app.fingerprintEngine,
            manualIdentificationRepository = app.manualIdentificationRepository,
        )

        setContent {
            NetHalLabTheme {
                NetHalNavHost(viewModelFactory = viewModelFactory)
            }
        }
    }
}
