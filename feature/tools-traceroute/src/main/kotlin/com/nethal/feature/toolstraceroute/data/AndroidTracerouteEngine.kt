package com.nethal.feature.toolstraceroute.data

import com.nethal.feature.toolstraceroute.domain.PingLineParser
import com.nethal.feature.toolstraceroute.domain.TracerouteAvailability
import com.nethal.feature.toolstraceroute.domain.TracerouteEngine
import com.nethal.feature.toolstraceroute.domain.TracerouteHop
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * Implementação real de [TracerouteEngine] (issue #102) — shell-out para o binário `ping` do
 * próprio Android, incrementando o TTL a cada tentativa. Inspirado no `TopologyTracer.kt` do
 * SignallQ (mesma técnica geral: `Runtime`/`ProcessBuilder` chamando `ping -t <ttl>`), mas
 * reescrito, não copiado — ver decisão de arquitetura no PR desta issue.
 *
 * Deliberadamente **não** roteado por `CapabilityEngine`/`DriverFamily`: o alvo é qualquer
 * host/IP informado pelo usuário (ex.: `8.8.8.8`), não o equipamento pareado — este traceroute
 * sai do próprio aparelho Android para a internet, não é uma leitura autenticada de um gateway.
 *
 * @param pingBinaryPath caminho do binário — parametrizado (não só para teste: alguns
 * fabricantes/ROMs historicamente moveram ou removeram o binário deste caminho padrão, ver
 * `/regras-android-nethal`; expor o parâmetro deixa o teste de disponibilidade honesto sobre isso
 * em vez de mascarar como "traceroute sempre falha").
 * @param perHopTimeoutSeconds timeout de espera por resposta em CADA hop, repassado como `-W` ao
 * `ping` — em segundos porque é a unidade que o `-W` do toybox (Android) espera.
 */
class AndroidTracerouteEngine(
    private val pingBinaryPath: String = DEFAULT_PING_BINARY_PATH,
    private val perHopTimeoutSeconds: Int = 1,
    private val elapsedNanos: () -> Long = System::nanoTime,
) : TracerouteEngine {

    override suspend fun checkAvailability(): TracerouteAvailability = withContext(Dispatchers.IO) {
        try {
            val process = startPing(listOf("-c", "1", "-W", perHopTimeoutSeconds.toString(), LOOPBACK_ADDRESS))
            process.inputStream.bufferedReader().readText()
            process.waitFor()
            TracerouteAvailability.Available
        } catch (e: IOException) {
            TracerouteAvailability.Unavailable(
                reason = "Não foi possível executar o traceroute neste aparelho: o binário de ping do " +
                    "sistema não respondeu (${e.message ?: e::class.simpleName}).",
            )
        } catch (e: SecurityException) {
            TracerouteAvailability.Unavailable(
                reason = "O sistema bloqueou a execução do traceroute neste aparelho " +
                    "(restrição de segurança do fabricante).",
            )
        }
    }

    override fun trace(target: String, maxHops: Int): Flow<TracerouteHop> = flow {
        val resolvedTargetAddress = resolveTargetAddress(target)
        for (ttl in 1..maxHops) {
            val hop = pingWithTtl(target, ttl, resolvedTargetAddress)
            emit(hop)
            if (hop is TracerouteHop.Responded && hop.isTarget) return@flow
        }
    }.flowOn(Dispatchers.IO)

    /** Best-effort: alvo pode ser hostname (`google.com`) ou IP literal — sem isto, nunca saberíamos identificar qual hop É o alvo (comparação teria que ser por string crua, que falha para hostname). `null` quando o hostname não resolve — o traceroute roda até `maxHops` sem nunca marcar `isTarget`. */
    private fun resolveTargetAddress(target: String): String? = try {
        InetAddress.getByName(target).hostAddress
    } catch (e: UnknownHostException) {
        null
    }

    private fun pingWithTtl(target: String, ttl: Int, resolvedTargetAddress: String?): TracerouteHop {
        val startedAt = elapsedNanos()
        val output = try {
            val process = startPing(
                listOf("-c", "1", "-W", perHopTimeoutSeconds.toString(), "-t", ttl.toString(), target),
            )
            val text = process.inputStream.bufferedReader().readText()
            process.waitFor()
            text
        } catch (e: IOException) {
            return TracerouteHop.TimedOut(ttl)
        }
        val elapsedMillis = (elapsedNanos() - startedAt) / NANOS_PER_MILLI

        val address = PingLineParser.extractAddress(output) ?: return TracerouteHop.TimedOut(ttl)
        val isTarget = resolvedTargetAddress != null && address == resolvedTargetAddress
        return TracerouteHop.Responded(
            ttl = ttl,
            address = address,
            // PTR reverso só para o hop final — tentar em cada roteador intermediário raramente
            // encontra registro e estoura o tempo do traceroute inteiro esperando timeout de DNS.
            hostname = if (isTarget) reverseDnsLookup(address) else null,
            rttMillis = elapsedMillis,
            isTarget = isTarget,
        )
    }

    private fun reverseDnsLookup(address: String): String? = try {
        InetAddress.getByName(address).canonicalHostName.takeIf { it != address }
    } catch (e: UnknownHostException) {
        null
    }

    private fun startPing(args: List<String>): Process =
        ProcessBuilder(listOf(pingBinaryPath) + args)
            .redirectErrorStream(true)
            .start()

    private companion object {
        const val DEFAULT_PING_BINARY_PATH = "/system/bin/ping"
        const val LOOPBACK_ADDRESS = "127.0.0.1"
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
