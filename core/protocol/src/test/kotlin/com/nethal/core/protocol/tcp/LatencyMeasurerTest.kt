package com.nethal.core.protocol.tcp

import com.nethal.core.model.LatencyProbeRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

/**
 * [fallbackPorts] injetado com uma porta efêmera real (não 80/443/53, privilegiadas em vários
 * ambientes de CI/dev) — ver KDoc de [LatencyMeasurer.measure].
 */
class LatencyMeasurerTest {

    private var server: ServerSocket? = null

    @After
    fun tearDown() {
        server?.close()
    }

    @Test
    fun `rejects a public ip before sampling anything`() {
        val result = LatencyMeasurer.measure(LatencyProbeRequest(targetHost = "8.8.8.8"))

        assertTrue(result is LatencyMeasurementResult.Rejected)
    }

    @Test
    fun `measures real RTT samples against a real listener on the first fallback port`() {
        val listener = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        server = listener

        val result = LatencyMeasurer.measure(
            LatencyProbeRequest(targetHost = "127.0.0.1", sampleCount = 3, timeoutMillisPerSample = 1_000),
            fallbackPorts = listOf(listener.localPort),
        )

        assertTrue(result is LatencyMeasurementResult.Success)
        val stats = (result as LatencyMeasurementResult.Success).stats
        assertEquals(3, stats.packetsSent)
        assertEquals(3, stats.packetsReceived)
        assertEquals(0.0, stats.packetLossPercent, 0.001)
        assertTrue("média deve ser um valor real, não nulo, quando todas as amostras respondem", stats.averageRoundTripMillis != null)
    }

    @Test
    fun `falls back to the next port when the first one refuses the connection`() {
        val secondListener = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        server = secondListener
        val closedPort = ServerSocket(0).use { it.localPort }

        val result = LatencyMeasurer.measure(
            LatencyProbeRequest(targetHost = "127.0.0.1", sampleCount = 2, timeoutMillisPerSample = 1_000),
            fallbackPorts = listOf(closedPort, secondListener.localPort),
        )

        assertTrue(result is LatencyMeasurementResult.Success)
        val stats = (result as LatencyMeasurementResult.Success).stats
        assertEquals(2, stats.packetsReceived)
    }

    @Test
    fun `all fallback ports closed yields 100 percent loss, never a fabricated average`() {
        val closedPort = ServerSocket(0).use { it.localPort }

        val result = LatencyMeasurer.measure(
            LatencyProbeRequest(targetHost = "127.0.0.1", sampleCount = 2, timeoutMillisPerSample = 200),
            fallbackPorts = listOf(closedPort),
        )

        assertTrue(result is LatencyMeasurementResult.Success)
        val stats = (result as LatencyMeasurementResult.Success).stats
        assertEquals(2, stats.packetsSent)
        assertEquals(0, stats.packetsReceived)
        assertEquals(100.0, stats.packetLossPercent, 0.001)
        assertEquals(null, stats.averageRoundTripMillis)
    }
}
