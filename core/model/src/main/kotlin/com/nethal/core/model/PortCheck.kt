package com.nethal.core.model

/**
 * Shape de request/resultado da capability `CHECK_PORT` (issues #94/#100) — ver KDoc de
 * [CapabilityId.CHECK_PORT] para o porquê desta capability não fluir pelo
 * `DriverFamily.readCapability(id)`/`CapabilityEngine` genérico.
 *
 * [targetHost] é validado contra o guard de IP privado (RFC 1918/loopback,
 * `com.nethal.core.protocol.tcp.TcpProbe`) antes de qualquer tentativa de socket — escopo
 * estritamente LAN local (`CLAUDE.md`, "Escopo fora do MVP"), nunca varredura de host arbitrário
 * fora da rede do usuário.
 */
data class PortCheckRequest(
    val targetHost: String,
    val port: Int,
    val timeoutMillis: Int = 1_500,
)

enum class PortCheckStatus {
    OPEN,
    CLOSED,
    TIMED_OUT,
}

/** Resultado de uma verificação de porta — [elapsedMillis] é `null` quando não fizer sentido reportar tempo (ex.: guard rejeitou antes de qualquer tentativa de rede, tratado fora deste tipo). */
data class PortCheckOutcome(
    val targetHost: String,
    val port: Int,
    val status: PortCheckStatus,
    val elapsedMillis: Long?,
)
