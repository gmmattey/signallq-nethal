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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nethal.core.catalog.CatalogDeviceType
import com.nethal.core.catalog.CompatibilityProfile
import com.nethal.feature.pairingdiscovery.internal.AvatarInitial
import com.nethal.feature.pairingdiscovery.internal.BackButton
import com.nethal.feature.pairingdiscovery.internal.BreadcrumbTrail
import com.nethal.feature.pairingdiscovery.internal.SelectableListRow

/**
 * Tela 2h — Selecionar fabricante (issue #81, protótipo `prototypes.dc.html` #2h). Lista só
 * fabricantes reais do catálogo para o tipo escolhido em 2g — nunca fabricante sem profile
 * (decisão registrada na spec: `Intelbras`/`Huawei`/`Zyxel` do protótipo saem, pois não têm
 * cobertura real). "Outro / não sei" sempre por último, agora com destino real: entrada manual
 * de IP (decisão registrada na spec de #80).
 */
@Composable
fun SelectManufacturerScreen(
    profiles: List<CompatibilityProfile>,
    type: CatalogDeviceType,
    typeLabel: String,
    onBack: () -> Unit,
    onManufacturerSelected: (String) -> Unit,
    onOtherSelected: () -> Unit,
) {
    val manufacturers = remember(profiles, type) { manufacturerOptions(profiles, type) }

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
                steps = listOf(typeLabel),
                currentLabel = "Etapa 2 de 3 · Fabricante",
                modifier = Modifier.padding(top = 10.dp, bottom = 16.dp),
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(22.dp))
                    .border(width = 1.dp, color = MaterialTheme.colorScheme.outline, shape = RoundedCornerShape(22.dp)),
            ) {
                manufacturers.forEach { manufacturer ->
                    SelectableListRow(
                        title = manufacturer.vendor,
                        leadingIcon = {
                            AvatarInitial(letter = manufacturer.vendor.take(1).uppercase(), highlighted = false)
                        },
                        onClick = { onManufacturerSelected(manufacturer.vendor) },
                    )
                }
                SelectableListRow(
                    title = "Outro / não sei",
                    leadingIcon = { AvatarInitial(letter = "?", highlighted = false) },
                    onClick = onOtherSelected,
                )
            }
        }
    }
}
