package com.nethal.lab.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nethal.core.consent.ConsentRepository
import com.nethal.core.consent.ConsentScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val consentRepository: ConsentRepository,
) : ViewModel() {

    val isBetaProgramActive: StateFlow<Boolean> = consentRepository.observeState()
        .map { it.isGranted(ConsentScope.TELEMETRY_BETA) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * Opt-out apenas impede novo envio de telemetria (spec §10 "Saída"). Não promete,
     * e não pode prometer, remoção de relatórios já enviados — são anônimos.
     */
    fun leaveBetaProgram() {
        viewModelScope.launch {
            consentRepository.revoke(ConsentScope.TELEMETRY_BETA)
        }
    }
}
