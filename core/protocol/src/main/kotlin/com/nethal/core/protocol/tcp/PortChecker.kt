package com.nethal.core.protocol.tcp

import com.nethal.core.model.PortCheckOutcome
import com.nethal.core.model.PortCheckRequest
import com.nethal.core.model.PortCheckStatus

/** Resultado de [PortChecker.check] — [Rejected] espelha [TcpProbeOutcome.Rejected] (guard de IP privado). */
sealed interface PortCheckResult {
    data class Success(val outcome: PortCheckOutcome) : PortCheckResult
    data class Rejected(val reason: String) : PortCheckResult
}

/**
 * Capability `CHECK_PORT` (issues #94/#100) — mesma técnica de [TcpProbe] usada por
 * [LatencyMeasurer], mas para uma porta e host explícitos informados pelo usuário (tela
 * Verificação de porta, #94), sem fallback entre portas.
 *
 * Guard de IP privado obrigatório (herdado de [TcpProbe.connect]) — escopo estritamente LAN local
 * (`CLAUDE.md`, "Escopo fora do MVP"): nunca aceita alvo fora de RFC 1918/loopback, mesmo que o
 * usuário digite um IP público no campo de texto da tela.
 */
object PortChecker {

    fun check(request: PortCheckRequest): PortCheckResult {
        return when (val outcome = TcpProbe.connect(request.targetHost, request.port, request.timeoutMillis)) {
            is TcpProbeOutcome.Open -> PortCheckResult.Success(
                PortCheckOutcome(request.targetHost, request.port, PortCheckStatus.OPEN, outcome.elapsedMillis),
            )
            is TcpProbeOutcome.Closed -> PortCheckResult.Success(
                PortCheckOutcome(request.targetHost, request.port, PortCheckStatus.CLOSED, outcome.elapsedMillis),
            )
            is TcpProbeOutcome.TimedOut -> PortCheckResult.Success(
                PortCheckOutcome(request.targetHost, request.port, PortCheckStatus.TIMED_OUT, outcome.elapsedMillis),
            )
            is TcpProbeOutcome.Rejected -> PortCheckResult.Rejected(outcome.reason)
        }
    }
}
