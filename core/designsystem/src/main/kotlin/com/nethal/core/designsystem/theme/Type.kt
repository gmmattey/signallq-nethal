package com.nethal.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Escala tipográfica do NetHAL (`docs/design/design-system.dc.html` §1c "Tipografia — fonte
 * única"), mapeada nos slots do Material 3 já consumidos pelas telas via `MaterialTheme.typography.*`.
 *
 * Até aqui este objeto era `Typography()` puro — os tamanhos/pesos default do M3 (Roboto,
 * `headlineMedium` 28sp normal, `bodyMedium` 14sp normal etc.), que não correspondem à hierarquia
 * do design system (títulos de tela em 700, corpo em 400/13-14sp, overline em 600 com tracking
 * largo). Título de tela em `headlineLarge` saía maior e mais fino do que o protótipo em toda
 * tela que usa esse slot (Status, Wi-Fi & Rede, Configurações, Dispositivos) — a causa mais
 * provável do relato "não está de acordo com os protótipos": não é uma tela isolada, é a
 * tipografia base de todo o app.
 *
 * Família tipográfica: o design system usa 'Google Sans Flex' como fonte única, mas ela é uma
 * fonte web do Google sem distribuição bundlável confirmada para Android — trocar a família exige
 * decisão de produto (adicionar arquivo de fonte ao APK, checar licença) e fica fora do escopo
 * desta correção puramente visual. Aqui só tamanho/peso/tracking são corrigidos; a família
 * permanece a default do sistema (Roboto), registrado como gap conhecido.
 *
 * Só os estilos com token explícito no design system são sobrescritos abaixo (headlineLarge,
 * headlineMedium, titleLarge, bodyLarge, bodyMedium, labelLarge, labelSmall) — o resto herda o
 * default do M3 (`base`) para não inventar valor sem fonte na spec.
 */
private val base = Typography()

val NetHalTypography = Typography(
    // "título de tela" — 30/38 · 700 (Visão geral, Wi-Fi & Rede, Configurações, Dispositivos).
    headlineLarge = base.headlineLarge.copy(
        fontSize = 30.sp,
        lineHeight = 38.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.01f).em,
    ),
    // "headline" — 24/30 · 700 (títulos de passo, ex.: "Permitir localização").
    headlineMedium = base.headlineMedium.copy(
        fontSize = 24.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.01f).em,
    ),
    // "title" — 18/24 · 600 (cabeçalhos de sub-tela, ex.: "Onde encontrar a senha").
    titleLarge = base.titleLarge.copy(
        fontSize = 18.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    // "body" — 14/22 · 400 (texto primário de linha/card).
    bodyLarge = base.bodyLarge.copy(
        fontSize = 14.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Normal,
    ),
    // "body secundário" — 13/20 · 400 (detalhe/legenda abaixo do texto primário).
    bodyMedium = base.bodyMedium.copy(
        fontSize = 13.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Normal,
    ),
    // "label / botão" — 14/20 · 600 (CTA de texto, ex.: "Reiniciar agora").
    labelLarge = base.labelLarge.copy(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    // "overline" — 11/16 · 600, tracking 0.12em (cabeçalhos de seção, ex.: "AÇÕES DA REDE" —
    // texto já chega em maiúsculas do call site; TextStyle não faz text-transform).
    labelSmall = base.labelSmall.copy(
        fontSize = 11.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.12f.em,
    ),
)
