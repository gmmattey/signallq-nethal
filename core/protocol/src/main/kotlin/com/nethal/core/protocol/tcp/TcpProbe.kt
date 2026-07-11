package com.nethal.core.protocol.tcp

import com.nethal.core.protocol.http.HttpTransportIpGuard
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/** Resultado de uma única tentativa de conexão TCP feita por [TcpProbe.connect]. */
sealed interface TcpProbeOutcome {
    /** Connect concluído sem exceção — o host aceitou a conexão TCP na porta testada. */
    data class Open(val elapsedMillis: Long) : TcpProbeOutcome

    /** Connect recusado ativamente (ex.: RST) antes do timeout configurado — porta fechada. */
    data class Closed(val elapsedMillis: Long) : TcpProbeOutcome

    /** Sem resposta dentro do timeout configurado — host inalcançável ou pacotes descartados por firewall. */
    data class TimedOut(val elapsedMillis: Long) : TcpProbeOutcome

    /** [host] recusado pelo guard de IP privado antes de qualquer tentativa de rede — nunca chega a abrir socket. */
    data class Rejected(val reason: String) : TcpProbeOutcome
}

/**
 * TCP probe compartilhado por [LatencyMeasurer] (`MEASURE_LATENCY`, issues #91/#99) e [PortChecker]
 * (`CHECK_PORT`, issues #94/#100) — mesma técnica encontrada em duplicidade no SignallQ
 * (`GatewayLatencyMeasurer.medirConexaoTcp` e `ScannerDispositivosAndroid.testarPortaAberta`),
 * extraída uma única vez aqui: `Socket().connect(InetSocketAddress(host, port), timeoutMillis)` —
 * conexão concluída sem exceção conta como "aberto". Sem ICMP: raw socket exigiria root em Android
 * 10+, por isso tanto Ping (#91) quanto Verificação de porta (#94) usam TCP connect como proxy de
 * alcançabilidade, nunca um ping ICMP real (ver nota de copy da issue #91).
 *
 * Guard de IP privado obrigatório ([HttpTransportIpGuard], RFC 1918 + loopback) — mesma defesa em
 * profundidade contra alvo fora da LAN já aplicada em
 * [com.nethal.core.protocol.http.DefaultHttpTransport], reaproveitada aqui em vez de duplicada
 * (issues #55/#100). Nenhum chamador consegue contornar isso passando IP público: o guard roda
 * antes de qualquer tentativa de socket, falha segura.
 */
object TcpProbe {

    fun connect(host: String, port: Int, timeoutMillis: Int): TcpProbeOutcome =
        connect(host, port, timeoutMillis, ::performConnect)

    /**
     * Overload com [performConnect] injetável — único propósito é permitir que
     * `TcpProbeTest` force determinística e portavelmente o ramo [TcpProbeOutcome.TimedOut] (não
     * há forma confiável e não-flaky de provocar timeout de socket real de dentro de um teste de
     * unidade puro, ver KDoc do teste). O caminho real (o overload público de 3 argumentos acima)
     * sempre usa [performConnect] (conexão de verdade via `java.net.Socket`) — este parâmetro não
     * é uma porta de extensão para chamadores de produção.
     */
    internal fun connect(
        host: String,
        port: Int,
        timeoutMillis: Int,
        performConnect: (String, Int, Int) -> Unit,
    ): TcpProbeOutcome {
        if (!HttpTransportIpGuard.isAllowedHost(host)) {
            return TcpProbeOutcome.Rejected(
                "TcpProbe recusou alvo fora da LAN local (RFC 1918/loopback): host=$host",
            )
        }
        val start = System.currentTimeMillis()
        return try {
            performConnect(host, port, timeoutMillis)
            TcpProbeOutcome.Open(System.currentTimeMillis() - start)
        } catch (e: SocketTimeoutException) {
            TcpProbeOutcome.TimedOut(System.currentTimeMillis() - start)
        } catch (e: IOException) {
            TcpProbeOutcome.Closed(System.currentTimeMillis() - start)
        }
    }

    private fun performConnect(host: String, port: Int, timeoutMillis: Int) {
        Socket().use { socket -> socket.connect(InetSocketAddress(host, port), timeoutMillis) }
    }
}
