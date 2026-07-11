package com.nethal.lab.ui.discovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nethal.core.discovery.DiscoveryEngine
import com.nethal.core.discovery.NetworkEnvironmentReader
import com.nethal.core.protocol.PrivateIpRanges
import com.nethal.core.model.NetworkTarget
import com.nethal.core.model.TargetRole
import com.nethal.core.model.TargetSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Orquestra a Tela 2 (spec §11): pede a permissão de localização em runtime (primeira vez
 * que o app de fato a solicita — a Tela 1 só explica o porquê, SIG-312), roda o Discovery
 * Engine e decide entre Tela 2b (falha), Tela 2c (múltiplos candidatos) ou seguir direto
 * quando há exatamente um candidato sem indício de duplo NAT.
 */
class DiscoveryViewModel(
    private val discoveryEngine: DiscoveryEngine,
    private val networkEnvironmentReader: NetworkEnvironmentReader,
) : ViewModel() {

    private val _uiState = MutableStateFlow<DiscoveryUiState>(DiscoveryUiState.AwaitingLocationPermission)
    val uiState: StateFlow<DiscoveryUiState> = _uiState.asStateFlow()

    private val _manualTargetError = MutableStateFlow<String?>(null)
    val manualTargetError: StateFlow<String?> = _manualTargetError.asStateFlow()

    private val manualDevices = mutableListOf<NetworkTarget>()

    fun onLocationPermissionResult(granted: Boolean) {
        if (!granted) {
            _uiState.value = DiscoveryUiState.Failed(FailureReason.LOCATION_PERMISSION_DENIED)
            return
        }
        startDiscovery()
    }

    fun retry() {
        startDiscovery()
    }

    fun addManualTarget(ip: String) {
        val trimmed = ip.trim()
        if (trimmed.isEmpty()) return

        // Guarda de SSRF (revisão de segurança da Feat 3): o IP manual é digitado pelo próprio
        // usuário, mas pode apontar sem querer para um host público — o NetHAL só deve sondar
        // equipamentos da rede local, nunca a internet. Mesma regra do UpnpIgdProbe/isProbeAllowed.
        if (!PrivateIpRanges.isPrivate(trimmed)) {
            _manualTargetError.value =
                "Esse IP não parece ser da sua rede local. O NetHAL só testa equipamentos na sua LAN."
            return
        }

        _manualTargetError.value = null
        manualDevices += NetworkTarget(
            ip = trimmed,
            role = TargetRole.MANUAL,
            source = TargetSource.USER_INPUT,
        )
        startDiscovery(reuseManualDevices = true)
    }

    private fun startDiscovery(reuseManualDevices: Boolean = false) {
        if (!reuseManualDevices) manualDevices.clear()

        _uiState.value = DiscoveryUiState.Scanning

        viewModelScope.launch {
            // Lida separado do discoveryEngine.discover() só para popular a UI (IP local/DNS,
            // spec Tela 2) — o engine já lê o ambiente de novo internamente para decidir o
            // gateway. Duplicar essa leitura é aceitável (é uma chamada local, não de rede)
            // e evita reabrir a interface pública do DiscoveryEngine para expor esse detalhe.
            val environment = networkEnvironmentReader.read()
            val networkInfo = DiscoveredNetworkInfo(
                localIp = environment?.localIp,
                gatewayIp = environment?.gatewayIp,
                dnsServers = environment?.dnsServers.orEmpty(),
            )

            val result = discoveryEngine.discover()
            val allDevices = result.devices + manualDevices

            _uiState.value = when {
                allDevices.isEmpty() -> DiscoveryUiState.Failed(FailureReason.NO_GATEWAY_FOUND)

                allDevices.size == 1 && !result.possibleDoubleNat ->
                    DiscoveryUiState.SingleCandidateReady(allDevices.first(), networkInfo)

                else -> DiscoveryUiState.MultipleCandidates(
                    devices = allDevices,
                    possibleDoubleNat = result.possibleDoubleNat,
                    networkInfo = networkInfo,
                )
            }
        }
    }
}
