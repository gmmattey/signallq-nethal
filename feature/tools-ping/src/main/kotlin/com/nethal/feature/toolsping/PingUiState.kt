package com.nethal.feature.toolsping

import com.nethal.core.model.LatencyProbeStats

/**
 * Estado da tela Ping (issue #91). Nunca existe um caso "resultado de exemplo" — [Ready.result] só
 * é preenchido por uma execução real de [com.nethal.core.protocol.tcp.LatencyMeasurer.measure]
 * (issue #99).
 */
sealed interface PingUiState {

    /**
     * Sem alvo de LAN conhecido nesta sessão (nenhum equipamento pareado ainda) — a capability
     * `MEASURE_LATENCY` em si não depende de Driver Family (ver KDoc de
     * [com.nethal.core.model.CapabilityId.MEASURE_LATENCY]), então este estado reflete "nada para
     * medir ainda", não "o equipamento não suporta". Renderizado com [com.nethal.feature.toolscommon.UnavailableFeatureDialog]
     * (issue #91, critério "usa o componente de recurso indisponível").
     */
    data class Unavailable(val reason: String) : PingUiState

    data class Ready(
        val targetHost: String,
        val isRunning: Boolean = false,
        val result: LatencyProbeStats? = null,
        val errorMessage: String? = null,
    ) : PingUiState
}
