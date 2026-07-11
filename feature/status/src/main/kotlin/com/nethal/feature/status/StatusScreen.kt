package com.nethal.feature.status

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.nethal.core.designsystem.theme.LocalNetHalExtendedColors

/**
 * Tela Status (issue #83) — destino permanente da bottom nav, dado ao vivo (mecanismo de
 * atualização em [StatusViewModel], issue #107).
 *
 * O que migrou da extinta `CapabilitiesScreen`/`ReportScreen` (decisão
 * `docs/product/decisions/0001-telas-orfas-redesenho.md`): nenhuma lista bruta de `CapabilityId` —
 * o card de equipamento/Wi-Fi abaixo mostra o mesmo dado que `CapabilitiesScreen` lia
 * (`READ_DEVICE_INFO`, `READ_WIFI_STATUS`, `READ_WAN_STATUS`), só que como cards de leitura contínua
 * em vez de enumeração técnica de uma sessão única — não existe mais tela de relatório separada.
 *
 * `DisposableEffect` inicia o poll ao entrar em composição e para+encerra a sessão ao sair — inclui
 * troca de aba na bottom nav, que remove este composable da árvore mesmo com o `ViewModel`
 * sobrevivendo (ver KDoc de [StatusViewModel]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusScreen(viewModel: StatusViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()

    DisposableEffect(Unit) {
        viewModel.onScreenStarted()
        onDispose { viewModel.onScreenStopped() }
    }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = { viewModel.refreshNow() },
        modifier = Modifier
            .fillMaxSize()
            .testTag("status_screen"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(text = "Visão geral", style = MaterialTheme.typography.headlineLarge)

            when (val state = uiState) {
                is StatusUiState.Loading -> LoadingBody()
                is StatusUiState.SessionUnavailable -> SessionUnavailableBody(state)
                is StatusUiState.Loaded -> LoadedBody(state)
            }
        }
    }
}

@Composable
private fun LoadingBody() {
    Text(
        text = "Lendo status ao vivo do equipamento...",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.testTag("status_loading"),
    )
}

@Composable
private fun SessionUnavailableBody(state: StatusUiState.SessionUnavailable) {
    // Mesmo padrão de shape/cor dos demais cards desta tela (26dp sobre `colorScheme.surface`) —
    // `Card()` sem esses parâmetros usa o default M3, que destoa do resto da tela.
    Card(
        modifier = Modifier.fillMaxWidth().testTag("status_session_unavailable"),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text = "Sessão indisponível", style = MaterialTheme.typography.titleMedium)
            Text(text = state.reason, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun LoadedBody(state: StatusUiState.Loaded) {
    EquipmentAndWifiCard(state)
    when (val variant = state.variant) {
        is StatusVariant.Ont -> {
            OpticalSignalCard(variant.signal)
            GponErrorCountersCard(variant.gponErrors)
            LanPortsCard(variant.lanPorts)
        }
        is StatusVariant.Mesh -> MeshTopologyCard(variant.topology)
        null -> Unit
    }
    SpeedCard(state.speed)
    NativeAdSlot()
}

/** Card combinado equipamento + Wi-Fi, mesma composição do componente do design system ("Componentes" 1h/1i). */
@Composable
private fun EquipmentAndWifiCard(state: StatusUiState.Loaded) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("status_equipment_card"),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            StatusRow(
                title = state.equipmentLabel,
                detail = state.equipmentDetail,
                dot = state.equipmentDot,
                testTag = "status_equipment_row",
            )
            if (state.wifi != null) {
                StatusRow(
                    title = state.wifi.label,
                    detail = state.wifi.detail,
                    dot = state.wifi.dot,
                    testTag = "status_wifi_row",
                )
            }
            state.publicIp?.let { ip ->
                StatusRow(
                    title = "IP público",
                    detail = ip,
                    dot = null,
                    testTag = "status_wan_row",
                )
            }
        }
    }
}

