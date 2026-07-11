package com.nethal.feature.onboarding.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * Indicador de progresso das telas sequenciais do onboarding (`1a`→`1b`→`1c`→`1d`→`1e`),
 * fiel ao protótipo: pill ativo 20×6dp accent, pontos inativos 6×6dp `#262F40`.
 *
 * [activeIndex] segue o próprio protótipo, não uma contagem linear de telas implementadas: `1e`
 * reaproveita o mesmo índice de `1d` (posição 3) porque as duas representam o mesmo passo visual
 * ("permissões", concluído) — `1d` (notificações) é a issue #71, fora do escopo deste módulo.
 */
@Composable
internal fun OnboardingProgressDots(
    activeIndex: Int,
    modifier: Modifier = Modifier,
    totalDots: Int = 4,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier,
    ) {
        repeat(totalDots) { index ->
            val active = index == activeIndex
            Box(
                modifier = Modifier
                    .height(6.dp)
                    .width(if (active) 20.dp else 6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (active) OnboardingColors.Accent else OnboardingColors.Border),
            )
        }
    }
}
