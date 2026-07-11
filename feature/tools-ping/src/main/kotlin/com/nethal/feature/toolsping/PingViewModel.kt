package com.nethal.feature.toolsping

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nethal.core.model.LatencyProbeRequest
import com.nethal.core.protocol.tcp.LatencyMeasurementResult
import com.nethal.core.protocol.tcp.LatencyMeasurer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Orquestra a tela Ping (issue #91) sobre a capability `MEASURE_LATENCY` (issue #99,
 * [LatencyMeasurer]) — TCP connect, não ICMP (ver nota de copy da issue #91, refletida em
 * [PingScreen]).
 *
 * [defaultTargetHost] é o IP do equipamento pareado nesta sessão — mesmo handoff de
 * `capabilityEngine: CapabilityEngine?` já usado por `StatusViewModel`, entregue pronto por quem
 * compõe o grafo (`ToolsPingGraph`). `null` = nenhum equipamento pareado ainda: como
 * `MEASURE_LATENCY` não é mediada por Driver Family (ver KDoc de
 * [com.nethal.core.model.CapabilityId.MEASURE_LATENCY]), o motivo de indisponibilidade aqui não é
 * "o driver não suporta" — é "sem alvo de LAN conhecido ainda" (ver [PingUiState.Unavailable]).
 *
 * [ioDispatcher] injetável só para `PingViewModelTest` conseguir controlar tempo virtual de ponta a
 * ponta com `StandardTestDispatcher` (sem isso, `withContext(Dispatchers.IO)` escaparia para um
 * dispatcher real fora do controle de `advanceUntilIdle()`, tornando o teste flaky). Produção nunca
 * informa este parâmetro — o default já é [Dispatchers.IO].
 */
class PingViewModel(
    private val defaultTargetHost: String?,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _uiState = MutableStateFlow(initialState())
    val uiState: StateFlow<PingUiState> = _uiState.asStateFlow()

    private fun initialState(): PingUiState {
        val host = defaultTargetHost
        return if (host.isNullOrBlank()) {
            PingUiState.Unavailable(
                reason = "Nenhum equipamento pareado nesta sessão. Pareie um equipamento para medir a " +
                    "latência até ele.",
            )
        } else {
            PingUiState.Ready(targetHost = host)
        }
    }

    fun onTargetHostChanged(host: String) {
        val current = _uiState.value as? PingUiState.Ready ?: return
        _uiState.value = current.copy(targetHost = host, errorMessage = null)
    }

    /** Dispara uma medição real — nunca reentra enquanto uma já está em andamento. */
    fun run() {
        val current = _uiState.value as? PingUiState.Ready ?: return
        if (current.isRunning || current.targetHost.isBlank()) return
        _uiState.value = current.copy(isRunning = true, errorMessage = null)

        viewModelScope.launch {
            val request = LatencyProbeRequest(targetHost = current.targetHost)
            // Socket.connect() bloqueia a thread — nunca na main.
            val measurement = withContext(ioDispatcher) { LatencyMeasurer.measure(request) }

            val latest = _uiState.value as? PingUiState.Ready ?: return@launch
            _uiState.value = when (measurement) {
                is LatencyMeasurementResult.Success ->
                    latest.copy(isRunning = false, result = measurement.stats, errorMessage = null)
                is LatencyMeasurementResult.Rejected ->
                    latest.copy(isRunning = false, result = null, errorMessage = measurement.reason)
            }
        }
    }
}
