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
import com.nethal.feature.pairingdiscovery.internal.PairingInfoDialog
import com.nethal.feature.pairingdiscovery.internal.RouterGlyph
import com.nethal.feature.pairingdiscovery.internal.SelectableListRow

/**
 * Tela 2g — Selecionar tipo (issue #80, protótipo `prototypes.dc.html` #2g). Primeiro passo do
 * cluster de seleção manual. Disponibilidade calculada a partir do catálogo real (nunca
 * hardcoded) — hoje só Roteador e ONT têm profile; Mesh e Ponto de acesso ficam no estado
 * desabilitado do design system (tocável, abre diálogo, nunca escondido).
 */
@Composable
fun SelectDeviceTypeScreen(
    profiles: List<CompatibilityProfile>,
    onBack: () -> Unit,
    onTypeSelected: (CatalogDeviceType) -> Unit,
) {
    var unavailableTypeLabel by remember { mutableStateOf<String?>(null) }
    val options = remember(profiles) { deviceTypeOptions(profiles) }

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

            Text(
                text = "Etapa 1 de 3 · Tipo de equipamento",
                color = LocalNetHalExtendedColors.current.onSurfaceTertiary,
                fontSize = 11.5.sp,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(22.dp))
                    .border(width = 1.dp, color = MaterialTheme.colorScheme.outline, shape = RoundedCornerShape(22.dp)),
            ) {
                options.forEach { option ->
                    SelectableListRow(
                        title = option.label,
                        leadingIcon = {
                            RouterGlyph(
                                tint = if (option.available) MaterialTheme.colorScheme.onSurfaceVariant
                                else LocalNetHalExtendedColors.current.onSurfaceTertiary,
                                size = 20.dp,
                            )
                        },
                        enabled = option.available,
                        onClick = {
                            if (option.available) {
                                onTypeSelected(option.type)
                            } else {
                                unavailableTypeLabel = option.label
                            }
                        },
                    )
                }
            }
        }
    }

    val label = unavailableTypeLabel
    if (label != null) {
        PairingInfoDialog(
            title = "Ainda não há suporte",
            body = "Ainda não há driver para $label no NetHAL. Hoje cobrimos Roteador e ONT.",
            confirmLabel = "Entendi",
            onConfirm = { unavailableTypeLabel = null },
        )
    }
}
