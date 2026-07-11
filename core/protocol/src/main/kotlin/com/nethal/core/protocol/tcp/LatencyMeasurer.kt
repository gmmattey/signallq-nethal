package com.nethal.core.protocol.tcp

import com.nethal.core.model.LatencyProbeRequest
import com.nethal.core.model.LatencyProbeStats
import com.nethal.core.model.LatencySample
import com.nethal.core.protocol.http.HttpTransportIpGuard

/** Resultado de [LatencyMeasurer.measure] — [Rejected] espelha [TcpProbeOutcome.Rejected] (guard de IP privado). */
sealed interface LatencyMeasurementResult {
    data class Success(val stats: LatencyProbeStats) : LatencyMeasurementResult
    data class Rejected(val reason: String) : LatencyMeasurementResult
}

/**
 * Capability `MEASURE_LATENCY` (issues #91/#99) — portado de `GatewayLatencyMeasurer.kt` do
 * SignallQ (100% JVM puro, reuso direto confirmado na issue). Mede RTT via [TcpProbe] (TCP
 * connect), não ICMP.
 *
 * Diferente do original (media só o gateway, mediana de 3 amostras por porta, resultado era um
 * único `Int?`), aqui o shape é pensado para a tela Ping (#91, protótipo `4c`) mostrar uma linha
 * por amostra ("Resposta de X: tempo=N ms") + estatística agregada
 * (enviados/recebidos/perda/média, ver [LatencyProbeStats]) — por isso soma
 * [LatencyProbeRequest.sampleCount] amostras reais na primeira porta de fallback (80/443/53, nesta
 * ordem) que responder à primeira tentativa, em vez de calcular mediana entre portas.
 */
object LatencyMeasurer {

    /** Portas de fallback padrão em produção — nesta ordem, mesma escolha do `GatewayLatencyMeasurer` original. */
    val DEFAULT_FALLBACK_PORTS = listOf(80, 443, 53)

    /**
     * @param fallbackPorts porta(s) tentada(s) em ordem até a primeira responder — parâmetro (não
     * uma constante fixa) só para permitir que `LatencyMeasurerTest` exercite a lógica real de
     * sampling/agregação contra uma porta efêmera de teste, sem depender de portas privilegiadas
     * (80/443/53 exigem root/admin em vários ambientes de CI/dev). Chamadores de produção nunca
     * precisam informar isto — o default já é [DEFAULT_FALLBACK_PORTS].
     */
    fun measure(request: LatencyProbeRequest, fallbackPorts: List<Int> = DEFAULT_FALLBACK_PORTS): LatencyMeasurementResult {
        // Guard checado uma única vez aqui, direto contra HttpTransportIpGuard (sem gastar uma
        // tentativa de socket real com timeout=0, que bloquearia indefinidamente em vez de falhar
        // rápido) — TcpProbe.connect também checa por conta própria, defesa em profundidade.
        if (!HttpTransportIpGuard.isAllowedHost(request.targetHost)) {
            return LatencyMeasurementResult.Rejected(
                "Alvo fora da LAN local (RFC 1918/loopback): ${request.targetHost}",
            )
        }

        for (port in fallbackPorts) {
            val samples = (1..request.sampleCount).map {
                toSample(TcpProbe.connect(request.targetHost, port, request.timeoutMillisPerSample))
            }
            if (samples.any { it.roundTripMillis != null }) {
                return LatencyMeasurementResult.Success(LatencyProbeStats(request.targetHost, samples))
            }
        }

        // Nenhuma porta de fallback respondeu — ainda é um resultado válido (100% de perda), não
        // uma falha de guard: o host pode estar fora do ar ou bloqueando as 3 portas testadas.
        return LatencyMeasurementResult.Success(
            LatencyProbeStats(request.targetHost, List(request.sampleCount) { LatencySample(null) }),
        )
    }

    private fun toSample(outcome: TcpProbeOutcome): LatencySample = when (outcome) {
        is TcpProbeOutcome.Open -> LatencySample(outcome.elapsedMillis)
        is TcpProbeOutcome.Closed, is TcpProbeOutcome.TimedOut -> LatencySample(null)
        // Guard já foi checado uma vez no início de measure() — inatingível na prática, mas
        // tratado sem exceção não checada (falha segura: conta como perda, não crasha).
        is TcpProbeOutcome.Rejected -> LatencySample(null)
    }
}
