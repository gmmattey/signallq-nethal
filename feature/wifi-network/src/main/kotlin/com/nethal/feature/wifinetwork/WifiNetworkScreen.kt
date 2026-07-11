package com.nethal.feature.wifinetwork

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.nethal.feature.toolscommon.UnavailableResourceState

/**
 * Tela "Wi-Fi & Rede" (issue #84, protótipos `3b`/`3e`) — casca de conteúdo da aba "Rede" do host
 * de bottom nav (#67). Exposta ao composition root só via [wifiNetworkGraph]; esta função
 * (`@Composable` avulsa) não é chamada diretamente por fora do módulo.
 *
 * `DisposableEffect` fecha a sessão ao sair de composição — mesmo padrão de `CapabilitiesScreen`
 * (hoje em `:app`).
 */
@Composable
fun WifiNetworkScreen(viewModel: WifiNetworkViewModel) {
    val uiState by viewModel.uiState.collectAsState()

    DisposableEffect(Unit) {
        onDispose { viewModel.closeSession() }
    }

    WifiNetworkContent(uiState = uiState)
}

/** Renderização pura a partir do estado — separada de [WifiNetworkScreen] para ser exercitada em teste sem precisar de uma sessão/ViewModel real. */
@Composable
fun WifiNetworkContent(uiState: WifiNetworkUiState) {
    Scaffold(modifier = Modifier.testTag("home_network_screen")) { padding ->
        when (uiState) {
            is WifiNetworkUiState.Loading -> LoadingContent(padding = padding)
            is WifiNetworkUiState.SessionUnavailable -> SessionUnavailableContent(padding = padding, state = uiState)
            is WifiNetworkUiState.Loaded -> LoadedContent(padding = padding, state = uiState)
        }
    }
}

@Composable
private fun LoadingContent(padding: PaddingValues) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CircularProgressIndicator()
        Text(text = "Lendo Wi-Fi & Rede do equipamento...", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SessionUnavailableContent(
    padding: PaddingValues,
    state: WifiNetworkUiState.SessionUnavailable,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(24.dp)
            .testTag("network_session_unavailable"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = "Wi-Fi & Rede", style = MaterialTheme.typography.headlineSmall)
        Text(text = state.reason, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun LoadedContent(padding: PaddingValues, state: WifiNetworkUiState.Loaded) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 22.dp, vertical = 14.dp)
            .verticalScroll(rememberScrollState())
            .testTag("network_loaded_content"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // "título de tela" do design system (30/38·700) — mesmo slot usado por Status/Dispositivos/
        // Configurações; `headlineMedium` deixava este título visivelmente menor/mais fino que o
        // resto do app.
        Text(text = "Wi-Fi & Rede", style = MaterialTheme.typography.headlineLarge)

        if (state.radios.isEmpty()) {
            val reason = state.radiosUnavailableReason
            if (reason != null) {
                Text(
                    text = "Não foi possível ler o status de Wi-Fi: $reason",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("network_radios_unavailable"),
                )
            } else {
                Text(
                    text = "Nenhum rádio Wi-Fi foi encontrado neste equipamento.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            state.radios.forEach { radio -> WifiRadioCard(radio) }
        }

        Text(
            text = "AÇÕES DA REDE",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column {
                state.actions.forEach { action ->
                    UnavailableResourceState(
                        label = action.label,
                        reason = action.reason,
                        modifier = Modifier.testTag("network_action_${action.id.name}"),
                    )
                }
            }
        }
    }
}

@Composable
private fun WifiRadioCard(radio: WifiRadioUiModel) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("network_radio_${radio.bandLabel.name}"),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(text = bandDisplayLabel(radio.bandLabel), style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = radio.ssid,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Leitura, não controle: reflete `WifiRadio.enabled` como o driver leu — não há
                // executor de escrita (SET_WIFI_ENABLED) no Core hoje, ver KDoc de
                // WifiNetworkViewModel. `onCheckedChange = null` deixa o Switch não-interativo.
                Switch(checked = radio.enabled ?: true, onCheckedChange = null)
            }

            RadioStatGrid(radio)
        }
    }
}

@Composable
private fun RadioStatGrid(radio: WifiRadioUiModel) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            RadioStat(modifier = Modifier.weight(1f), label = "CANAL", value = radio.channel)
            RadioStat(modifier = Modifier.weight(1f), label = "LARGURA", value = radio.bandwidth)
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            RadioStat(modifier = Modifier.weight(1f), label = "SEGURANÇA", value = radio.security)
            RadioStat(modifier = Modifier.weight(1f), label = "CLIENTES", value = radio.clientCount)
        }
    }
}

@Composable
private fun RadioStat(modifier: Modifier, label: String, value: String) {
    Column(modifier = modifier) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun bandDisplayLabel(band: WifiRadioBandLabel): String = when (band) {
    WifiRadioBandLabel.GHZ_2_4 -> "2.4 GHz"
    WifiRadioBandLabel.GHZ_5 -> "5 GHz"
    WifiRadioBandLabel.GHZ_6 -> "6 GHz"
    WifiRadioBandLabel.UNKNOWN -> "Banda desconhecida"
}
