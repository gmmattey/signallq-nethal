package com.nethal.lab.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Tela mínima de configurações (SIG-315): status do programa beta e opt-out.
 * Sem mais nada nesta entrega — Discovery Engine e Command Executor não existem ainda.
 */
@Composable
fun SettingsScreen(viewModel: SettingsViewModel) {
    val isBetaProgramActive by viewModel.isBetaProgramActive.collectAsState()

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(text = "Configurações", style = MaterialTheme.typography.headlineSmall)

            Text(
                text = if (isBetaProgramActive) {
                    "Você está participando do programa de testers beta."
                } else {
                    "Você não está participando do programa de testers beta."
                },
                style = MaterialTheme.typography.bodyLarge,
            )

            if (isBetaProgramActive) {
                Text(
                    text = "Sair interrompe o envio de novos relatórios. Relatórios já " +
                        "enviados são anônimos e não podem ser removidos individualmente.",
                    style = MaterialTheme.typography.bodyMedium,
                )

                OutlinedButton(
                    onClick = viewModel::leaveBetaProgram,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Sair do programa beta")
                }
            }
        }
    }
}
