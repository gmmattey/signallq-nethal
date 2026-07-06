package com.nethal.lab.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nethal.core.consent.ConsentRepository
import com.nethal.core.consent.ConsentScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Estado da Tela 1 (Boas-vindas), spec §11.
 *
 * "Iniciar diagnóstico" só libera quando o usuário confirma, em um passo distinto do termo de
 * teste genérico, que a rede é dele ou que tem autorização para testá-la (SIG-313). Esta
 * confirmação é o consentimento NETWORK_AUTHORIZATION — o prompt nativo de localização do
 * Android só deve ser disparado depois que o usuário avança desta tela (SIG-312), então este
 * ViewModel nunca solicita permissão em si; ele só libera a navegação.
 */
class WelcomeViewModel(
    private val consentRepository: ConsentRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(WelcomeUiState())
    val uiState: StateFlow<WelcomeUiState> = _uiState.asStateFlow()

    fun onNetworkAuthorizationChanged(confirmed: Boolean) {
        _uiState.value = _uiState.value.copy(networkAuthorizationConfirmed = confirmed)
    }

    fun confirmAndProceed(onProceed: () -> Unit) {
        val confirmed = _uiState.value.networkAuthorizationConfirmed
        if (!confirmed) return

        viewModelScope.launch {
            consentRepository.grant(ConsentScope.NETWORK_AUTHORIZATION, System.currentTimeMillis())
            consentRepository.grant(ConsentScope.READ_STATUS, System.currentTimeMillis())
            onProceed()
        }
    }
}

data class WelcomeUiState(
    val networkAuthorizationConfirmed: Boolean = false,
) {
    val canStartDiagnosis: Boolean get() = networkAuthorizationConfirmed
}
