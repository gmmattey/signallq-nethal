package com.nethal.feature.toolsspeedtest

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nethal.core.designsystem.theme.LocalNetHalExtendedColors
import com.nethal.core.model.SpeedtestBottleneck
import com.nethal.core.model.SpeedtestPhase
import com.nethal.core.model.SpeedtestResult
import com.nethal.core.model.SpeedtestUsageVerdict
import com.nethal.feature.toolscommon.UnavailableResourceState
import kotlin.math.roundToInt

/**
 * Tela "Teste de velocidade" (issue #90, protótipos `4a` testando / `4b` resultado,
 * `docs/design/prototypes.dc.html`). Um único composable troca de corpo por estado
 * ([SpeedtestUiState]), mesmo padrão de `StatusScreen` — não são duas rotas separadas.
 */
@Composable
fun SpeedtestScreen(
    viewModel: SpeedtestViewModel,
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 22.dp, vertical = 14.dp)
                .testTag("speedtest_screen"),
        ) {
            SpeedtestHeader(onBack = onBack)
            Spacer(modifier = Modifier.height(8.dp))

            when (val state = uiState) {
                is SpeedtestUiState.Idle -> IdleBody(onStart = { viewModel.startTest() })
                is SpeedtestUiState.NoConnectivity -> NoConnectivityBody(state)
                is SpeedtestUiState.Running -> RunningBody(state, onCancel = viewModel::cancelTest)
                is SpeedtestUiState.Done -> DoneBody(state.result, onRetry = { viewModel.startTest() })
                is SpeedtestUiState.Error -> ErrorBody(state.message, onRetry = { viewModel.startTest() })
            }
        }
    }
}

@Composable
private fun SpeedtestHeader(onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onClick = onBack, modifier = Modifier.testTag("speedtest_back")) {
            Text("Voltar")
        }
        Text(
            text = "Teste de velocidade",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
        )
    }
}

@Composable
private fun IdleBody(onStart: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Mede download, upload, ping e jitter da sua conexão contra a rede da Cloudflare.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onStart, modifier = Modifier.testTag("speedtest_start_button")) {
            Text("Iniciar teste")
        }
    }
}

@Composable
private fun NoConnectivityBody(state: SpeedtestUiState.NoConnectivity) {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        UnavailableResourceState(
            label = "Teste de velocidade",
            reason = state.reason,
            modifier = Modifier.testTag("speedtest_no_connectivity"),
        )
    }
}

@Composable
private fun RunningBody(state: SpeedtestUiState.Running, onCancel: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().testTag("speedtest_running"),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        RingGauge(
            fraction = state.progressPercent / 100f,
            color = MaterialTheme.colorScheme.primary,
            centerLabel = "${state.liveMbps.roundToInt()}",
            centerCaption = "Mbps",
        )
        Spacer(modifier = Modifier.height(20.dp))
        Text(text = phaseLabel(state.phase, state.currentRound), style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            PhaseStep(label = "Ping", done = state.phase.ordinal > SpeedtestPhase.LATENCY.ordinal, active = state.phase == SpeedtestPhase.LATENCY)
            PhaseStep(label = "Download", done = state.phase.ordinal > SpeedtestPhase.DOWNLOAD.ordinal, active = state.phase == SpeedtestPhase.DOWNLOAD)
            PhaseStep(label = "Upload", done = state.phase.ordinal > SpeedtestPhase.UPLOAD.ordinal, active = state.phase == SpeedtestPhase.UPLOAD)
        }
        Spacer(modifier = Modifier.height(28.dp))
        OutlinedButton(onClick = onCancel, modifier = Modifier.testTag("speedtest_cancel_button")) {
            Text("Cancelar")
        }
    }
}

private fun phaseLabel(phase: SpeedtestPhase, round: Int): String {
    val roundSuffix = if (round > 0) " (rodada $round/3)" else ""
    return when (phase) {
        SpeedtestPhase.IDLE -> "Preparando…$roundSuffix"
        SpeedtestPhase.LATENCY -> "Testando ping…$roundSuffix"
        SpeedtestPhase.DOWNLOAD -> "Testando download…$roundSuffix"
        SpeedtestPhase.UPLOAD -> "Testando upload…$roundSuffix"
        SpeedtestPhase.DONE -> "Concluído$roundSuffix"
    }
}

