package com.nethal.core.model

/**
 * Status de WAN de um equipamento — cobre a capability `READ_WAN_STATUS`. Dado bruto (ver ADR 0001,
 * `docs/architecture/adr/0001-fronteira-sanitizacao-telemetria.md`), sem mascaramento de IP público —
 * essa regra vale só na fronteira de exportação de telemetria, não neste modelo local.
 */
data class WanStatus(
    val ipv4Address: String? = null,
)
