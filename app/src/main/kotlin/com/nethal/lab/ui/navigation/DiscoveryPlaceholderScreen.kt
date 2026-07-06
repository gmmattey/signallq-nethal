package com.nethal.lab.ui.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Espaço reservado para a Tela 2 (Descoberta), fora do escopo desta entrega — o Discovery
 * Engine ainda não existe. Existe só para o fluxo de onboarding ter um destino final e
 * permitir acesso às Configurações.
 */
@Composable
fun DiscoveryPlaceholderScreen(onOpenSettings: () -> Unit) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Descoberta de rede ainda não implementada nesta versão.",
                style = MaterialTheme.typography.bodyLarge,
            )

            TextButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Text("Configurações")
            }
        }
    }
}
