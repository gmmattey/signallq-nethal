package com.nethal.lab.ui.discovery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nethal.core.model.NetworkTarget
import com.nethal.core.model.TargetRole
import com.nethal.core.model.TargetSource

/**
 * Tela 2c — Múltiplos equipamentos encontrados (spec §11, SIG-318). Exibida quando
 * `devices.size > 1` ou `possibleDoubleNat == true`. Lista candidatos com papel e origem,
 * avisa sobre indício de duplo NAT e permite adicionar um equipamento manualmente por IP.
 */
@Composable
fun MultipleCandidatesScreen(
    state: DiscoveryUiState.MultipleCandidates,
    manualTargetError: String?,
    onCandidateChosen: (NetworkTarget) -> Unit,
    onAddManualTarget: (String) -> Unit,
) {
    var manualIp by remember { mutableStateOf("") }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Encontramos mais de um equipamento",
                style = MaterialTheme.typography.headlineSmall,
            )

            Text(
                text = "Escolha qual equipamento você quer testar.",
                style = MaterialTheme.typography.bodyLarge,
            )

            if (state.possibleDoubleNat) {
                Text(
                    text = "Pode haver um equipamento adicional entre você e a internet " +
                        "(ex.: ONT da operadora).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.devices.forEach { device ->
                    CandidateCard(device = device, onClick = { onCandidateChosen(device) })
                }
            }

            Text(
                text = "Ou informe outro equipamento manualmente:",
                style = MaterialTheme.typography.titleMedium,
            )

            OutlinedTextField(
                value = manualIp,
                onValueChange = { manualIp = it },
                label = { Text("IP do equipamento, ex.: 192.168.1.1") },
                isError = manualTargetError != null,
                modifier = Modifier.fillMaxWidth(),
            )

            if (manualTargetError != null) {
                Text(
                    text = manualTargetError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Button(
                onClick = { onAddManualTarget(manualIp) },
                enabled = manualIp.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Adicionar equipamento")
            }
        }
    }
}

@Composable
private fun CandidateCard(device: NetworkTarget, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = device.ip, style = MaterialTheme.typography.titleMedium)
            Text(text = roleLabel(device.role), style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "Origem: ${sourceLabel(device.source)}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun roleLabel(role: TargetRole): String = when (role) {
    TargetRole.PRIMARY_GATEWAY -> "Gateway principal"
    TargetRole.UPSTREAM_CANDIDATE -> "Possível equipamento a montante"
    TargetRole.MESH_NODE -> "Nó mesh / equipamento adicional"
    TargetRole.MANUAL -> "Adicionado manualmente"
}

private fun sourceLabel(source: TargetSource): String = when (source) {
    TargetSource.GATEWAY -> "gateway detectado"
    TargetSource.SSDP -> "SSDP"
    TargetSource.MDNS -> "mDNS"
    TargetSource.USER_INPUT -> "informado manualmente"
}
