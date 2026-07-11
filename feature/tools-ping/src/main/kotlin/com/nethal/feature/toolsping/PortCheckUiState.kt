package com.nethal.feature.toolsping

import com.nethal.core.model.PortCheckOutcome

/**
 * Estado da tela Verificação de porta (issue #94). Nunca existe um caso "resultado de exemplo" —
 * [Ready.result] só é preenchido por uma execução real de
 * [com.nethal.core.protocol.tcp.PortChecker.check] (issue #100).
 */
sealed interface PortCheckUiState {

    /** Mesmo raciocínio de [PingUiState.Unavailable] — sem alvo de LAN conhecido nesta sessão. */
    data class Unavailable(val reason: String) : PortCheckUiState

    data class Ready(
        val targetHost: String,
        val port: String = "443",
        val isRunning: Boolean = false,
        val result: PortCheckOutcome? = null,
        val errorMessage: String? = null,
    ) : PortCheckUiState
}