@Composable
private fun StatusRow(title: String, detail: String?, dot: StatusDotLevel?, testTag: String) {
    Row(
        modifier = Modifier.fillMaxWidth().testTag(testTag),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            if (!detail.isNullOrBlank()) {
                Text(text = detail, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (dot != null) {
            StatusDot(level = dot)
        }
    }
}

@Composable
private fun StatusDot(level: StatusDotLevel) {
    val extendedColors = LocalNetHalExtendedColors.current
    val color = when (level) {
        StatusDotLevel.OK -> extendedColors.success
        StatusDotLevel.WARNING -> extendedColors.warning
        StatusDotLevel.ERROR -> MaterialTheme.colorScheme.error
    }
    Box(
        modifier = Modifier
            .size(7.dp)
            .clip(CircleShape)
            .background(color)
            .testTag("status_dot_${level.name.lowercase()}"),
    )
}

/** Tile de métrica rotulada (design system, protótipo `3g`/`3h` — grid de estatísticas). Reaproveitado pelas três variantes especializadas abaixo. */
@Composable
private fun MetricTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(14.dp),
    ) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
    }
}

/**
 * Card de sinal óptico (variante ONT, issue #87, protótipo `3g`) — Rx/Tx e margem sobre o limite
 * mínimo já calculada pelo driver (`READ_SIGNAL` estendido, issue #28). Nunca reclassifica saúde do
 * sinal além do que [signal].dot já traz — ver KDoc de `StatusViewModel.signalDisplayFrom`.
 */
@Composable
private fun OpticalSignalCard(signal: OpticalSignalDisplay) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("status_ont_signal_card"),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            StatusRow(
                title = "Sinal óptico",
                detail = signal.unavailableReason ?: "Potência Rx/Tx e margem sobre o limite mínimo do transceptor",
                dot = signal.dot,
                testTag = "status_ont_signal_row",
            )
            if (signal.unavailableReason == null) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MetricTile(
                        label = "Potência Rx",
                        value = signal.rxPowerDbm?.let { "%.1f dBm".format(it) } ?: "—",
                        modifier = Modifier.weight(1f).testTag("status_ont_signal_rx"),
                    )
                    MetricTile(
                        label = "Potência Tx",
                        value = signal.txPowerDbm?.let { "%.1f dBm".format(it) } ?: "—",
                        modifier = Modifier.weight(1f).testTag("status_ont_signal_tx"),
                    )
                }
                signal.rxPowerMarginToLowerThresholdDb?.let { margin ->
                    Text(
                        text = "Margem sobre limite mínimo: %.1f dB".format(margin),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("status_ont_signal_margin"),
                    )
                }
            }
        }
    }
}

/** Card de contadores de erro GPON (variante ONT, issue #87) — `READ_GPON_ERROR_COUNTERS` (issue #29), sem classificação de saúde (comportamento cumulativo vs. por janela não confirmado, ver KDoc de `GponErrorCounters`). */
@Composable
private fun GponErrorCountersCard(counters: GponErrorCountersDisplay) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("status_ont_gpon_errors_card"),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            StatusRow(
                title = "Contadores de erro GPON",
                detail = counters.unavailableReason ?: "FEC corrigido, erro de cabeçalho, pacotes descartados",
                dot = if (counters.unavailableReason != null) StatusDotLevel.ERROR else null,
                testTag = "status_ont_gpon_errors_row",
            )
            if (counters.unavailableReason == null) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MetricTile(label = "FEC", value = counters.fecErrorCount?.toString() ?: "—", modifier = Modifier.weight(1f))
                    MetricTile(label = "HEC", value = counters.hecErrorCount?.toString() ?: "—", modifier = Modifier.weight(1f))
                    MetricTile(label = "Drop", value = counters.dropPacketsCount?.toString() ?: "—", modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** Card de status por porta LAN (variante ONT, issue #87) — `READ_LAN_PORT_STATUS` (issue #30). */
@Composable
private fun LanPortsCard(lanPorts: LanPortsDisplay) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("status_ont_lan_ports_card"),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text = "Portas LAN", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when {
                lanPorts.unavailableReason != null -> Text(
                    text = lanPorts.unavailableReason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("status_ont_lan_ports_unavailable"),
                )
                lanPorts.ports.isEmpty() -> Text(
                    text = "Nenhuma porta LAN reportada pelo equipamento.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("status_ont_lan_ports_empty"),
                )
                else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    lanPorts.ports.forEach { port -> LanPortRowView(port) }
                }
            }
        }
    }
}