@Composable
private fun PhaseStep(label: String, done: Boolean, active: Boolean) {
    val colors = LocalNetHalExtendedColors.current
    val color = when {
        done -> colors.success
        active -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .background(color = color, shape = CircleShape),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (active || done) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DoneBody(result: SpeedtestResult, onRetry: () -> Unit) {
    val colors = LocalNetHalExtendedColors.current
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("speedtest_done"),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            RingGauge(
                fraction = overallRingFraction(result),
                color = colors.success,
                centerLabel = "",
                centerCaption = "",
                sizeDp = 104,
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(text = overallLabel(result), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard(label = "Download", value = "${result.downloadMbps.roundToInt()} Mbps", accent = true, modifier = Modifier.weight(1f))
            MetricCard(label = "Upload", value = "${result.uploadMbps.roundToInt()} Mbps", accent = true, modifier = Modifier.weight(1f))
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard(label = "Ping", value = "${result.latencyMs.roundToInt()} ms", accent = false, modifier = Modifier.weight(1f))
            MetricCard(label = "Jitter", value = "${result.jitterMs.roundToInt()} ms", accent = false, modifier = Modifier.weight(1f))
        }

        BottleneckCard(result)

        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().testTag("speedtest_retry_button")) {
            Text("Testar novamente")
        }
    }
}

@Composable
private fun BottleneckCard(result: SpeedtestResult) {
    val bottleneck = result.qualityDiagnosis.primaryBottleneck
    if (bottleneck == SpeedtestBottleneck.NONE) return
    Card(
        modifier = Modifier.fillMaxWidth().testTag("speedtest_bottleneck_card"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = "Gargalo provável", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text = bottleneckLabel(bottleneck), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun bottleneckLabel(bottleneck: SpeedtestBottleneck): String = when (bottleneck) {
    SpeedtestBottleneck.NONE -> ""
    SpeedtestBottleneck.LATENCY -> "Latência alta — jogos e videochamadas sentem mais que streaming."
    SpeedtestBottleneck.UPLOAD -> "Upload baixo — envio de arquivo grande e videochamada em grupo sofrem primeiro."
    SpeedtestBottleneck.BUFFERBLOAT -> "Bufferbloat — a conexão engasga sob carga mesmo com boa velocidade de pico."
    SpeedtestBottleneck.PACKET_LOSS -> "Perda de pacotes acima do esperado — instabilidade em chamadas e jogos online."
}

/** Rótulo geral derivado dos vereditos reais de [com.nethal.core.model.SpeedtestQualityDiagnosis] — nunca um número de exemplo, ver KDoc de [overallRingFraction]. */
private fun overallLabel(result: SpeedtestResult): String {
    val verdicts = result.qualityDiagnosis.let { listOf(it.streamingVerdict, it.gamingVerdict, it.videoCallVerdict) }
    return when {
        verdicts.all { it == SpeedtestUsageVerdict.GOOD } -> "Excelente"
        verdicts.none { it == SpeedtestUsageVerdict.POOR } -> "Boa"
        verdicts.any { it == SpeedtestUsageVerdict.GOOD } -> "Limitada"
        else -> "Ruim"
    }
}

/**
 * Preenchimento do anel de resultado: média dos 3 vereditos reais (streaming/gamer/videochamada),
 * cada um valendo 1/3 (ruim), 2/3 (aceitável) ou 3/3 (boa) — não é um "score" fabricado com casa
 * decimal de precisão que a engine não calcula; é a mesma classificação já exposta em
 * [com.nethal.core.model.SpeedtestQualityDiagnosis], só desenhada como anel em vez de 3 linhas de
 * texto. Decisão registrada no PR de #90: o protótipo mostra um número de 0-100 (`96`), mas nada em
 * #98 pede esse formato de pontuação — inventar um número preciso ali seria dado falso.
 */
private fun overallRingFraction(result: SpeedtestResult): Float {
    val verdicts = result.qualityDiagnosis.let { listOf(it.streamingVerdict, it.gamingVerdict, it.videoCallVerdict) }
    val points: Int = verdicts.map { verdict ->
        when (verdict) {
            SpeedtestUsageVerdict.GOOD -> 3
            SpeedtestUsageVerdict.ACCEPTABLE -> 2
            SpeedtestUsageVerdict.POOR -> 1
        }
    }.sum()
    return points / 9f
}

@Composable
private fun MetricCard(label: String, value: String, accent: Boolean, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.testTag("speedtest_metric_${label.lowercase()}"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
            )
        }
    }
}

@Composable
private fun ErrorBody(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().testTag("speedtest_error"),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Não foi possível concluir o teste",
            style = MaterialTheme.typography.titleMedium,
            color = LocalNetHalExtendedColors.current.error,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(20.dp))
        Button(onClick = onRetry, modifier = Modifier.testTag("speedtest_error_retry_button")) {
            Text("Tentar novamente")
        }
    }
}

/** Anel de progresso/resultado (protótipos `4a`/`4b`) — arco determinístico sobre dado real, nunca decorativo/infinito. */
@Composable
private fun RingGauge(
    fraction: Float,
    color: Color,
    centerLabel: String,
    centerCaption: String,
    sizeDp: Int = 160,
) {
    val trackColor = MaterialTheme.colorScheme.outline
    Box(
        modifier = Modifier.size(sizeDp.dp).aspectRatio(1f),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = size.minDimension * 0.045f
            val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
            val topLeft = androidx.compose.ui.geometry.Offset(strokeWidth / 2, strokeWidth / 2)
            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = 360f * fraction.coerceIn(0f, 1f),
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
        }
        if (centerLabel.isNotEmpty()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = centerLabel, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold))
                if (centerCaption.isNotEmpty()) {
                    Text(text = centerCaption, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
