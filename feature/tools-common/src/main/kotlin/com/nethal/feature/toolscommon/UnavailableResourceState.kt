package com.nethal.feature.toolscommon

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Estado "recurso indisponível" — componente reutilizável para qualquer ferramenta cuja
 * capability ainda não está disponível (ex.: firmware antigo, capability não suportada pelo
 * driver). Combina o item de lista opaco ([UnavailableResourceListItem]) com o diálogo de
 * explicação ([UnavailableFeatureDialog]), gerenciando a visibilidade do diálogo internamente.
 *
 * Regras (issue #89 / design system seção 1v, protótipo tela `4g`):
 * - Nunca esconde a opção nem a deixa muda ao toque — o item continua tocável e o toque sempre
 *   abre o diálogo explicando o motivo.
 * - Opacidade 38-45% no elemento inteiro (ícone + texto + chevron) — nunca recolorido para um
 *   cinza genérico, os tokens de cor continuam os mesmos.
 * - Definir *quais* ferramentas estão bloqueadas é decisão de cada tela consumidora (capability
 *   engine), fora do escopo deste componente.
 *
 * @param label rótulo da ferramenta/recurso bloqueado (ex.: "Diagnóstico avançado").
 * @param reason motivo da indisponibilidade, mostrado no corpo do diálogo (ex.: "Este diagnóstico
 * requer firmware v2.2+.").
 * @param icon slot para o ícone da ferramenta — cada tela consumidora traz seu próprio ícone
 * (evita acoplar este módulo a um set de ícones específico).
 * @param resolutionLabel rótulo de uma ação de resolução opcional (ex.: "Atualizar firmware").
 * Só é exibida se [onResolutionClick] também for informado.
 * @param onResolutionClick ação executada ao tocar em [resolutionLabel]; o diálogo fecha em
 * seguida.
 */
@Composable
fun UnavailableResourceState(
    label: String,
    reason: String,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit = {},
    dialogTitle: String = "Recurso indisponível",
    resolutionLabel: String? = null,
    onResolutionClick: (() -> Unit)? = null,
) {
    var dialogVisible by remember { mutableStateOf(false) }

    UnavailableResourceListItem(
        label = label,
        onClick = { dialogVisible = true },
        modifier = modifier,
        icon = icon,
    )

    if (dialogVisible) {
        UnavailableFeatureDialog(
            reason = reason,
            onDismissRequest = { dialogVisible = false },
            title = dialogTitle,
            resolutionLabel = resolutionLabel,
            onResolutionClick = onResolutionClick,
        )
    }
}

/**
 * Linha visual do item bloqueado — ícone + rótulo + chevron a 40% de opacidade (faixa 38-45% do
 * design system). Continua clicável: [onClick] deve abrir a explicação do motivo — nunca fica
 * muda ao toque nem desaparece da lista.
 */
@Composable
fun UnavailableResourceListItem(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surface)
            .border(1.dp, colors.outline, RoundedCornerShape(16.dp))
            .clickable(onClickLabel = "$label, recurso indisponível", onClick = onClick)
            .alpha(0.4f)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(modifier = Modifier.size(18.dp)) { icon() }
        Text(
            text = label,
            color = colors.onBackground,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
        ChevronRight(tint = colors.onSurfaceVariant, size = 20.dp)
    }
}

/**
 * Diálogo de explicação do motivo de bloqueio (design system seção 1n — Alertas & diálogos).
 * Reutilizável fora de [UnavailableResourceState] também: uma tela pode disparar este diálogo a
 * partir de um botão ou outro gatilho que não seja um item de lista.
 */
@Composable
fun UnavailableFeatureDialog(
    reason: String,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "Recurso indisponível",
    resolutionLabel: String? = null,
    onResolutionClick: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnClickOutside = true,
            dismissOnBackPress = true,
        ),
    ) {
        Column(
            modifier = modifier
                .widthIn(min = 280.dp, max = 340.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(colors.surfaceVariant)
                .border(1.dp, colors.outline, RoundedCornerShape(24.dp))
                .padding(20.dp),
        ) {
            Text(
                text = title,
                color = colors.onBackground,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = reason,
                color = colors.onSurfaceVariant,
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
            )
            Spacer(modifier = Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                if (resolutionLabel != null && onResolutionClick != null) {
                    Text(
                        text = resolutionLabel,
                        color = colors.primary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clickable {
                                onResolutionClick()
                                onDismissRequest()
                            }
                            .padding(end = 20.dp),
                    )
                }
                Text(
                    text = "Entendi",
                    color = colors.primary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable(onClick = onDismissRequest),
                )
            }
        }
    }
}

/**
 * Chevron outline consistente com `docs/design/assets/icons/dark/chevron-right.svg`
 * (`M9 6l6 6-6 6`, stroke 2, viewBox 24x24) — desenhado por [androidx.compose.foundation.Canvas]
 * em vez de depender de um artefato de ícones que este módulo não usa em nenhum outro lugar.
 */
@Composable
private fun ChevronRight(
    tint: Color,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.size(size)) {
        val scale = this.size.minDimension / 24f
        val path = Path().apply {
            moveTo(9f * scale, 6f * scale)
            lineTo(15f * scale, 12f * scale)
            lineTo(9f * scale, 18f * scale)
        }
        drawPath(
            path = path,
            color = tint,
            style = Stroke(
                width = 2f * scale,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
    }
}
