package com.nethal.feature.pairingdiscovery.discovery

import com.nethal.core.designsystem.theme.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nethal.core.model.NetworkTarget
import com.nethal.core.model.TargetRole
import com.nethal.core.model.TargetSource
import com.nethal.feature.pairingdiscovery.manualentry.ManualIpField

/**
 * Tela 2c — Múltiplos equipamentos encontrados (spec §11, SIG-318). Exibida quando
 * `devices.size > 1` ou `possibleDoubleNat == true`. Lista candidatos com papel e origem, avisa
 * sobre indício de duplo NAT e permite adicionar um equipamento manualmente por IP. Lógica e
 * fluxo preservados — não faz parte do cluster de seleção manual (2g/2h/2i), é desambiguação
 * entre candidatos já descobertos (decisão registrada na spec de #80).
 */
@Composable
fun MultipleCandidatesScreen(
    state: DiscoveryUiState.MultipleCandidates,
    manualTargetError: String?,
    onCandidateChosen: (NetworkTarget) -> Unit,
    onAddManualTarget: (String) -> Unit,
) {
    var manualIp by remember { mutableStateOf("") }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Encontramos mais de um equipamento",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
            )

            Text(
                text = "Escolha qual equipamento você quer testar.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
            )

            if (state.possibleDoubleNat) {
                Text(
                    text = "Pode haver um equipamento adicional entre você e a internet " +
                        "(ex.: ONT da operadora).",
                    color = LocalNetHalExtendedColors.current.warning,
                    fontSize = 12.5.sp,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.devices.forEach { device ->
                    CandidateCard(device = device, onClick = { onCandidateChosen(device) })
                }
            }

            Text(
                text = "Ou informe outro equipamento manualmente:",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )

            ManualIpField(
                value = manualIp,
                onValueChange = { manualIp = it },
                error = manualTargetError,
                onSubmit = { onAddManualTarget(manualIp) },
            )
        }
    }
}

@Composable
private fun CandidateCard(device: NetworkTarget, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(20.dp))
            .border(width = 1.dp, color = MaterialTheme.colorScheme.outline, shape = RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text = device.ip, color = MaterialTheme.colorScheme.onBackground, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
        Text(text = roleLabel(device.role), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.5.sp)
        Text(
            text = "Origem: ${sourceLabel(device.source)}",
            color = LocalNetHalExtendedColors.current.onSurfaceTertiary,
            fontSize = 11.sp,
        )
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
