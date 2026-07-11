package com.nethal.core.model

/**
 * Shape de request/resultado da capability `MEASURE_LATENCY` (issues #91/#99) — ver KDoc de
 * [CapabilityId.MEASURE_LATENCY] para o porquê desta capability não fluir pelo
 * `DriverFamily.readCapability(id)`/`CapabilityEngine` genérico.
 *
 * Nomenclatura deliberadamente evita "ping" (a tela consumidora é chamada "Ping" no protótipo, mas
 * a medição real é RTT via TCP connect, não ICMP — nota de copy da issue #91). [sampleCount] segue
 * o mesmo espírito de [NativeDiagnosticPingRequest.packetCount] (4 por padrão), mas aqui é sempre
 * TCP, nunca ICMP.
 */
data class LatencyProbeRequest(
    /** IP dentro da LAN local do usuário — validado pelo guard de IP privado antes de qualquer tentativa de socket (ver `com.nethal.core.protocol.tcp.TcpProbe`). */
    val targetHost: String,
    val sampleCount: Int = 4,
    val timeoutMillisPerSample: Int = 1_000,
)

/** Uma amostra de RTT — `null` significa que a tentativa não recebeu resposta dentro do timeout (perda), nunca um valor inventado. */
data class LatencySample(val roundTripMillis: Long?)

/**
 * Estatística agregada de uma execução de [LatencyProbeRequest] — shape pensado para a tela Ping
 * (#91, protótipo `4c`): uma linha por amostra + grid de enviados/recebidos/perda/média.
 */
data class LatencyProbeStats(
    val targetHost: String,
    val samples: List<LatencySample>,
) {
    val packetsSent: Int get() = samples.size
    val packetsReceived: Int get() = samples.count { it.roundTripMillis != null }
    val packetLossPercent: Double
        get() = if (samples.isEmpty()) 0.0 else (1.0 - packetsReceived.toDouble() / packetsSent) * 100.0
    val averageRoundTripMillis: Double?
        get() = samples.mapNotNull { it.roundTripMillis }.takeIf { it.isNotEmpty() }?.average()
}
