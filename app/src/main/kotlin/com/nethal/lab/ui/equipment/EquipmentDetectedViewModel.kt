package com.nethal.lab.ui.equipment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nethal.core.catalog.ManualIdentificationCandidate
import com.nethal.core.catalog.ManualIdentificationRepository
import com.nethal.core.fingerprint.FingerprintEngine
import com.nethal.core.model.NetworkTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Orquestra a Tela 3 (spec §11): roda o Fingerprint Engine sobre o `NetworkTarget` escolhido
 * na Tela 2/2c e expõe a ação de correção manual. Correção nunca promove um profile a
 * `STABLE` — só grava localmente como candidato `USER_SUBMITTED` (SIG-320).
 */
class EquipmentDetectedViewModel(
    private val target: NetworkTarget,
    private val fingerprintEngine: FingerprintEngine,
    private val manualIdentificationRepository: ManualIdentificationRepository,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val _uiState = MutableStateFlow<EquipmentDetectedUiState>(EquipmentDetectedUiState.Identifying)
    val uiState: StateFlow<EquipmentDetectedUiState> = _uiState.asStateFlow()

    init {
        identify()
    }

    private fun identify() {
        _uiState.value = EquipmentDetectedUiState.Identifying
        viewModelScope.launch {
            val result = fingerprintEngine.identify(target)
            _uiState.value = EquipmentDetectedUiState.Identified(
                targetIp = target.ip,
                vendor = result.vendor,
                model = result.model,
                firmware = result.firmware,
                confidence = result.confidence,
                detectedProtocols = result.detectedProtocols,
                manifestVersion = result.manifestVersion,
                manifestGeneratedAt = result.manifestGeneratedAt,
                isLowConfidence = result.confidence < LOW_CONFIDENCE_THRESHOLD,
                correctionSubmitted = false,
            )
        }
    }

    /**
     * Registra a correção do usuário como candidato local (nunca telemetria/upload real
     * nesta entrega — "enviar ao catálogo" aqui é só persistência + preparação do dado).
     */
    fun submitCorrection(vendor: String, model: String, firmware: String?) {
        val trimmedVendor = vendor.trim()
        val trimmedModel = model.trim()
        if (trimmedVendor.isEmpty() || trimmedModel.isEmpty()) return

        viewModelScope.launch {
            manualIdentificationRepository.submit(
                ManualIdentificationCandidate(
                    targetIp = target.ip,
                    vendor = trimmedVendor,
                    model = trimmedModel,
                    firmware = firmware?.trim()?.takeIf(String::isNotEmpty),
                    submittedAtEpochMillis = nowEpochMillis(),
                ),
            )
            val current = _uiState.value
            if (current is EquipmentDetectedUiState.Identified) {
                _uiState.value = current.copy(correctionSubmitted = true)
            }
        }
    }
}
