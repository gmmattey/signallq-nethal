package com.nethal.feature.toolsdns

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nethal.core.model.DnsLookupOutcome
import com.nethal.feature.toolsdns.data.DnsLookupClient
import com.nethal.feature.toolsdns.data.NetworkConnectivityChecker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Orquestra a tela DNS Lookup (issue #93/#101). [client] roda a consulta real
 * ([com.nethal.feature.toolsdns.data.CloudflareDohDnsLookupClient] em produção) — nunca é chamado
 * com dado de exemplo hardcodado como se fosse resultado real (critério de aceite #93).
 *
 * Diferente de `StatusViewModel`/`CapabilityEngine`, não há sessão de equipamento para
 * abrir/fechar aqui: DNS Lookup não depende de driver nem de autenticação (ver KDoc de
 * `com.nethal.core.model.DnsLookupOutcome`).
 */
class DnsLookupViewModel(
    private val client: DnsLookupClient,
    private val connectivityChecker: NetworkConnectivityChecker,
) : ViewModel() {

    private val _hostname = MutableStateFlow("")
    val hostname: StateFlow<String> = _hostname.asStateFlow()

    private val _uiState = MutableStateFlow<DnsLookupUiState>(DnsLookupUiState.Idle)
    val uiState: StateFlow<DnsLookupUiState> = _uiState.asStateFlow()

    fun onHostnameChanged(value: String) {
        _hostname.value = value
    }

    fun execute() {
        val host = _hostname.value.trim()
        if (host.isEmpty()) {
            _uiState.value = DnsLookupUiState.Error(host, "Informe um hostname para consultar.")
            return
        }
        if (!connectivityChecker.hasInternet()) {
            _uiState.value = DnsLookupUiState.NoNetwork
            return
        }

        viewModelScope.launch {
            _uiState.value = DnsLookupUiState.Loading
            _uiState.value = when (val outcome = client.lookup(host)) {
                is DnsLookupOutcome.Success -> DnsLookupUiState.Success(outcome.result)
                is DnsLookupOutcome.Failure -> DnsLookupUiState.Error(outcome.hostname, outcome.reason)
            }
        }
    }
}
