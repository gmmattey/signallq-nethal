package com.nethal.feature.toolsdns

import com.nethal.core.model.DnsLookupResult

/** Estado da tela DNS Lookup (protótipo `4e`) — nunca inicia em [Success] com dado de exemplo. */
sealed interface DnsLookupUiState {
    data object Idle : DnsLookupUiState
    data object Loading : DnsLookupUiState
    data class Success(val result: DnsLookupResult) : DnsLookupUiState
    data class Error(val hostname: String, val reason: String) : DnsLookupUiState

    /** Sem conectividade — a ferramenta reusa o componente "Recurso indisponível" de `:feature:tools-common` (critério de aceite #93). */
    data object NoNetwork : DnsLookupUiState
}
