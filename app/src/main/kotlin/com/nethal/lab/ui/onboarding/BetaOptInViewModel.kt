package com.nethal.lab.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nethal.core.consent.ConsentRepository
import com.nethal.core.consent.ConsentScope
import kotlinx.coroutines.launch

/**
 * Opt-in ao programa de testers beta (SIG-315, spec §8.9 e §10 "Entrada e saída do programa
 * beta"). A tela explica o que é coletado antes da confirmação; recusar não bloqueia o uso do
 * app — apenas mantém TELEMETRY_BETA como não concedido, e nenhum relatório é enviado.
 */
class BetaOptInViewModel(
    private val consentRepository: ConsentRepository,
) : ViewModel() {

    fun optIn(onDone: () -> Unit) {
        viewModelScope.launch {
            consentRepository.grant(ConsentScope.TELEMETRY_BETA, System.currentTimeMillis())
            onDone()
        }
    }

    fun optOut(onDone: () -> Unit) {
        viewModelScope.launch {
            consentRepository.revoke(ConsentScope.TELEMETRY_BETA)
            onDone()
        }
    }
}
