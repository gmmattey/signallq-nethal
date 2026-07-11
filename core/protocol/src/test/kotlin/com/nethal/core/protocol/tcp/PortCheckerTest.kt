package com.nethal.core.protocol.tcp

import com.nethal.core.model.PortCheckRequest
import com.nethal.core.model.PortCheckStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

class PortCheckerTest {

    private var server: ServerSocket? = null

    @After
    fun tearDown() {
        server?.close()
    }

    @Test
    fun `open port against a real listener reports OPEN with elapsed time`() {
        val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        server = listener

        val result = PortChecker.check(PortCheckRequest(targetHost = "127.0.0.1", port = listener.localPort))

        assertTrue(result is PortCheckResult.Success)
        val outcome = (result as PortCheckResult.Success).outcome
        assertEquals(PortCheckStatus.OPEN, outcome.status)
        assertTrue(outcome.elapsedMillis != null)
    }

    @Test
    fun `closed port against loopback reports CLOSED`() {
        val closedPort = ServerSocket(0).use { it.localPort }

        val result = PortChecker.check(PortCheckRequest(targetHost = "127.0.0.1", port = closedPort))

        assertTrue(result is PortCheckResult.Success)
        assertEquals(PortCheckStatus.CLOSED, (result as PortCheckResult.Success).outcome.status)
    }

    @Test
    fun `rejects a public ip - port check is strictly LAN scoped, never external targets`() {
        val result = PortChecker.check(PortCheckRequest(targetHost = "8.8.8.8", port = 443))

        assertTrue(result is PortCheckResult.Rejected)
        assertTrue((result as PortCheckResult.Rejected).reason.contains("8.8.8.8"))
    }

    @Test
    fun `rejects a public ip even when the user types a common port like 443`() {
        val result = PortChecker.check(PortCheckRequest(targetHost = "201.17.45.90", port = 443))

        assertTrue(result is PortCheckResult.Rejected)
    }
}