@Composable
private fun LanPortRowView(port: LanPortRow) {
    Row(
        modifier = Modifier.fillMaxWidth().testTag("status_ont_lan_port_${port.portNumber}"),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(level = if (port.isUp) StatusDotLevel.OK else StatusDotLevel.WARNING)
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "Porta ${port.portNumber}", style = MaterialTheme.typography.bodyMedium)
            Text(
                text = listOfNotNull(
                    if (port.isUp) "Up" else "Sem link",
                    port.linkSpeedMbps?.let { "$it Mbps" },
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Card de topologia mesh (variante Mesh, issue #88+#106, protótipo `3h`) — `READ_MESH_TOPOLOGY` (issue #32). */
@Composable
private fun MeshTopologyCard(topology: MeshTopologyDisplay) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("status_mesh_topology_card"),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            StatusRow(
                title = topology.routerLabel ?: "Roteador mesh",
                detail = topology.unavailableReason
                    ?: "Nós satélite: ${topology.satelliteNodeCount} · Clientes na malha: ${topology.clients.size}",
                dot = if (topology.unavailableReason != null) StatusDotLevel.ERROR else StatusDotLevel.OK,
                testTag = "status_mesh_summary_row",
            )
            if (topology.unavailableReason == null) {
                when {
                    topology.clients.isEmpty() -> Text(
                        text = "Nenhum cliente reportado pela malha.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("status_mesh_clients_empty"),
                    )
                    else -> {
                        Text(
                            text = "CLIENTES NA MALHA",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            topology.clients.forEach { client -> MeshClientRowView(client) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MeshClientRowView(client: MeshClientRow) {
    Row(
        modifier = Modifier.fillMaxWidth().testTag("status_mesh_client_row"),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = client.hostname ?: client.macAddress ?: "Dispositivo",
                style = MaterialTheme.typography.bodyMedium,
            )
            val detail = listOfNotNull(client.ipAddress, client.wireType).joinToString(" · ")
            if (detail.isNotBlank()) {
                Text(text = detail, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Card de velocidade com sparkline (design system, seção "Componentes" 1h/1i). Sem `CapabilityId` de teste de velocidade hoje — mostra o estado "indisponível" (seção 1v), nunca dado inventado. */
@Composable
private fun SpeedCard(speed: SpeedSample?) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("status_speed_card"),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (speed == null) {
                Text(text = "Velocidade", style = MaterialTheme.typography.labelMedium)
                Text(
                    text = "Sem teste de velocidade nesta sessão — recurso ainda não disponível para este equipamento.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("status_speed_unavailable"),
                )
            } else {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(text = speed.downloadMbps.toInt().toString(), style = MaterialTheme.typography.headlineMedium)
                    Text(text = "Mbps ↓", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Sparkline(history = speed.history)
            }
        }
    }
}

@Composable
private fun Sparkline(history: List<Float>) {
    val lineColor = MaterialTheme.colorScheme.primary
    Canvas(modifier = Modifier.fillMaxWidth().height(40.dp).testTag("status_speed_sparkline")) {
        if (history.size < 2) return@Canvas
        val max = history.max()
        val min = history.min()
        val range = (max - min).takeIf { it > 0f } ?: 1f
        val stepX = size.width / (history.size - 1)
        val points = history.mapIndexed { index, value ->
            Offset(
                x = index * stepX,
                y = size.height - ((value - min) / range) * size.height,
            )
        }
        for (i in 0 until points.size - 1) {
            drawLine(
                color = lineColor,
                start = points[i],
                end = points[i + 1],
                strokeWidth = 4f,
            )
        }
    }
}

/** Espaço reservado para 1 unidade de anúncio nativo (design system, seção 1u) — só o slot visual; integração real do SDK AdSense fora de escopo desta issue. */
@Composable
private fun NativeAdSlot() {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("status_ad_slot"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Box(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = "ANÚNCIO", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(text = "Espaço de anúncio nativo", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "Google AdSense · unidade in-feed",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
