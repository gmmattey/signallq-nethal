package com.nethal.feature.devices.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nethal.feature.devices.domain.LanDevice
import com.nethal.feature.devices.domain.LanDeviceType

/**
 * Tela "Dispositivos" (protótipo `3i`/`3j` em `docs/design/prototypes.dc.html`, issue #86).
 * Mostra só a lista de conectados detectados pelo scan (issue #105). O protótipo também desenha
 * uma seção "Bloqueados" com ação "Permitir" — não implementada aqui porque não existe nenhuma
 * capability de bloqueio real (fora de escopo da #105); renderizar essa seção com dado
 * inventado violaria o critério de aceite "nunca lista mockada em produção" (#86). Fica
 * pendurado até uma capability de bloqueio existir (issue futura).
 */
@Composable
fun DevicesScreen(viewModel: DevicesViewModel) {
    val state by viewModel.uiState.collectAsState()

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .testTag("home_devices_screen"),
        ) {
            Column(modifier = Modifier.padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 8.dp)) {
                // "título de tela" do design system (30/38·700) — mesmo slot de Status/Rede/
                // Configurações.
                Text(text = "Dispositivos", style = MaterialTheme.typography.headlineLarge)
                Text(
                    text = subtitleFor(state),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            when (val current = state) {
                DevicesUiState.Loading -> LoadingContent()

                is DevicesUiState.Loaded -> if (current.devices.isEmpty()) {
                    EmptyContent(
                        message = "Nenhum dispositivo encontrado na rede local.",
                        onRetry = viewModel::refresh,
                    )
                } else {
                    DeviceList(devices = current.devices)
                }

                DevicesUiState.NoNetwork -> EmptyContent(
                    message = "Conecte-se a uma rede Wi-Fi para listar os dispositivos conectados.",
                    onRetry = viewModel::refresh,
                )

                is DevicesUiState.Failed -> EmptyContent(
                    message = "Não foi possível buscar os dispositivos: ${current.message}",
                    onRetry = viewModel::refresh,
                )
            }
        }
    }
}

private fun subtitleFor(state: DevicesUiState): String = when (state) {
    is DevicesUiState.Loaded -> "${state.devices.size} conectados"
    DevicesUiState.Loading -> "Buscando dispositivos na rede…"
    DevicesUiState.NoNetwork, is DevicesUiState.Failed -> "Indisponível"
}

@Composable
private fun DeviceList(devices: List<LanDevice>) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(devices, key = { it.ipAddress }) { device -> DeviceRow(device) }
    }
}

@Composable
private fun DeviceRow(device: LanDevice) {
    // Shape/cor explícitos (design system: cards arredondados 20dp sobre `colorScheme.surface`) —
    // `Card()` sem esses parâmetros cai no default do M3 (canto ~12dp, `surfaceContainerLow`
    // calculado por elevação tonal), destoando do resto do app, que nunca usa o Card "cru".
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = device.hostname ?: labelFor(device.deviceType),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = listOfNotNull(device.vendor, device.ipAddress, device.macAddress)
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun labelFor(type: LanDeviceType): String = when (type) {
    LanDeviceType.GATEWAY -> "Roteador / Gateway"
    LanDeviceType.COMPUTER -> "Computador"
    LanDeviceType.MOBILE -> "Celular"
    LanDeviceType.TV_MEDIA -> "TV / Media"
    LanDeviceType.IOT -> "Dispositivo IoT"
    LanDeviceType.PRINTER -> "Impressora"
    LanDeviceType.UNKNOWN -> "Dispositivo desconhecido"
}

@Composable
private fun LoadingContent() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun EmptyContent(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            Text(text = message, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        }
        OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
            Text("Tentar novamente")
        }
    }
}
