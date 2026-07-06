package com.nethal.lab.ui.equipment

import com.nethal.core.model.DetectedProtocol

/**
 * Estado da Tela 3 — Equipamento detectado (spec §11). `identifying` cobre o tempo do probe
 * HTTP passivo do Fingerprint Engine; `Identified` traz o resultado (fabricante/modelo podem
 * ser `null` quando o catálogo não bate com confiança suficiente).
 */
sealed interface EquipmentDetectedUiState {

    data object Identifying : EquipmentDetectedUiState

    data class Identified(
        val targetIp: String,
        val vendor: String?,
        val model: String?,
        val firmware: String?,
        val confidence: Double,
        val detectedProtocols: List<DetectedProtocol>,
        val manifestVersion: String,
        val manifestGeneratedAt: String,
        val isLowConfidence: Boolean,
        val correctionSubmitted: Boolean,
    ) : EquipmentDetectedUiState
}

/** Limiar abaixo do qual a Tela 3 destaca a ação "corrigir identificação" (spec §10 item 6). */
const val LOW_CONFIDENCE_THRESHOLD = 0.50

/** Sugestões reconhecíveis para a correção manual — os dois profiles reais do catálogo. */
data class KnownProfileSuggestion(val vendor: String, val model: String)

val KNOWN_PROFILE_SUGGESTIONS = listOf(
    KnownProfileSuggestion(vendor = "Nokia", model = "G-1425G-A"),
    KnownProfileSuggestion(vendor = "TP-Link", model = "Archer C6"),
)
