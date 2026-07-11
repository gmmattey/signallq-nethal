package com.nethal.feature.onboarding.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Test tags estáveis para Compose UI Test — issue #69. */
object OnboardingLocationScreenTestTags {
    const val TITLE = "onboarding_location_title"
    const val CONTINUE_BUTTON = "onboarding_location_continue"
}

/**
 * Tela `1b` — Onboarding: Localização (issue #69).
 *
 * Decisão registrada em `docs/design/specs/2026-07-11-onboarding-e-pareamento-manual.md` §1b:
 * esta tela é **só preparatória/educativa** — nunca dispara `ACCESS_FINE_LOCATION` de verdade. O
 * prompt real do Android continua acontecendo em `DiscoveryScreen` (tela `2a`), no momento em que
 * o scan de Wi-Fi de fato ocorre (`/regras-android-nethal`: solicitação em momento contextual,
 * nunca no cold start). Por isso o CTA é "Entendi, continuar" (não "Permitir localização") e não
 * há CTA secundário "Agora não" — não há nada para recusar aqui.
 */
@Composable
fun OnboardingLocationScreen(
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
            OnboardingProgressDots(activeIndex = 1)
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            OnboardingConcentricHero {
                LocationPinGlyph(modifier = Modifier.size(34.dp))
            }

            Spacer(modifier = Modifier.height(28.dp))

            LocationPinGlyph(modifier = Modifier.size(26.dp))

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Permitir localização",
                color = OnboardingColors.TextPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag(OnboardingLocationScreenTestTags.TITLE),
            )

            androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "O Android exige acesso à localização para ler o nome (SSID) da rede " +
                    "Wi-Fi conectada — é assim que identificamos seu roteador. O NetHAL não " +
                    "coleta nem usa sua localização geográfica.",
                color = OnboardingColors.TextSecondary,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }

        OnboardingPrimaryButton(
            text = "Entendi, continuar",
            onClick = onContinue,
            modifier = Modifier.testTag(OnboardingLocationScreenTestTags.CONTINUE_BUTTON),
        )
    }
}

/**
 * Painel hero com anéis concêntricos accent — reaproveitado por `1b`/`1c` (mesma composição do
 * protótipo: painel arredondado translúcido + círculos concêntricos + glifo central).
 */
@Composable
internal fun OnboardingConcentricHero(
    modifier: Modifier = Modifier,
    ringCount: Int = 3,
    centerContent: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(Color.White.copy(alpha = 0.04f)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val maxRadius = size.minDimension * 0.42f
            repeat(ringCount) { index ->
                val fraction = (index + 1f) / ringCount
                drawCircle(
                    color = OnboardingColors.Accent.copy(alpha = 0.14f + index * 0.1f),
                    radius = maxRadius * fraction,
                    center = center,
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
        }
        centerContent()
    }
}
