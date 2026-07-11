package com.nethal.feature.pairingdiscovery.manualentry

import com.nethal.core.designsystem.theme.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nethal.feature.pairingdiscovery.internal.BackButton

/**
 * Destino de entrada manual de IP (issue #80, decisão registrada na spec). Alcançado por dois
 * caminhos: item "Outro/não sei" em 2h (sem `deviceLabel` — vendor/modelo ainda desconhecidos, o
 * IP entra no pipeline normal de fingerprint/2b) e a partir do item de modelo confirmado em 2i
 * (com `deviceLabel` — vendor/modelo já escolhidos manualmente, o IP só falta para poder tentar
 * o login em 2c, pulando 2b conforme critério de aceite de #82). Também é o destino de "Informar
 * IP manualmente" na tela de falha de descoberta (2b-falha).
 */
@Composable
fun ManualIpEntryScreen(
    deviceLabel: String?,
    error: String?,
    onBack: () -> Unit,
    onSubmit: (ip: String) -> Unit,
) {
    var manualIp by remember { mutableStateOf("") }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BackButton(onClick = onBack)
                Text(
                    text = "Informar IP manualmente",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }

            Text(
                text = if (deviceLabel != null) {
                    "Você selecionou $deviceLabel. Informe o IP dele na sua rede local para continuar."
                } else {
                    "Informe o IP do equipamento na sua rede local — o NetHAL tenta identificá-lo " +
                        "automaticamente a partir do endereço."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
            )

            ManualIpField(
                value = manualIp,
                onValueChange = { manualIp = it },
                error = error,
                onSubmit = { onSubmit(manualIp) },
                submitLabel = if (deviceLabel != null) "Continuar" else "Adicionar equipamento",
            )
        }
    }
}
