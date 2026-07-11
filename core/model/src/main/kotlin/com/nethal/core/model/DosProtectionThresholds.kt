package com.nethal.core.model

/** Três níveis de rate-limit configurados para um tipo de tráfego (ICMP/SYN/UDP) — packets/segundo. */
data class DosProtectionThreshold(
    val low: Int? = null,
    val middle: Int? = null,
    val high: Int? = null,
)

/**
 * Thresholds de proteção DoS configurados no equipamento — cobre `READ_DOS_PROTECTION_THRESHOLDS`
 * (issue #34). Leitura pura de configuração de segurança já existente no equipamento (não altera
 * nada) — útil como indicador de causa possível para falsos positivos de latência/perda sob tráfego
 * elevado (ex.: um speedtest multi-thread esbarrando no rate-limit configurado).
 */
data class DosProtectionThresholds(
    val icmp: DosProtectionThreshold,
    val syn: DosProtectionThreshold,
    val udp: DosProtectionThreshold,
)
