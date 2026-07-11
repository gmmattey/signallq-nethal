package com.nethal.feature.toolsping

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * Botão circular de voltar compartilhado por [PingScreen] e `PortCheckScreen` (protótipos `4c`/`4f`
 * — mesmo componente de header nas duas telas). Desenhado via `Canvas` em vez de
 * `material-icons-core` (dependência não declarada no projeto) — mesmo padrão já usado por
 * `BackButton` de `:feature:pairing-discovery`; não reaproveitado direto por regra de dependência
 * única entre `:feature:*` (ADR 0002), então reimplementado aqui deliberadamente.
 */
@Composable
internal fun ToolBackButton(onClick: () -> Unit, testTag: String) {
    val chevronColor = MaterialTheme.colorScheme.onBackground
    Canvas(
        modifier = Modifier
            .size(32.dp)
            .background(color = MaterialTheme.colorScheme.surface, shape = CircleShape)
            .border(width = 1.dp, color = MaterialTheme.colorScheme.outline, shape = CircleShape)
            .clickable(onClick = onClick)
            .testTag(testTag),
    ) {
        val strokeWidth = 2.dp.toPx()
        val cx = size.width / 2f
        val cy = size.height / 2f
        val half = size.minDimension * 0.16f
        val path = Path().apply {
            moveTo(cx + half, cy - half * 1.4f)
            lineTo(cx - half, cy)
            lineTo(cx + half, cy + half * 1.4f)
        }
        drawPath(
            path = path,
            color = chevronColor,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}
