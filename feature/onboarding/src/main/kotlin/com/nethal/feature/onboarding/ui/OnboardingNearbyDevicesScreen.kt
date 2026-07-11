package com.nethal.feature.onboarding.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Test tags estáveis para Compose UI Test — issue #70. */
object OnboardingNearbyDevicesScreenTestTags {
    const val TITLE = "onboarding_nearby_devices_title"
    const val CONTINUE_BUTTON = "onboarding_nearby_devices_continue"
}

/**
 * Tela `1c` — Onboarding: Dispositivos próximos (issue #70).
 *
 * Gap corrigido em `docs/design/specs/2026-07-11-onboarding-e-pareamento-manual.md` §1c: o
 * protótipo original usava ícone e copy de Bluetooth ("Necessário para parear... via Bluetooth"),
 * mas o NetHAL não tem nenhuma permissão de Bluetooth declarada — discovery é 100% Wi-Fi/LAN. Esta
 * tela foi reescrita do zero em cima do mesmo layout: ícone `router` (não Bluetooth), título "Sua
 * rede Wi-Fi local", copy honesta sobre o escopo (LAN-only, nunca fora dela). Sem CTA secundário
 * "Agora não" — não há permissão real associada a esta tela (a única permissão real, localização,
 * já foi coberta em `1b`).
 */
@Composable
fun OnboardingNearbyDevicesScreen(
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(OnboardingColors.Background)
            .padding(PaddingValues(horizontal = 26.dp, vertical = 28.dp)),
    ) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            OnboardingProgressDots(activeIndex = 2)
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            OnboardingConcentricHero(ringCount = 0) {
                RouterGlyph(modifier = Modifier.size(34.dp))
            }

            Spacer(modifier = Modifier.height(28.dp))

            RouterGlyph(modifier = Modifier.size(26.dp))

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Sua rede Wi-Fi local",
                color = OnboardingColors.TextPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag(OnboardingNearbyDevicesScreenTestTags.TITLE),
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "O NetHAL procura equipamentos só dentro da sua rede Wi-Fi local (LAN) " +
                    "— nunca fora dela, nunca na internet. Nada é enviado para fora do seu " +
                    "roteador.",
                color = OnboardingColors.TextSecondary,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }

        OnboardingPrimaryButton(
            text = "Entendi, continuar",
            onClick = onContinue,
            modifier = Modifier.testTag(OnboardingNearbyDevicesScreenTestTags.CONTINUE_BUTTON),
        )
    }
}
