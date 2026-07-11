package com.nethal.lab.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nethal.core.capability.CapabilityEngine
import com.nethal.core.catalog.DriverFamilyRegistry
import com.nethal.core.catalog.DriverRegistry
import com.nethal.core.consent.ConsentRepository
import com.nethal.core.designsystem.theme.ThemeModeRepository
import com.nethal.feature.settings.SettingsViewModel
import com.nethal.lab.ui.capabilities.CapabilitiesViewModel
import com.nethal.lab.ui.capabilities.CapabilityItem
import com.nethal.lab.ui.onboarding.BetaOptInViewModel
import com.nethal.lab.ui.onboarding.WelcomeViewModel
import com.nethal.lab.ui.report.ReportViewModel

/**
 * Factory única do app. Sem DI framework nesta entrega — o grafo de dependências ainda é
 * pequeno o suficiente para não justificar Hilt/Koin. `DiscoveryViewModel`/
 * `EquipmentDetectedViewModel` saíram daqui na extração de `:feature:pairing-discovery` (ADR
 * 0002), e `AuthenticationViewModel` (→ `PairingAuthViewModel`) saiu na extração de
 * `:feature:pairing-auth` (issues #76-#79) — cada um desses módulos monta seus próprios
 * ViewModels a partir de suas próprias `*Dependencies` (ver `NetHalApplication`/`MainActivity`),
 * nunca através desta factory.
 */
class NetHalViewModelFactory(
    private val consentRepository: ConsentRepository,
    private val themeModeRepository: ThemeModeRepository,
    private val driverRegistry: DriverRegistry,
    private val driverFamilyRegistry: DriverFamilyRegistry,
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when (modelClass) {
            WelcomeViewModel::class.java -> WelcomeViewModel(consentRepository) as T
            BetaOptInViewModel::class.java -> BetaOptInViewModel(consentRepository) as T
            SettingsViewModel::class.java -> SettingsViewModel(consentRepository, themeModeRepository) as T
            else -> throw IllegalArgumentException("Unknown ViewModel class: $modelClass")
        }
    }

    /**
     * `CapabilitiesViewModel` recebe a sessão (`CapabilityEngine`) já autenticada, entregue pelo
     * grafo de autenticação via `PairingAuthViewModel.captureAuthenticatedSession()` — nunca
     * constrói uma sessão nova aqui. `capabilityEngine` pode ser `null` (sessão perdida entre
     * telas); a própria `CapabilitiesViewModel` trata isso como
     * `CapabilitiesUiState.SessionUnavailable`, não esta factory.
     */
    fun forCapabilities(capabilityEngine: CapabilityEngine?, matchedProfileId: String?): ViewModelProvider.Factory =
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass == CapabilitiesViewModel::class.java) {
                    "Unknown ViewModel class: $modelClass"
                }
                return CapabilitiesViewModel(
                    capabilityEngine = capabilityEngine,
                    matchedProfileId = matchedProfileId,
                    driverRegistry = driverRegistry,
                ) as T
            }
        }

    /**
     * `ReportViewModel` recebe os itens já lidos pela Tela 4 (Capabilities) — não lê nada do
     * equipamento nem depende de nenhuma sessão ativa (a Tela 4 já encerrou a sessão antes de
     * chegar aqui, ver `CapabilitiesViewModel.closeSession`).
     */
    fun forReport(matchedProfileId: String?, items: List<CapabilityItem>): ViewModelProvider.Factory =
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass == ReportViewModel::class.java) {
                    "Unknown ViewModel class: $modelClass"
                }
                return ReportViewModel(
                    matchedProfileId = matchedProfileId,
                    driverRegistry = driverRegistry,
                    items = items,
                ) as T
            }
        }
}
