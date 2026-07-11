package com.nethal.feature.toolsdns

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nethal.core.designsystem.theme.LocalNetHalExtendedColors
import com.nethal.core.model.DnsRecordAnswer
import com.nethal.feature.toolscommon.UnavailableResourceListItem
import com.nethal.feature.toolscommon.UnavailableFeatureDialog

/** Test tags estáveis para Compose UI Test. */
object DnsLookupScreenTestTags {
    const val SCREEN = "tools_dns_lookup_screen"
    const val BACK_BUTTON = "tools_dns_lookup_back"
    const val HOSTNAME_FIELD = "tools_dns_lookup_hostname_field"
    const val EXECUTE_BUTTON = "tools_dns_lookup_execute"
    const val RESULT_CARD = "tools_dns_lookup_result_card"
    const val NO_NETWORK = "tools_dns_lookup_no_network"
}

/**
 * Tela `4e` — Ferramentas: DNS Lookup (issue #93). Consome [viewModel] real, nunca dado de
 * exemplo — estado [DnsLookupUiState.Idle] fica em branco até o usuário executar uma consulta de
 * verdade.
 */
@Composable
fun DnsLookupScreen(
    viewModel: DnsLookupViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hostname by viewModel.hostname.collectAsState()
    val state by viewModel.uiState.collectAsState()
    val colors = MaterialTheme.colorScheme

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(horizontal = 26.dp, vertical = 24.dp)
            .testTag(DnsLookupScreenTestTags.SCREEN),
    ) {
        DnsLookupTopBar(onBack = onBack)

        Spacer(modifier = Modifier.height(18.dp))

        if (state is DnsLookupUiState.NoNetwork) {
            DnsLookupNoNetworkContent()
            return@Column
        }

        DnsLookupInputRow(
            hostname = hostname,
            enabled = state !is DnsLookupUiState.Loading,
            onHostnameChanged = viewModel::onHostnameChanged,
            onExecute = viewModel::execute,
        )

        Spacer(modifier = Modifier.height(16.dp))

        when (val current = state) {
            DnsLookupUiState.Idle -> DnsLookupHint()
            DnsLookupUiState.Loading -> DnsLookupLoading()
            is DnsLookupUiState.Success -> DnsLookupResultCard(
                hostname = current.result.hostname,
                answers = current.result.answers,
                serverLabel = current.result.serverLabel,
                elapsedMillis = current.result.elapsedMillis,
            )
            is DnsLookupUiState.Error -> DnsLookupErrorContent(reason = current.reason)
            DnsLookupUiState.NoNetwork -> Unit // tratado acima, return@Column já saiu da composição
        }
    }
}

@Composable
private fun DnsLookupTopBar(onBack: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(colors.surface)
                .border(1.dp, colors.outline, CircleShape)
                .clickable(onClickLabel = "Voltar", onClick = onBack)
                .testTag(DnsLookupScreenTestTags.BACK_BUTTON),
            contentAlignment = Alignment.Center,
        ) {
            ChevronLeftGlyph(tint = colors.onBackground, modifier = Modifier.size(16.dp))
        }

        Spacer(modifier = Modifier.size(10.dp))

        Text(
            text = "DNS Lookup",
            color = colors.onBackground,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun DnsLookupInputRow(
    hostname: String,
    enabled: Boolean,
    onHostnameChanged: (String) -> Unit,
    onExecute: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = hostname,
            onValueChange = onHostnameChanged,
            enabled = enabled,
            singleLine = true,
            placeholder = { Text("ex: nethal.com.br") },
            modifier = Modifier
                .weight(1f)
                .testTag(DnsLookupScreenTestTags.HOSTNAME_FIELD),
        )

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(if (enabled) colors.primary else colors.surfaceVariant)
                .clickable(enabled = enabled, onClickLabel = "Executar", onClick = onExecute)
                .padding(horizontal = 18.dp, vertical = 14.dp)
                .testTag(DnsLookupScreenTestTags.EXECUTE_BUTTON),
        ) {
            Text(
                text = "Executar",
                color = if (enabled) colors.onPrimary else colors.onSurfaceVariant,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun DnsLookupHint() {
    Text(
        text = "Informe um hostname e toque em Executar para consultar os registros A e AAAA.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 12.5.sp,
    )
}

@Composable
private fun DnsLookupLoading() {
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun DnsLookupErrorContent(reason: String) {
    Text(
        text = reason,
        color = LocalNetHalExtendedColors.current.error,
        fontSize = 12.5.sp,
    )
}

@Composable
private fun DnsLookupResultCard(
    hostname: String,
    answers: List<DnsRecordAnswer>,
    serverLabel: String,
    elapsedMillis: Long,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(colors.surface)
            .border(1.dp, colors.outline, RoundedCornerShape(18.dp))
            .testTag(DnsLookupScreenTestTags.RESULT_CARD),
    ) {
        answers.forEach { answer ->
            DnsLookupResultRow(
                label = answer.type.label,
                value = answer.values.joinToString(", ").ifBlank { "sem registro" },
            )
            HorizontalDivider(color = colors.outline, thickness = 1.dp)
        }
        DnsLookupResultRow(label = "Servidor DNS", value = serverLabel)
        HorizontalDivider(color = colors.outline, thickness = 1.dp)
        DnsLookupResultRow(
            label = "Tempo de resposta",
            value = "$elapsedMillis ms",
            valueColor = LocalNetHalExtendedColors.current.success,
        )
    }
}

@Composable
private fun DnsLookupResultRow(label: String, value: String, valueColor: Color? = null) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, color = colors.onSurfaceVariant, fontSize = 12.5.sp)
        Text(text = value, color = valueColor ?: colors.onBackground, fontSize = 12.5.sp)
    }
}

@Composable
private fun DnsLookupNoNetworkContent() {
    var showDialog by remember { mutableStateOf(false) }
    val reason = "DNS Lookup consulta um resolvedor DNS público pela internet — conecte-se a " +
        "uma rede com acesso à internet para usar esta ferramenta."

    UnavailableResourceListItem(
        label = "DNS Lookup",
        onClick = { showDialog = true },
        modifier = Modifier
            .fillMaxWidth()
            .testTag(DnsLookupScreenTestTags.NO_NETWORK),
    )

    if (showDialog) {
        UnavailableFeatureDialog(
            reason = reason,
            onDismissRequest = { showDialog = false },
        )
    }
}

/** Chevron esquerdo — espelho de `UnavailableResourceState.ChevronRight` (`:feature:tools-common`), este módulo não a importa por ser `private`. */
@Composable
private fun ChevronLeftGlyph(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val scale = this.size.minDimension / 24f
        val path = Path().apply {
            moveTo(15f * scale, 6f * scale)
            lineTo(9f * scale, 12f * scale)
            lineTo(15f * scale, 18f * scale)
        }
        drawPath(
            path = path,
            color = tint,
            style = Stroke(width = 2f * scale, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}
