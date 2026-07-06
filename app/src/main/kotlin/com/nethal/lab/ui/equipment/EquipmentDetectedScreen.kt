package com.nethal.lab.ui.equipment

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nethal.core.model.DetectedProtocol
import java.util.Locale

/**
 * Tela 3 — Equipamento detectado (spec §11). Mostra fabricante/modelo/firmware provável (ou
 * "não identificado"), protocolo detectado, confiança e a data do manifesto do catálogo
 * carregado. Ação de correção manual fica sempre disponível, destacada quando a confiança é
 * baixa (`LOW_CONFIDENCE_THRESHOLD`).
 */
@Composable
fun EquipmentDetectedScreen(viewModel: EquipmentDetectedViewModel, onContinue: () -> Unit) {
    val uiState by viewModel.uiState.collectAsState()

    when (val state = uiState) {
        is EquipmentDetectedUiState.Identifying -> IdentifyingContent()
        is EquipmentDetectedUiState.Identified -> IdentifiedContent(
            state = state,
            onSubmitCorrection = viewModel::submitCorrection,
            onContinue = onContinue,
        )
    }
}

@Composable
private fun IdentifyingContent() {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CircularProgressIndicator()
            Text(
                text = "Identificando o equipamento...",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun IdentifiedContent(
    state: EquipmentDetectedUiState.Identified,
    onSubmitCorrection: (String, String, String?) -> Unit,
    onContinue: () -> Unit,
) {
    var showCorrectionForm by remember { mutableStateOf(state.isLowConfidence) }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(text = "Equipamento detectado", style = MaterialTheme.typography.headlineSmall)

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(text = "IP: ${state.targetIp}", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "Fabricante: ${state.vendor ?: "não identificado"}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "Modelo: ${state.model ?: "não identificado"}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "Firmware: ${state.firmware ?: "não disponível"}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "Protocolo detectado: ${protocolsLabel(state.detectedProtocols)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    AssistChip(
                        onClick = {},
                        label = { Text("Confiança: ${confidencePercentLabel(state.confidence)}") },
                    )

                    if (state.isLowConfidence) {
                        Text(
                            text = "Confiança baixa — considere corrigir a identificação abaixo.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            Text(
                text = "Catálogo de compatibilidade: versão ${state.manifestVersion} " +
                    "(atualizado em ${state.manifestGeneratedAt}). A identificação pode estar " +
                    "desatualizada se o catálogo for antigo.",
                style = MaterialTheme.typography.bodySmall,
            )

            if (!showCorrectionForm) {
                OutlinedButton(
                    onClick = { showCorrectionForm = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Corrigir identificação")
                }
            } else {
                CorrectionForm(
                    correctionSubmitted = state.correctionSubmitted,
                    onSubmit = onSubmitCorrection,
                )
            }

            // Tela 4 (capabilities detectadas, spec §11) ainda não existe — "Continuar" leva a
            // Configurações só para o fluxo ter um destino navegável nesta entrega.
            Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                Text("Continuar (Configurações)")
            }
        }
    }
}

@Composable
private fun CorrectionForm(
    correctionSubmitted: Boolean,
    onSubmit: (String, String, String?) -> Unit,
) {
    var vendor by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var firmware by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = "Corrigir identificação", style = MaterialTheme.typography.titleMedium)

        Text(
            text = "Equipamentos reconhecidos pelo catálogo atual: " +
                KNOWN_PROFILE_SUGGESTIONS.joinToString(", ") { "${it.vendor} ${it.model}" },
            style = MaterialTheme.typography.bodySmall,
        )

        OutlinedTextField(
            value = vendor,
            onValueChange = { vendor = it },
            label = { Text("Fabricante") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            label = { Text("Modelo") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = firmware,
            onValueChange = { firmware = it },
            label = { Text("Firmware (opcional)") },
            modifier = Modifier.fillMaxWidth(),
        )

        Text(
            text = "Sua correção fica registrada apenas neste aparelho como candidata — " +
                "não promove automaticamente nenhum driver para uso estável.",
            style = MaterialTheme.typography.bodySmall,
        )

        Button(
            onClick = { onSubmit(vendor, model, firmware.ifBlank { null }) },
            enabled = vendor.isNotBlank() && model.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Salvar correção")
        }

        if (correctionSubmitted) {
            Text(
                text = "Correção salva localmente.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

private fun confidencePercentLabel(confidence: Double): String =
    "${String.format(Locale.getDefault(), "%.0f", confidence * 100)}%"

private fun protocolsLabel(protocols: List<DetectedProtocol>): String {
    if (protocols.isEmpty()) return "não detectado"
    return protocols.joinToString(", ") { protocol ->
        when (protocol) {
            DetectedProtocol.HTTP_LOCAL_WEBUI -> "WebUI local (HTTP)"
            DetectedProtocol.HTTPS_LOCAL_WEBUI -> "WebUI local (HTTPS)"
            DetectedProtocol.UNKNOWN -> "desconhecido"
        }
    }
}
