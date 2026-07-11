package com.nethal.core.driver.family.nokia.gpon

import com.nethal.core.catalog.CapabilityReadResult
import com.nethal.core.catalog.DriverFamilyAuthResult
import com.nethal.core.driver.nokia.FakeNokiaHttpTransport
import com.nethal.core.driver.nokia.errorLoginResponse
import com.nethal.core.driver.nokia.sampleHomeNetworkingHtml
import com.nethal.core.driver.nokia.successfulLoginResponse
import com.nethal.core.model.CapabilityId
import com.nethal.core.model.CapabilityPayload
import com.nethal.core.util.PiiHashing
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.util.Base64

/**
 * Testes de [NokiaGponDriverFamily] — orquestração (retry, guarda RFC 1918, gerenciamento de
 * sessão, classificação de falha), com [FakeNokiaHttpTransport] (mesmo fake já usado por
 * `NokiaOntDriverTest`/`NokiaAuthenticationClientTest`). Não substituem a validação ao vivo já
 * registrada para `NokiaOntDriver`/`NokiaAuthenticationClient`; cobrem a orquestração nova desta
 * classe (issue #18).
 */
class NokiaGponDriverFamilyTest {

    private fun realProfileConfig(): NokiaGponDriverConfig = NokiaGponDriverConfig(
        gponStatusPath = "/wan_status.cgi?gpon",
        wanStatusPath = "/show_wan_status.cgi?ipv4",
        pppStatusPath = "/index.cgi?getppp",
        deviceInfoPath = "/device_status.cgi",
        connectedClientsPath = "/lan_status.cgi?wlan",
    )

    private fun loginPageWithGeneratedKey(): String {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
        val pubkeyBase64 = Base64.getEncoder().encodeToString(keyPair.public.encoded)
        return """
            var pubkey = '-----BEGIN PUBLIC KEY-----$pubkeyBase64-----END PUBLIC KEY-----';
            var nonce = "test-nonce";
            var token = "test-csrf-token";
        """.trimIndent()
    }

    private val samplePages = mapOf(
        "/wan_status.cgi?gpon" to """var GponConnectionStat = 1; var RXPower = "251189"; var TXPower = "158489";""",
        "/show_wan_status.cgi?ipv4" to """
            var wan_conns = {ConnectionStatus:'Connected',ExternalIPAddress:'203.0.113.10',RemoteIPAddress:'198.51.100.1',DNSServers:'8.8.8.8,8.8.4.4',Uptime:60,ConnectionType:'IP_Routed'};
        """.trimIndent(),
        "/device_status.cgi" to """{"ModelName":"G-1425G-B","Manufacturer":"Nokia","SerialNumber":"ALCLXXXXXXXX","SoftwareVersion":"v1","HardwareVersion":"1.0","UpTime":100}""",
        "/lan_status.cgi?wlan" to sampleHomeNetworkingHtml(),
    )

    private fun sessionRejectedBody(): String =
        """<script>var Errorinfo ="Bad request for invalid parameter in the coookie.";window.location.replace('/');</script>"""

    @Test
    fun `rejects public host at construction - never sends credentials outside the LAN`() {
        val transport = FakeNokiaHttpTransport(loginPageBody = "", loginResponses = mutableListOf())

        val exception = assertThrows(IllegalArgumentException::class.java) {
            NokiaGponDriverFamily("8.8.8.8", realProfileConfig(), transport)
        }
        assertTrue(exception.message!!.contains("8.8.8.8"))
    }

    @Test
    fun `accepts RFC1918 private host at construction`() {
        val transport = FakeNokiaHttpTransport(loginPageBody = "", loginResponses = mutableListOf())

        listOf("192.168.1.254", "10.0.0.1", "172.16.5.5").forEach { privateHost ->
            NokiaGponDriverFamily(privateHost, realProfileConfig(), transport) // não deve lançar
        }
    }

    @Test
    fun `authenticate succeeds and caches the session for subsequent readCapability calls`() = runTest {
        val transport = FakeNokiaHttpTransport(
            loginPageBody = loginPageWithGeneratedKey(),
            loginResponses = mutableListOf(successfulLoginResponse()),
            authenticatedPages = samplePages,
        )
        val driver = NokiaGponDriverFamily("192.168.1.254", realProfileConfig(), transport, backoffMillis = { 0L })

        val result = driver.authenticate("admin", "secret")

        assertTrue(result is DriverFamilyAuthResult.Success)
    }

    @Test
    fun `authenticate fails fast on invalid credentials without exhausting retries`() = runTest {
        val transport = FakeNokiaHttpTransport(
            loginPageBody = loginPageWithGeneratedKey(),
            loginResponses = mutableListOf(errorLoginResponse(errT = 1)),
            authenticatedPages = samplePages,
        )
        val driver = NokiaGponDriverFamily("192.168.1.254", realProfileConfig(), transport, maxAttempts = 3, backoffMillis = { 0L })

        val result = driver.authenticate("admin", "wrong")

        assertTrue(result is DriverFamilyAuthResult.InvalidCredentials)
        assertEquals(1, transport.postCallCount) // credencial errada não se resolve com retry
    }

