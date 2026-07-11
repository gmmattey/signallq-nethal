package com.nethal.feature.toolstraceroute.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nethal.core.designsystem.theme.LocalNetHalExtendedColors
import com.nethal.feature.toolscommon.UnavailableResourceState
import com.nethal.feature.toolstraceroute.domain.TracerouteHop

/**
 * Tela Traceroute (issue #92, protótipo `4d` em `docs/design/prototypes.dc.html`). Consome
 * [TracerouteViewModel] (issue #102) — nunca lista hop de exemplo hardcoded como se fosse dado
 * real (critério de aceite da #92): a lista só existe quando [TracerouteRunState.Running]/
 * [TracerouteRunState.Completed] carregam hops de verdade emitidos pelo
 * [com.nethal.feature.toolstraceroute.domain.TracerouteEngine].
 *
 * [onBack] é fornecido por quem monta o grafo (`tracerouteGraph`, em `TracerouteGraph.kt`) — este
 * módulo não conhece a tela de origem (regra de dependência única, ADR 0002; mesmo padrão de
 * `onBack` em `SettingsPrivacyScreen`).
 */
@Composable
fun TracerouteScreen(viewModel: TracerouteViewModel, onBack: () -> Unit) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp)
            .testTag("traceroute_screen"),
    ) {
        TracerouteHeader(onBack = onBack)
        Spacer(modifier = Modifier.height(18.dp))

        when (val state = uiState) {
            TracerouteUiState.CheckingAvailability -> CheckingAvailabilityBody()
            is TracerouteUiState.Unavailable -> UnavailableBody(reason = state.reason)
            is TracerouteUiState.Ready -> ReadyBody(
                state = state,
                onTargetChanged = viewModel::onTargetChanged,
                onExecute = viewModel::execute,
            )
        }
    }
}

@Composable
private fun TracerouteHeader(onBack: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                .clickable(onClickLabel = "Voltar", onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            BackChevron(tint = MaterialTheme.colorScheme.onBackground)
        }
        Text(
            text = "Traceroute",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Composable
private fun CheckingAvailabilityBody() {
    Box(modifier = Modifier.fillMaxSize().testTag("traceroute_checking_availability"), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun UnavailableBody(reason: String) {
    Column(modifier = Modifier.testTag("traceroute_unavailable")) {
        UnavailableResourceState(
            label = "Traceroute",
            reason = reason,
            icon = { TracerouteGlyph(tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        )
    }
}

@Composable
private fun ReadyBody(
    state: TracerouteUiState.Ready,
    onTargetChanged: (String) -> Unit,
    onExecute: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().testTag("traceroute_target_row"),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = state.target,
                onValueChange = onTargetChanged,
                modifier = Modifier.weight(1f).testTag("traceroute_target_input"),
                singleLine = true,
                enabled = !state.isRunning,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.primary,
                ),
            )
            Button(
                onClick = onExecute,
                enabled = !state.isRunning && state.target.isNotBlank(),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier.testTag("traceroute_execute_button"),
            ) {
                Text(if (state.isRunning) "Executando…" else "Executar")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        when (val runState = state.runState) {
            TracerouteRunState.Empty -> EmptyRunBody()
            is TracerouteRunState.Running -> HopList(hops = runState.hops)
            is TracerouteRunState.Completed -> HopList(hops = runState.hops)
        }
    }
}

@Composable
private fun EmptyRunBody() {
    Box(modifier = Modifier.fillMaxSize().testTag("traceroute_empty"), contentAlignment = Alignment.Center) {
        Text(
            text = "Informe um alvo e toque em Executar para rastrear a rota até ele.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HopList(hops: List<TracerouteHop>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
            .testTag("traceroute_hop_list"),
    ) {
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(hops, key = { it.ttl }) { hop ->
                HopRow(hop = hop)
            }
        }
    }
}

@Composable
private fun HopRow(hop: TracerouteHop) {
    val colors = MaterialTheme.colorScheme
    val extended = LocalNetHalExtendedColors.current
    val isFinalTarget = hop is TracerouteHop.Responded && hop.isTarget

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 11.dp)
            .testTag("traceroute_hop_row_${hop.ttl}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HopBadge(ttl = hop.ttl, highlighted = isFinalTarget)

        when (hop) {
            is TracerouteHop.Responded -> {
                val label = if (hop.hostname != null) "${hop.hostname} (${hop.address})" else hop.address
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isFinalTarget) colors.primary else colors.onBackground,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${hop.rttMillis} ms",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isFinalTarget) colors.primary else colors.onSurfaceVariant,
                )
            }
            is TracerouteHop.TimedOut -> {
                Text(
                    text = "* * *",
                    style = MaterialTheme.typography.bodyMedium,
                    color = extended.warning,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "esgotado",
                    style = MaterialTheme.typography.bodySmall,
                    color = extended.warning,
                )
            }
        }
    }
}

@Composable
private fun HopBadge(ttl: Int, highlighted: Boolean) {
    val colors = MaterialTheme.colorScheme
    val background = if (highlighted) colors.primary.copy(alpha = 0.16f) else colors.background
    val textColor = if (highlighted) colors.primary else colors.onSurfaceVariant
    Box(
        modifier = Modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(background)
            .then(if (highlighted) Modifier else Modifier.border(1.dp, colors.outline, CircleShape)),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = ttl.toString(), style = MaterialTheme.typography.labelSmall, color = textColor, fontWeight = FontWeight.SemiBold)
    }
}

/** Seta de "voltar" (`M15 6l-6 6 6 6`, mesmo estilo do chevron de `docs/design/assets/icons`) — desenhada em Canvas, sem depender de `material-icons-extended` (não é dependência do projeto). */
@Composable
private fun BackChevron(tint: Color) {
    Canvas(modifier = Modifier.size(16.dp)) {
        val scale = size.minDimension / 24f
        val path = Path().apply {
            moveTo(15f * scale, 6f * scale)
            lineTo(9f * scale, 12f * scale)
            lineTo(15f * scale, 18f * scale)
        }
        drawPath(path = path, color = tint, style = Stroke(width = 2f * scale, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Ícone de traceroute (3 pontos conectados por linhas, mesmo desenho do protótipo `4d`/menu de Ferramentas). */
@Composable
private fun TracerouteGlyph(tint: Color) {
    Canvas(modifier = Modifier.size(19.dp)) {
        val scale = size.minDimension / 24f
        fun point(x: Float, y: Float) = Offset(x * scale, y * scale)

        val p1 = point(5f, 7f)
        val p2 = point(12f, 12f)
        val p3 = point(19f, 17f)

        drawLine(color = tint, start = p1, end = p2, strokeWidth = 1.6f * scale, cap = StrokeCap.Round)
        drawLine(color = tint, start = p2, end = p3, strokeWidth = 1.6f * scale, cap = StrokeCap.Round)
        drawCircle(color = tint, radius = 1.6f * scale, center = p1)
        drawCircle(color = tint, radius = 1.6f * scale, center = p2)
        drawCircle(color = tint, radius = 1.6f * scale, center = p3)
    }
}
