package com.nethal.lab.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nethal.core.consent.ConsentRepository
import com.nethal.core.designsystem.theme.ThemeModeRepository
import com.nethal.feature.settings.SettingsViewModel

/**
 * Factory única do app. Sem DI framework nesta entrega — o grafo de dependências ainda é pequeno o
 * suficiente para não justificar Hilt/Koin.
 *
 * Restou só `SettingsViewModel`: os ViewModels de descoberta/autenticação saíram na extração de
 * `:feature:pairing-discovery`/`:feature:pairing-auth` (ADR 0002) e montam-se a partir das próprias
 * `*Dependencies`; o onboarding migrou para `:feature:onboarding` (injeção direta de
 * `ConsentRepository`, sem ViewModel); e as telas Capabilities/Report foram descontinuadas (decisão
 * #66, issue #113) junto com seus `forCapabilities`/`forReport`.
 */
class NetHalViewModelFactory(
    private val consentRepository: ConsentRepository,
    private val themeModeRepository: ThemeModeRepository,
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when (modelClass) {
            SettingsViewModel::class.java -> SettingsViewModel(consentRepository, themeModeRepository) as T
            else -> throw IllegalArgumentException("Unknown ViewModel class: $modelClass")
        }
    }
}
