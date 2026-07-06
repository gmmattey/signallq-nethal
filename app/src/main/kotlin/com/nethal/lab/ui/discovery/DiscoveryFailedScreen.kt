package com.nethal.lab.ui.discovery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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

/**
 * Tela 2b — Falha na descoberta (spec §11, SIG-319). Exibida quando a lista de candidatos
 * vem vazia: sem gateway identificável (AP isolation, VPN ativa, rede sem gateway) ou
 * permissão de localização negada.
 */
@Composable
fun DiscoveryFailedScreen(
    reason: FailureReason,
    manualTargetError: String?,
    onRetry: () -> Unit,
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
                text = "Não foi possível encontrar sua rede",
                style = MaterialTheme.typography.headlineSmall,
            )

            Text(
                text = probableReasonText(reason),
                style = MaterialTheme.typography.bodyLarge,
            )

            Text(
                text = "O que tentar:",
                style = MaterialTheme.typography.titleMedium,
            )

            Text(
                text = suggestionsText(reason),
                style = MaterialTheme.typography.bodyMedium,
            )

            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                Text("Tentar novamente")
            }

            Text(
                text = "Ou informe o IP do gateway manualmente:",
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

private fun probableReasonText(reason: FailureReason): String = when (reason) {
    FailureReason.NO_GATEWAY_FOUND ->
        "Não conseguimos identificar um gateway válido na sua rede. Isso pode acontecer " +
            "quando o roteador isola os dispositivos (AP isolation), há uma VPN ativa, ou a " +
            "rede não tem um gateway padrão identificável."

    FailureReason.NOT_ON_WIFI ->
        "Seu aparelho não parece estar conectado a uma rede Wi-Fi. A descoberta de rede " +
            "local só funciona em Wi-Fi."

    FailureReason.LOCATION_PERMISSION_DENIED ->
        "Sem a permissão de localização, o Android não libera os dados de Wi-Fi " +
            "necessários para identificar o gateway."
}

private fun suggestionsText(reason: FailureReason): String = when (reason) {
    FailureReason.LOCATION_PERMISSION_DENIED ->
        "• Conceda a permissão de localização nas configurações do app\n" +
            "• Ou informe o IP do gateway manualmente abaixo"

    else ->
        "• Desative a VPN, se houver uma ativa\n" +
            "• Troque de rede Wi-Fi, se possível\n" +
            "• Informe o IP do gateway manualmente abaixo"
}
