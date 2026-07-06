package com.nethal.lab.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Tela 1 — Boas-vindas (spec §11). Copy e estrutura de ações seguem o texto exato da
 * especificação: explicação da permissão de localização antes de qualquer prompt do sistema
 * (SIG-312), e bloqueio de "Iniciar diagnóstico" até confirmação separada de autorização de
 * rede (SIG-313).
 */
@Composable
fun WelcomeScreen(
    viewModel: WelcomeViewModel,
    onStartDiagnosis: () -> Unit,
    onViewPrivacy: () -> Unit,
    onExit: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Text(
                text = "NetHAL Lab é uma ferramenta experimental para detectar e testar " +
                    "compatibilidade com roteadores, ONTs e modems na sua rede local.",
                style = MaterialTheme.typography.bodyLarge,
            )

            Text(
                text = "Para identificar sua rede, o Android exige permissão de localização. " +
                    "O NetHAL usa isso apenas para ler informações de Wi-Fi (SSID/BSSID) — " +
                    "nunca para rastrear sua localização.",
                style = MaterialTheme.typography.bodyLarge,
            )

            Spacer(modifier = Modifier.height(8.dp))

            NetworkAuthorizationConfirmation(
                checked = uiState.networkAuthorizationConfirmed,
                onCheckedChange = viewModel::onNetworkAuthorizationChanged,
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Button(
                    onClick = { viewModel.confirmAndProceed(onStartDiagnosis) },
                    enabled = uiState.canStartDiagnosis,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Iniciar diagnóstico")
                }

                TextButton(onClick = onViewPrivacy, modifier = Modifier.fillMaxWidth()) {
                    Text("Ver privacidade")
                }

                TextButton(onClick = onExit, modifier = Modifier.fillMaxWidth()) {
                    Text("Sair")
                }
            }
        }
    }
}

@Composable
private fun NetworkAuthorizationConfirmation(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(
            text = "Esta é a minha rede, ou tenho autorização para testá-la.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
