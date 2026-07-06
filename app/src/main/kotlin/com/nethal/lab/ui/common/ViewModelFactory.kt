package com.nethal.lab.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nethal.core.consent.ConsentRepository
import com.nethal.lab.ui.onboarding.WelcomeViewModel
import com.nethal.lab.ui.settings.SettingsViewModel

/**
 * Factory única do app. Sem DI framework nesta entrega — o grafo de dependências ainda é
 * pequeno o suficiente (só ConsentRepository) para não justificar Hilt/Koin.
 */
class NetHalViewModelFactory(
    private val consentRepository: ConsentRepository,
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when (modelClass) {
            WelcomeViewModel::class.java -> WelcomeViewModel(consentRepository) as T
            SettingsViewModel::class.java -> SettingsViewModel(consentRepository) as T
            else -> throw IllegalArgumentException("Unknown ViewModel class: $modelClass")
        }
    }
}
