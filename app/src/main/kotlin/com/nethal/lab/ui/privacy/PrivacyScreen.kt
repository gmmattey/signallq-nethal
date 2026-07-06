package com.nethal.lab.ui.privacy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Tela "Ver privacidade", acionada a partir da Tela 1 (spec §11). Resume as regras de
 * telemetria sanitizada (§8.9) e a ausência de armazenamento de credenciais.
 */
@Composable
fun PrivacyScreen(onBack: () -> Unit) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(text = "Privacidade", style = MaterialTheme.typography.headlineSmall)

            Text(
                text = "O NetHAL nunca armazena a senha do seu roteador ou do Wi-Fi. " +
                    "Credenciais existem apenas na sessão local do app e são descartadas " +
                    "ao fechar o módulo.",
                style = MaterialTheme.typography.bodyLarge,
            )

            Text(
                text = "A permissão de localização é usada somente para ler informações " +
                    "de Wi-Fi (SSID/BSSID), exigida pelo Android — não para rastrear sua " +
                    "localização.",
                style = MaterialTheme.typography.bodyLarge,
            )

            Text(
                text = "Se você optar por participar do programa de testers beta, os " +
                    "relatórios enviados são anônimos e sanitizados: nunca incluem senha, " +
                    "SSID em claro, MAC completo ou IP público completo.",
                style = MaterialTheme.typography.bodyLarge,
            )

            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text("Voltar")
            }
        }
    }
}
