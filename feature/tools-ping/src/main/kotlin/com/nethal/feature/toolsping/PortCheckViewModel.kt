package com.nethal.feature.toolsping

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nethal.core.model.PortCheckRequest
import com.nethal.core.protocol.tcp.PortCheckResult
import com.nethal.core.protocol.tcp.PortChecker
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Orquestra a tela Verificação de porta (issue #94) sobre a capability `CHECK_PORT` (issue #100,
 * [PortChecker]) — mesmo handoff de [defaultTargetHost] documentado em [PingViewModel].
 *
 * Validação de porta é local à UI (campo texto → `Int`, 1-65535) — nunca deixa passar um valor
 * inválido para [PortChecker.check] silenciosamente; um valor fora do intervalo vira
 * [PortCheckUiState.Ready.errorMessage] honesto, sem tentar nenhuma conexão.
 *
 * [ioDispatcher] injetável pelo mesmo motivo documentado em [PingViewModel.ioDispatcher].
 */
class PortCheckViewModel(
    private val defaultTargetHost: String?,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _uiState = MutableStateFlow(initialState())
    val uiState: StateFlow<PortCheckUiState> = _uiState.asStateFlow()

    private fun initialState(): PortCheckUiState {
        val host = defaultTargetHost
        return if (host.isNullOrBlank()) {
            PortCheckUiState.Unavailable(
                reason = "Nenhum equipamento pareado nesta sessão. Pareie um equipamento para verificar " +
                    "portas na sua rede local.",
            )
        } else {
            PortCheckUiState.Ready(targetHost = host)
        }
    }

    fun onTargetHostChanged(host: String) {
        val current = _uiState.value as? PortCheckUiState.Ready ?: return
        _uiState.value = current.copy(targetHost = host, errorMessage = null)
    }

    fun onPortChanged(port: String) {
        val current = _uiState.value as? PortCheckUiState.Ready ?: return
        _uiState.value = current.copy(port = port, errorMessage = null)
    }

    /** Dispara uma verificação real — nunca reentra enquanto uma já está em andamento. */
    fun run() {
        val current = _uiState.value as? PortCheckUiState.Ready ?: return
        if (current.isRunning) return

        val portNumber = current.port.toIntOrNull()
        if (portNumber == null || portNumber !in 1..65_535) {
            _uiState.value = current.copy(errorMessage = "Porta inválida — use um número entre 1 e 65535.")
            return
        }
        if (current.targetHost.isBlank()) return

        _uiState.value = current.copy(isRunning = true, errorMessage = null)

        viewModelScope.launch {
            val request = PortCheckRequest(targetHost = current.targetHost, port = portNumber)
            // Socket.connect() bloqueia a thread — nunca na main.
            val checkResult = withContext(ioDispatcher) { PortChecker.check(request) }

            val latest = _uiState.value as? PortCheckUiState.Ready ?: return@launch
            _uiState.value = when (checkResult) {
                is PortCheckResult.Success ->
                    latest.copy(isRunning = false, result = checkResult.outcome, errorMessage = null)
                is PortCheckResult.Rejected ->
                    latest.copy(isRunning = false, result = null, errorMessage = checkResult.reason)
            }
        }
    }
}
