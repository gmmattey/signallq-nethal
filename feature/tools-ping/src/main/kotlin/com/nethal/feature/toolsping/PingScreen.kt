package com.nethal.feature.toolsping

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nethal.core.model.LatencyProbeStats
import com.nethal.feature.toolscommon.UnavailableFeatureDialog

/**
 * Tela "Ping" (issue #91, protótipo `4c`) — a medição real é RTT via TCP connect
 * ([com.nethal.core.protocol.tcp.LatencyMeasurer], issue #99), nunca ICMP. O rótulo da tela
 * permanece "Ping" (mesmo nome do protótipo/menu), mas o corpo nunca promete um ping ICMP no
 * sentido estrito — o subtítulo e o rótulo "TCP" na linha de resultado deixam isso explícito (nota
 * de copy da issue #91, alinhada com a Vera).
 *
 * Alvo restrito à LAN local (guard obrigatório em [com.nethal.core.protocol.tcp.TcpProbe]) — um IP
 * público digitado no campo é aceito na UI, mas rejeitado na execução com [PingUiState.Ready.errorMessage]
 * honesto, nunca uma tentativa de conexão de verdade.
 */
@Composable
fun PingScreen(
    viewModel: PingViewModel,
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()

    when (val state = uiState) {
        is PingUiState.Unavailable -> UnavailableBody(state, onBack)
        is PingUiState.Ready -> ReadyBody(
            state = state,
            onBack = onBack,
            onTargetHostChanged = viewModel::onTargetHostChanged,
            onRun = viewModel::run,
        )
    }
}

@Composable
private fun UnavailableBody(state: PingUiState.Unavailable, onBack: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().testTag("ping_unavailable")) {
        Column(modifier = Modifier.fillMaxWidth().padding(22.dp)) {
            ScreenHeader(onBack = onBack)
        }
        UnavailableFeatureDialog(
            reason = state.reason,
            onDismissRequest = onBack,
        )
    }
}

@Composable
private fun ReadyBody(
    state: PingUiState.Ready,
    onBack: () -> Unit,
    onTargetHostChanged: (String) -> Unit,
    onRun: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(22.dp)
            .verticalScroll(rememberScrollState())
            .testTag("ping_screen"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenHeader(onBack = onBack)

        Text(
            text = "Mede o tempo de resposta (RTT) via TCP até um endereço da sua rede local — não é " +
                "um ping ICMP tradicional.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = state.targetHost,
                onValueChange = onTargetHostChanged,
                modifier = Modifier.weight(1f).testTag("ping_target_field"),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                label = { Text("IP na rede local") },
            )
            Button(
                onClick = onRun,
                enabled = !state.isRunning && state.targetHost.isNotBlank(),
                modifier = Modifier.testTag("ping_run_button"),
            ) {
                if (state.isRunning) {
                    CircularProgressIndicator(modifier = Modifier.width(16.dp), strokeWidth = 2.dp)
                } else {
                    Text("Executar")
                }
            }
        }

        state.errorMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("ping_error"),
            )
        }

        state.result?.let { stats ->
            PingResultBody(stats)
        }
    }
}

@Composable
private fun ScreenHeader(onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ToolBackButton(onClick = onBack, testTag = "ping_back_button")
        Text(
            text = "Ping",
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
        )
    }
}

@Composable
private fun PingResultBody(stats: LatencyProbeStats) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Card(
            modifier = Modifier.fillMaxWidth().testTag("ping_result_log"),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                stats.samples.forEachIndexed { index, sample ->
                    val text = if (sample.roundTripMillis != null) {
                        "Resposta de ${stats.targetHost}: tempo=${sample.roundTripMillis} ms"
                    } else {
                        "Sem resposta de ${stats.targetHost} (tentativa ${index + 1})"
                    }
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            StatCell(label = "ENVIADOS", value = stats.packetsSent.toString(), modifier = Modifier.weight(1f))
            StatCell(label = "RECEBIDOS", value = stats.packetsReceived.toString(), modifier = Modifier.weight(1f))
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            StatCell(
                label = "PERDA",
                value = "${stats.packetLossPercent.toInt()}%",
                modifier = Modifier.weight(1f),
            )
            StatCell(
                label = "MÉDIA",
                value = stats.averageRoundTripMillis?.let { "${it.toInt()} ms" } ?: "—",
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.padding(top = 2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}
