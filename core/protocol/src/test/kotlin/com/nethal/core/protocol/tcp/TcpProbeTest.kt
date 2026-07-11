package com.nethal.core.protocol.tcp

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.SocketTimeoutException

/**
 * Cobertura de [TcpProbe] contra sockets reais em loopback (127.0.0.1, aceito pelo guard — mesma
 * técnica de harness local usada por `DefaultHttpTransportTest`) para os casos "aberto"/"fechado", e
 * via injeção de [TcpProbe.connect] com [performConnect] para o caso "timeout" — não há forma
 * portátil e não-flaky de forçar um `SocketTimeoutException` real de dentro de um teste de unidade
 * (depende de comportamento de rede/OS fora do controle do teste), então este único ramo é
 * verificado por injeção determinística em vez de rede real.
 */
class TcpProbeTest {

    private var server: ServerSocket? = null

    @After
    fun tearDown() {
        server?.close()
    }

    @Test
    fun `open port succeeds without exception when a real listener accepts the connection`() {
        val listener = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        server = listener

        val outcome = TcpProbe.connect("127.0.0.1", listener.localPort, timeoutMillis = 2_000)

        assertTrue(outcome is TcpProbeOutcome.Open)
        assertTrue((outcome as TcpProbeOutcome.Open).elapsedMillis >= 0)
    }

    @Test
    fun `closed port is refused immediately - nothing listening on loopback`() {
        // Porta bem alta escolhida por não ter nada acoplado no harness de teste — connect() a um
        // loopback sem listener é recusado (RST) quase instantaneamente pelo próprio kernel, sem
        // depender de rede externa.
        val unusedPort = ServerSocket(0).use { it.localPort }

        val outcome = TcpProbe.connect("127.0.0.1", unusedPort, timeoutMillis = 2_000)

        assertTrue("esperava porta fechada, obteve $outcome", outcome is TcpProbeOutcome.Closed)
    }

    @Test
    fun `rejects a public ip before opening any socket - guard obrigatorio das issues 55 e 100`() {
        val outcome = TcpProbe.connect("8.8.8.8", 443, timeoutMillis = 2_000)

        assertTrue(outcome is TcpProbeOutcome.Rejected)
        assertTrue((outcome as TcpProbeOutcome.Rejected).reason.contains("8.8.8.8"))
    }

    @Test
    fun `accepts rfc1918 and loopback targets - mesma faixa de HttpTransportIpGuard`() {
        val listener = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        server = listener

        val outcome = TcpProbe.connect("127.0.0.1", listener.localPort, timeoutMillis = 2_000)

        assertTrue(outcome !is TcpProbeOutcome.Rejected)
    }

    @Test
    fun `timed out outcome maps SocketTimeoutException via injected connector`() {
        val outcome = TcpProbe.connect("192.168.1.1", 443, timeoutMillis = 5) { _, _, _ ->
            throw SocketTimeoutException("simulado")
        }

        assertTrue(outcome is TcpProbeOutcome.TimedOut)
    }

    @Test
    fun `other IOException from the connector maps to Closed, not TimedOut`() {
        val outcome = TcpProbe.connect("192.168.1.1", 443, timeoutMillis = 5) { _, _, _ ->
            throw java.net.ConnectException("Connection refused")
        }

        assertTrue(outcome is TcpProbeOutcome.Closed)
    }

    @Test
    fun `injected connector is never invoked when the guard rejects the host`() {
        var invoked = false

        val outcome = TcpProbe.connect("8.8.8.8", 443, timeoutMillis = 5) { _, _, _ -> invoked = true }

        assertTrue(outcome is TcpProbeOutcome.Rejected)
        assertEquals("guard deve recusar antes de qualquer tentativa de conexão", false, invoked)
    }
}
