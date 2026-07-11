package com.nethal.core.model

/**
 * Contadores de erro da camada GPON — cobre a capability `READ_GPON_ERROR_COUNTERS` (issue #29).
 * Sinal de degradação de linha óptica que aparece antes de virar perda de pacote perceptível no
 * nível de aplicação.
 *
 * Comportamento cumulativo vs. por janela **não confirmado contra hardware real** — GPON PMBd
 * (Performance Monitoring Bins) normalmente é cumulativo desde boot/reset do transceptor, e este é
 * o comportamento assumido, mas o firmware Nokia G-1425G-B especificamente não foi validado ao vivo
 * para este campo (ver PR desta capability). Tratar como cumulativo até confirmação real.
 */
data class GponErrorCounters(
    /** Erros corrigidos por forward error correction. */
    val fecErrorCount: Long? = null,
    /** Erros de cabeçalho GPON. */
    val hecErrorCount: Long? = null,
    /** Pacotes descartados na camada óptica. */
    val dropPacketsCount: Long? = null,
)
