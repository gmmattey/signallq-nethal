package com.nethal.feature.toolsping

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nethal.core.designsystem.theme.LocalNetHalExtendedColors
import com.nethal.core.model.PortCheckOutcome
import com.nethal.core.model.PortCheckStatus
import com.nethal.feature.toolscommon.UnavailableFeatureDialog

/**
 * Tela "Verificação de porta" (issue #94, protótipo `4f`) sobre a capability `CHECK_PORT`
 * ([com.nethal.core.protocol.tcp.PortChecker], issue #100) — TCP connect a um host+porta da LAN
 * local.
 *
 * Escopo estritamente LAN (guard obrigatório em [com.nethal.core.protocol.tcp.TcpProbe],
 * `CLAUDE.md` "Escopo fora do MVP") — o campo aceita texto livre (não há como restringir teclado a
 * "só IP privado"), mas a execução recusa qualquer alvo fora de RFC 1918/loopback com
 * [PortCheckUiState.Ready.errorMessage] honesto, nunca uma tentativa de conexão de verdade contra a
 * internet.
 */
@Composable
fun PortCheckScreen(
    viewModel: PortCheckViewModel,
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()

    when (val state = uiState) {
        is PortCheckUiState.Unavailable -> UnavailableBody(state, onBack)
        is PortCheckUiState.Ready -> ReadyBody(
            state = state,
            onBack = onBack,
            onTargetHostChanged = viewModel::onTargetHostChanged,
            onPortChanged = viewModel::onPortChanged,
            onRun = viewModel::run,
        )
    }
}

@Composable
private fun UnavailableBody(state: PortCheckUiState.Unavailable, onBack: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().testTag("port_check_unavailable")) {
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
    state: PortCheckUiState.Ready,
    onBack: () -> Unit,
    onTargetHostChanged: (String) -> Unit,
    onPortChanged: (String) -> Unit,
    onRun: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(22.dp)
            .verticalScroll(rememberScrollState())
            .testTag("port_check_screen"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenHeader(onBack = onBack)

        Text(
            text = "Verifica se uma porta TCP está aberta num endereço da sua rede local — restrito à " +
                "própria LAN, nunca a um host externo.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = state.targetHost,
                onValueChange = onTargetHostChanged,
                modifier = Modifier.weight(2f).testTag("port_check_host_field"),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                label = { Text("IP na rede local") },
            )
            OutlinedTextField(
                value = state.port,
                onValueChange = onPortChanged,
                modifier = Modifier.weight(1f).testTag("port_check_port_field"),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                label = { Text("Porta") },
            )
        }

        state.errorMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("port_check_error"),
            )
        }

        state.result?.let { outcome ->
            PortCheckResultBody(outcome)
        }

        val buttonLabel = if (state.result != null) "Verificar novamente" else "Verificar"
        Button(
            onClick = onRun,
            enabled = !state.isRunning,
            modifier = Modifier.fillMaxWidth().testTag("port_check_run_button"),
        ) {
            if (state.isRunning) {
                CircularProgressIndicator(modifier = Modifier.width(16.dp), strokeWidth = 2.dp)
            } else {
                Text(buttonLabel)
            }
        }
    }
}

@Composable
private fun ScreenHeader(onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ToolBackButton(onClick = onBack, testTag = "port_check_back_button")
        Text(
            text = "Verificação de porta",
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
        )
    }
}

@Composable
private fun PortCheckResultBody(outcome: PortCheckOutcome) {
    val extendedColors = LocalNetHalExtendedColors.current
    val (statusColor, statusLabel) = when (outcome.status) {
        PortCheckStatus.OPEN -> extendedColors.success to "Porta ${outcome.port} aberta"
        PortCheckStatus.CLOSED -> extendedColors.error to "Porta ${outcome.port} fechada"
        PortCheckStatus.TIMED_OUT -> extendedColors.warning to "Porta ${outcome.port}: tempo esgotado"
    }

    Column(
        modifier = Modifier.fillMaxWidth().testTag("port_check_result"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StatusBadge(color = statusColor, status = outcome.status)
        Text(
            text = statusLabel,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        )
        Text(
            text = "TCP" + (outcome.elapsedMillis?.let { " · latência $it ms" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatusBadge(color: Color, status: PortCheckStatus) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .background(color = color.copy(alpha = 0.12f), shape = CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(28.dp)) {
            val path = Path()
            when (status) {
                PortCheckStatus.OPEN -> {
                    path.moveTo(size.width * 0.2f, size.height * 0.55f)
                    path.lineTo(size.width * 0.42f, size.height * 0.75f)
                    path.lineTo(size.width * 0.8f, size.height * 0.3f)
                }
                PortCheckStatus.CLOSED -> {
                    path.moveTo(size.width * 0.25f, size.height * 0.25f)
                    path.lineTo(size.width * 0.75f, size.height * 0.75f)
                    path.moveTo(size.width * 0.75f, size.height * 0.25f)
                    path.lineTo(size.width * 0.25f, size.height * 0.75f)
                }
                PortCheckStatus.TIMED_OUT -> {
                    path.moveTo(size.width * 0.5f, size.height * 0.2f)
                    path.lineTo(size.width * 0.5f, size.height * 0.55f)
                    path.moveTo(size.width * 0.5f, size.height * 0.55f)
                    path.lineTo(size.width * 0.7f, size.height * 0.68f)
                }
            }
            drawPath(
                path = path,
                color = color,
                style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
    }
}
