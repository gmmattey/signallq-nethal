package com.nethal.feature.onboarding.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nethal.core.catalog.DriverRegistry
import com.nethal.feature.onboarding.catalog.OnboardingCompatibleDevice
import com.nethal.feature.onboarding.catalog.buildOnboardingCompatibilityCatalog

/** Test tags estáveis para Compose UI Test — issue #73. */
object OnboardingCompatibleDevicesScreenTestTags {
    const val TITLE = "onboarding_compatible_devices_title"
    const val BACK_BUTTON = "onboarding_compatible_devices_back"
    const val SCOPE_BANNER = "onboarding_compatible_devices_scope_banner"
    const val READING_GROUP_OVERLINE = "onboarding_compatible_devices_reading_overline"
    const val RESEARCH_GROUP_OVERLINE = "onboarding_compatible_devices_research_overline"
    const val RECOMMEND_BUTTON = "onboarding_compatible_devices_recommend"
    fun deviceRow(vendor: String, model: String) = "onboarding_compatible_device_${vendor}_$model"
}

/**
 * Tela `1f` — Onboarding: Dispositivos compatíveis (issue #73).
 *
 * Lê o catálogo real via [driverRegistry] (`buildOnboardingCompatibilityCatalog`), nunca texto
 * fixo — gap corrigido em relação ao protótipo original, que listava fabricantes/modelos
 * fictícios sob rótulos "HOMOLOGADOS"/"SUPORTADOS (BETA)" (vocabulário que não existe em
 * `/ciclo-vida-driver`). Banner de escopo parcial é obrigatório (regra do Bruno: nunca prometer
 * suporte universal).
 */
@Composable
fun OnboardingCompatibleDevicesScreen(
    driverRegistry: DriverRegistry,
    onBack: () -> Unit,
    onRecommendModel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val catalog = driverRegistry.buildOnboardingCompatibilityCatalog()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(OnboardingColors.Background)
            .padding(horizontal = 26.dp, vertical = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(OnboardingColors.Surface),
                contentAlignment = Alignment.Center,
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(32.dp)
                        .testTag(OnboardingCompatibleDevicesScreenTestTags.BACK_BUTTON),
                ) {
                    ChevronLeftGlyph(modifier = Modifier.size(16.dp))
                }
            }

            Spacer(modifier = Modifier.size(10.dp))

            Text(
                text = "Dispositivos compatíveis",
                color = OnboardingColors.TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.testTag(OnboardingCompatibleDevicesScreenTestTags.TITLE),
            )
        }

        Spacer(modifier = Modifier.height(18.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                Text(
                    text = "Lista parcial. O NetHAL não funciona com qualquer roteador — só " +
                        "com os modelos abaixo.",
                    color = OnboardingColors.TextSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.testTag(OnboardingCompatibleDevicesScreenTestTags.SCOPE_BANNER),
                )
            }

            if (catalog.readingData.isNotEmpty()) {
                item {
                    OnboardingCompatibilityGroupOverline(
                        text = "LEITURA DE DADOS",
                        modifier = Modifier.testTag(OnboardingCompatibleDevicesScreenTestTags.READING_GROUP_OVERLINE),
                    )
                }
                item {
                    OnboardingCompatibilityCard(devices = catalog.readingData)
                }
            }

            if (catalog.inResearch.isNotEmpty()) {
                item {
                    OnboardingCompatibilityGroupOverline(
                        text = "EM PESQUISA — AINDA NÃO FUNCIONA",
                        modifier = Modifier.testTag(OnboardingCompatibleDevicesScreenTestTags.RESEARCH_GROUP_OVERLINE),
                    )
                }
                item {
                    Column {
                        OnboardingCompatibilityCard(devices = catalog.inResearch)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Estamos testando estes modelos — ainda não leem dados " +
                                "reais do equipamento. Podem entrar no grupo acima em " +
                                "atualizações futuras.",
                            color = OnboardingColors.TextTertiary,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        OnboardingOutlineButton(
            text = "Recomendar um modelo",
            onClick = onRecommendModel,
            modifier = Modifier.testTag(OnboardingCompatibleDevicesScreenTestTags.RECOMMEND_BUTTON),
        )
    }
}

@Composable
private fun OnboardingCompatibilityGroupOverline(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = OnboardingColors.TextTertiary,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.3.sp,
        modifier = modifier,
    )
}

@Composable
private fun OnboardingCompatibilityCard(devices: List<OnboardingCompatibleDevice>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(OnboardingColors.Surface),
    ) {
        devices.forEachIndexed { index, device ->
            OnboardingCompatibilityRow(device = device)
            if (index != devices.lastIndex) {
                HorizontalDivider(color = OnboardingColors.Border, thickness = 1.dp)
            }
        }
    }
}

@Composable
private fun OnboardingCompatibilityRow(device: OnboardingCompatibleDevice) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag(
                OnboardingCompatibleDevicesScreenTestTags.deviceRow(device.vendor, device.model),
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // `image-slot` placeholder (spec §1f) — nenhuma foto genérica quando não há asset real.
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(OnboardingColors.Border),
        )

        Spacer(modifier = Modifier.size(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${device.vendor} ${device.model}",
                color = OnboardingColors.TextPrimary,
                fontSize = 14.sp,
            )
            Text(
                text = device.typeLabel,
                color = OnboardingColors.TextSecondary,
                fontSize = 11.5.sp,
            )
        }
    }
}
