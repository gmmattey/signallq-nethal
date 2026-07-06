package com.nethal.lab.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Fluxo de opt-in ao programa de testers beta (SIG-315). Explica o que é coletado
 * (spec §8.9) antes de qualquer confirmação. Recusar segue para o app normalmente,
 * sem telemetria.
 */
@Composable
fun BetaOptInScreen(
    viewModel: BetaOptInViewModel,
    onDecided: () -> Unit,
) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                text = "Programa de testers beta",
                style = MaterialTheme.typography.headlineSmall,
            )

            Text(
                text = "Você pode ajudar a melhorar a compatibilidade do NetHAL enviando " +
                    "relatórios anônimos sobre os equipamentos testados.",
                style = MaterialTheme.typography.bodyLarge,
            )

            Text(
                text = "O que é coletado, quando você envia um relatório:",
                style = MaterialTheme.typography.titleMedium,
            )

            Text(
                text = "• Fabricante, modelo e firmware do equipamento\n" +
                    "• Protocolo e capabilities detectadas\n" +
                    "• Resultado da autenticação, sem senha\n" +
                    "• Código de erro e tempo de resposta\n" +
                    "• Hash anônimo da instalação\n" +
                    "• País/região e operadora, apenas se você informar manualmente",
                style = MaterialTheme.typography.bodyMedium,
            )

            Text(
                text = "O NetHAL nunca coleta senha, SSID em claro, MAC completo, IP público " +
                    "completo ou qualquer dado pessoal.",
                style = MaterialTheme.typography.bodyMedium,
            )

            Text(
                text = "Você pode sair do programa a qualquer momento nas configurações. " +
                    "Sair interrompe o envio de novos relatórios, mas relatórios já enviados " +
                    "são anônimos e não podem ser removidos individualmente.",
                style = MaterialTheme.typography.bodyMedium,
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Button(
                    onClick = { viewModel.optIn(onDecided) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Participar do programa beta")
                }

                OutlinedButton(
                    onClick = { viewModel.optOut(onDecided) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Agora não")
                }
            }
        }
    }
}
