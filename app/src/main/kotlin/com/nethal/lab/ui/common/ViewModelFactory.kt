package com.nethal.lab.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nethal.core.catalog.ManualIdentificationRepository
import com.nethal.core.consent.ConsentRepository
import com.nethal.core.discovery.DiscoveryEngine
import com.nethal.core.discovery.NetworkEnvironmentReader
import com.nethal.core.fingerprint.FingerprintEngine
import com.nethal.core.model.NetworkTarget
import com.nethal.lab.ui.discovery.DiscoveryViewModel
import com.nethal.lab.ui.equipment.EquipmentDetectedViewModel
import com.nethal.lab.ui.onboarding.WelcomeViewModel
import com.nethal.lab.ui.settings.SettingsViewModel

/**
 * Factory única do app. Sem DI framework nesta entrega — o grafo de dependências ainda é
 * pequeno o suficiente para não justificar Hilt/Koin.
 */
class NetHalViewModelFactory(
    private val consentRepository: ConsentRepository,
    private val discoveryEngine: DiscoveryEngine,
    private val networkEnvironmentReader: NetworkEnvironmentReader,
    private val fingerprintEngine: FingerprintEngine,
    private val manualIdentificationRepository: ManualIdentificationRepository,
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when (modelClass) {
            WelcomeViewModel::class.java -> WelcomeViewModel(consentRepository) as T
            SettingsViewModel::class.java -> SettingsViewModel(consentRepository) as T
            DiscoveryViewModel::class.java -> DiscoveryViewModel(discoveryEngine, networkEnvironmentReader) as T
            else -> throw IllegalArgumentException("Unknown ViewModel class: $modelClass")
        }
    }

    /**
     * `EquipmentDetectedViewModel` recebe um `NetworkTarget` por instância (varia a cada
     * navegação da Tela 2/2c) — factory dedicada em vez de sobrecarregar `create()` genérico,
     * que não tem como receber esse parâmetro por `Class<T>`.
     */
    fun forEquipmentDetected(target: NetworkTarget): ViewModelProvider.Factory =
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass == EquipmentDetectedViewModel::class.java) {
                    "Unknown ViewModel class: $modelClass"
                }
                return EquipmentDetectedViewModel(
                    target = target,
                    fingerprintEngine = fingerprintEngine,
                    manualIdentificationRepository = manualIdentificationRepository,
                ) as T
            }
        }
}
