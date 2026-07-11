package com.nethal.feature.onboarding.ui

import androidx.compose.ui.graphics.Color

/**
 * Tokens de cor do design system NetHAL (`docs/design/design-system.dc.html`), usados aqui como
 * constantes literais porque `core:designsystem` ainda não expõe accent/success/tertiary/border —
 * hoje só expõe cores de tema Material herdadas de uma paleta antiga (`NetHalTeal`/`NetHalCyan`),
 * em processo de resincronização em PR separado (#116, "electric blue"). Quando esses tokens
 * migrarem para `core:designsystem`, trocar estas constantes locais pelas expostas lá — nenhuma
 * outra mudança de código nas telas deveria ser necessária, já que o uso é só via este objeto.
 */
internal object OnboardingColors {
    val Background = Color(0xFF0B0F19)
    val Surface = Color(0xFF161B26)
    val Border = Color(0xFF262F40)
    val Accent = Color(0xFF006FFF)
    val OnAccent = Color(0xFFFFFFFF)
    val TextPrimary = Color(0xFFE8ECF5)
    val TextSecondary = Color(0xFF8891A8)
    val TextTertiary = Color(0xFF4C5567)
    val Success = Color(0xFF10B981)
}
