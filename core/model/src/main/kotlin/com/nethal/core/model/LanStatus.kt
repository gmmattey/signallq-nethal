package com.nethal.core.model

/**
 * Status de LAN de um equipamento — cobre a capability `READ_LAN_STATUS`.
 *
 * Carrega dado bruto (MAC completo, IP local), não hash/mascarado — mesma decisão de
 * `docs/architecture/adr/0001-fronteira-sanitizacao-telemetria.md`: sanitização de telemetria é
 * responsabilidade exclusiva de um futuro Telemetry Collector, aplicada só na fronteira de
 * exportação, nunca no modelo de dados local consumido pelo NetHAL Lab.
 */
data class LanStatus(
    val macAddress: String? = null,
    val ipv4Address: String? = null,
)
