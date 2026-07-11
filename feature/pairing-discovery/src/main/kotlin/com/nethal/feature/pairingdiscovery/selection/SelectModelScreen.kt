package com.nethal.feature.pairingdiscovery.selection

import com.nethal.core.designsystem.theme.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.nethal.core.catalog.CatalogDeviceType
import com.nethal.core.catalog.CompatibilityProfile
import com.nethal.feature.pairingdiscovery.internal.BackButton
import com.nethal.feature.pairingdiscovery.internal.BreadcrumbTrail
import com.nethal.feature.pairingdiscovery.internal.PairingInfoDialog
import com.nethal.feature.pairingdiscovery.internal.RouterGlyph
import com.nethal.feature.pairingdiscovery.internal.SelectableListRow
import com.nethal.feature.pairingdiscovery.internal.StatusChip

/**
 * Tela 2i — Selecionar modelo (issue #82, protótipo `prototypes.dc.html` #2i). Lista modelos do
 * fabricante escolhido em 2h, filtrados por `(tipo, fabricante)` direto do catálogo real,
 * deduplicados por `(vendor, model)` (caso do Archer C6, dois profiles). Cada linha mostra o
 * estágio do driver via "Status chip" — nunca dá a entender suporte pronto quando o driver está
 * em estágio inicial (critério de aceite explícito da issue). Modelos "Em pesquisa" usam o
 * estado desabilitado + diálogo "continuar mesmo assim"; modelos "Leitura"/"Leitura e escrita"
 * confirmam direto, navegando para o login (2c) — nunca para 2b, que é passo de confirmação da
 * descoberta automática (decisão registrada na spec, resposta à pergunta aberta da issue #82).
 */
@Composable
fun SelectModelScreen(
    profiles: List<CompatibilityProfile>,
    type: CatalogDeviceType,
    typeLabel: String,
    vendor: String,
    onBack: () -> Unit,
    onModelSelected: (CompatibilityProfile) -> Unit,
) {
    var pendingResearchModel by remember { mutableStateOf<ModelOption?>(null) }
    val models = remember(profiles, type, vendor) { modelOptions(profiles, type, vendor) }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding)
                .padding(24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BackButton(onClick = onBack)
                Text(
                    text = "Selecionar equipamento",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }

            BreadcrumbTrail(
                steps = listOf(typeLabel, vendor),
                currentLabel = "Modelo",
                modifier = Modifier.padding(top = 10.dp, bottom = 16.dp),
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(22.dp))
                    .border(width = 1.dp, color = MaterialTheme.colorScheme.outline, shape = RoundedCornerShape(22.dp)),
            ) {
                models.forEach { option ->
                    SelectableListRow(
                        title = option.profile.model,
                        subtitle = typeLabel,
                        leadingIcon = {
                            RouterGlyph(
                                tint = if (option.enabled) MaterialTheme.colorScheme.onSurfaceVariant
                                else LocalNetHalExtendedColors.current.onSurfaceTertiary,
                                size = 19.dp,
                            )
                        },
                        trailingContent = {
                            StatusChip(
                                label = option.supportLevel.uiLabel(),
                                color = if (option.enabled) LocalNetHalExtendedColors.current.success
                                else LocalNetHalExtendedColors.current.onSurfaceTertiary,
                            )
                        },
                        enabled = option.enabled,
                        onClick = {
                            if (option.enabled) {
                                onModelSelected(option.profile)
                            } else {
                                pendingResearchModel = option
                            }
                        },
                    )
                }
            }
        }
    }

    val pending = pendingResearchModel
    if (pending != null) {
        PairingInfoDialog(
            title = "${pending.profile.model} ainda está em pesquisa",
            body = "O NetHAL não consegue ler dados dele ainda. Você pode continuar mesmo assim, " +
                "mas é bem provável que a conexão falhe.",
            confirmLabel = "Continuar mesmo assim",
            onConfirm = {
                onModelSelected(pending.profile)
                pendingResearchModel = null
            },
            dismissLabel = "Cancelar",
            onDismiss = { pendingResearchModel = null },
        )
    }
}