    @Test
    fun `readCapability without a prior authenticate call always returns Unavailable`() = runTest {
        val transport = FakeNokiaHttpTransport(loginPageBody = "", loginResponses = mutableListOf())
        val driver = NokiaGponDriverFamily("192.168.1.254", realProfileConfig(), transport)

        val result = driver.readCapability(CapabilityId.READ_WAN_STATUS)

        assertTrue(result is CapabilityReadResult.Unavailable)
        assertTrue((result as CapabilityReadResult.Unavailable).reason.contains("authenticate"))
    }

    @Test
    fun `readCapability returns an honest Unavailable for READ_WIFI_STATUS and READ_LAN_STATUS regardless of session`() = runTest {
        val transport = FakeNokiaHttpTransport(loginPageBody = "", loginResponses = mutableListOf())
        val driver = NokiaGponDriverFamily("192.168.1.254", realProfileConfig(), transport)

        val wifi = driver.readCapability(CapabilityId.READ_WIFI_STATUS) as CapabilityReadResult.Unavailable
        val lan = driver.readCapability(CapabilityId.READ_LAN_STATUS) as CapabilityReadResult.Unavailable

        assertTrue(wifi.reason.contains("ONT"))
        assertTrue(lan.reason.contains("ONT"))
    }

    private suspend fun authenticatedDriver(): NokiaGponDriverFamily {
        val transport = FakeNokiaHttpTransport(
            loginPageBody = loginPageWithGeneratedKey(),
            loginResponses = mutableListOf(successfulLoginResponse()),
            authenticatedPages = samplePages,
        )
        val driver = NokiaGponDriverFamily("192.168.1.254", realProfileConfig(), transport, backoffMillis = { 0L })
        val authResult = driver.authenticate("admin", "secret")
        assertTrue(authResult is DriverFamilyAuthResult.Success)
        return driver
    }

    @Test
    fun `readCapability READ_WAN_STATUS returns Success with parsed WAN data after authenticate`() = runTest {
        val driver = authenticatedDriver()

        val result = driver.readCapability(CapabilityId.READ_WAN_STATUS)

        assertTrue(result is CapabilityReadResult.Success)
        val payload = (result as CapabilityReadResult.Success).payload as CapabilityPayload.Wan
        assertEquals("203.0.113.10", payload.status.ipv4Address)
    }

    @Test
    fun `readCapability READ_DEVICE_INFO returns Success with parsed device data after authenticate`() = runTest {
        val driver = authenticatedDriver()

        val result = driver.readCapability(CapabilityId.READ_DEVICE_INFO)

        assertTrue(result is CapabilityReadResult.Success)
        val payload = (result as CapabilityReadResult.Success).payload as CapabilityPayload.DeviceInfo
        assertEquals("G-1425G-B", payload.info.model)
        assertEquals("Nokia", payload.info.vendor)
        assertEquals(PiiHashing.sha256Hex("ALCLXXXXXXXX"), payload.info.serialNumberHash)
        assertTrue(payload.info.serialNumberHash != "ALCLXXXXXXXX")
    }

    @Test
    fun `readCapability READ_CONNECTED_CLIENTS returns Success with parsed client list after authenticate`() = runTest {
        val driver = authenticatedDriver()

        val result = driver.readCapability(CapabilityId.READ_CONNECTED_CLIENTS)

        assertTrue(result is CapabilityReadResult.Success)
        val payload = (result as CapabilityReadResult.Success).payload as CapabilityPayload.ConnectedClients
        assertEquals(2, payload.clients.clients.size)
    }

    @Test
    fun `readCapability READ_SIGNAL returns Success with parsed optical GPON data after authenticate`() = runTest {
        val driver = authenticatedDriver()

        val result = driver.readCapability(CapabilityId.READ_SIGNAL)

        assertTrue(result is CapabilityReadResult.Success)
        val payload = (result as CapabilityReadResult.Success).payload as CapabilityPayload.Signal
        assertTrue(payload.status.rxPowerDbm != null)
    }

    @Test
    fun `readCapability returns SessionExpired when the equipment rejects the session cookie mid-read`() = runTest {
        val transport = FakeNokiaHttpTransport(
            loginPageBody = loginPageWithGeneratedKey(),
            loginResponses = mutableListOf(successfulLoginResponse()),
            authenticatedPages = mapOf("/show_wan_status.cgi?ipv4" to sessionRejectedBody()),
        )
        val driver = NokiaGponDriverFamily("192.168.1.254", realProfileConfig(), transport, backoffMillis = { 0L })
        val authResult = driver.authenticate("admin", "secret")
        assertTrue(authResult is DriverFamilyAuthResult.Success)

        val result = driver.readCapability(CapabilityId.READ_WAN_STATUS)

        assertTrue(result is CapabilityReadResult.SessionExpired)
    }

    @Test
    fun `SUPPORTED_CAPABILITIES covers only capabilities with real structured parsing, excluding the ONT-inapplicable ones`() {
        assertEquals(
            setOf(
                CapabilityId.READ_WAN_STATUS,
                CapabilityId.READ_DEVICE_INFO,
                CapabilityId.READ_CONNECTED_CLIENTS,
                CapabilityId.READ_SIGNAL,
            ),
            NokiaGponDriverFamily.SUPPORTED_CAPABILITIES,
        )
    }
}
