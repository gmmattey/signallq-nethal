package com.nethal.core.driver.family.nokia.gpon

import com.nethal.core.capability.CapabilityEngine
import com.nethal.core.catalog.CapabilityReadResult
import com.nethal.core.driver.nokia.NokiaHttpTransport
import com.nethal.core.driver.nokia.NokiaHttpResponse
import com.nethal.core.model.CapabilityId
import com.nethal.core.model.CapabilityPayload
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.util.Base64

/**
 * Ponta a ponta real (mesmo espírito de `TpLinkStokLuciCapabilityEngineIntegrationTest`, issue #16):
 * [CapabilityEngine] gerenciando sessão de verdade contra [NokiaGponDriverFamily], sem mockar
 * `authenticate`/`readCapability`.
 */
class NokiaGponCapabilityEngineIntegrationTest {

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

    private fun wanStatusBody() = """
        var wan_conns = {ConnectionStatus:'Connected',ExternalIPAddress:'203.0.113.10',RemoteIPAddress:'198.51.100.1',DNSServers:'8.8.8.8,8.8.4.4',Uptime:60,ConnectionType:'IP_Routed'};
    """.trimIndent()

    private fun sessionRejectedBody(): String =
        """<script>var Errorinfo ="Bad request for invalid parameter in the coookie.";window.location.replace('/');</script>"""

    /**
     * Fake que simula sessão Nokia expirando entre leituras autenticadas: as N primeiras leituras
     * de `wanStatusPath` após cada login bem-sucedido devolvem [wanStatusBody], as seguintes (até o
     * próximo login) devolvem [sessionRejectedBody] — mesmo espírito de
     * `FakeTpLinkStokLuciHttpTransport.expireAuthenticatedReadsAfter`, adaptado ao sinal de sessão
     * rejeitada por conteúdo (não por HTTP 401/403) documentado em `NokiaGponDriverFamily`.
     */
    private inner class SessionExpiringFakeNokiaHttpTransport(
        private val loginPageBody: String,
        private val loginResponses: MutableList<NokiaHttpResponse>,
        private val healthyReadsPerLoginBeforeExpiry: Int,
    ) : NokiaHttpTransport {
        var postCallCount = 0
            private set
        private var readsSinceLastLogin = 0

        override fun get(url: String, extraHeaders: Map<String, String>): NokiaHttpResponse {
            if (!url.contains("show_wan_status")) {
                return NokiaHttpResponse(200, loginPageBody, emptyMap(), emptyMap())
            }
            readsSinceLastLogin++
            val body = if (readsSinceLastLogin > healthyReadsPerLoginBeforeExpiry) sessionRejectedBody() else wanStatusBody()
            return NokiaHttpResponse(200, body, emptyMap(), emptyMap())
        }

        override fun post(url: String, body: String, initCookies: Map<String, String>): NokiaHttpResponse {
            postCallCount++
            readsSinceLastLogin = 0
            check(loginResponses.isNotEmpty()) { "SessionExpiringFakeNokiaHttpTransport: nenhuma resposta de login configurada" }
            return loginResponses.removeAt(0)
        }
    }

    private fun successfulLoginResponse(sid: String = "abc123sessionid") = NokiaHttpResponse(
        statusCode = 299,
        body = "",
        headers = mapOf("x-sid" to sid),
        cookies = mapOf("sid" to sid, "lsid" to "legacy-$sid", "lang" to "eng"),
    )

    @Test
    fun `readCapability authenticates on first call and reuses the session on subsequent calls`() = runTest {
        val transport = SessionExpiringFakeNokiaHttpTransport(
            loginPageBody = loginPageWithGeneratedKey(),
            loginResponses = mutableListOf(successfulLoginResponse(), successfulLoginResponse()),
            healthyReadsPerLoginBeforeExpiry = 10, // não expira nesta prova
        )
        val driver = NokiaGponDriverFamily("192.168.1.254", realProfileConfig(), transport, backoffMillis = { 0L })
        val engine = CapabilityEngine(driver, username = "admin", password = "secret")

        assertEquals(0, transport.postCallCount)

        val first = engine.readCapability(CapabilityId.READ_WAN_STATUS)
        assertTrue(first is CapabilityReadResult.Success)
        val payload = (first as CapabilityReadResult.Success).payload as CapabilityPayload.Wan
        assertEquals("203.0.113.10", payload.status.ipv4Address)
        assertEquals(1, transport.postCallCount) // 1 POST de login

        val second = engine.readCapability(CapabilityId.READ_WAN_STATUS)
        assertTrue(second is CapabilityReadResult.Success)
        assertEquals(1, transport.postCallCount) // segunda leitura não fez novo login
    }

    @Test
    fun `session rejected mid-flow is renewed automatically by the CapabilityEngine, transparently to the caller`() = runTest {
        val transport = SessionExpiringFakeNokiaHttpTransport(
            loginPageBody = loginPageWithGeneratedKey(),
            loginResponses = mutableListOf(successfulLoginResponse(), successfulLoginResponse()),
            healthyReadsPerLoginBeforeExpiry = 1, // 1a leitura de cada login funciona, a 2a é rejeitada
        )
        val driver = NokiaGponDriverFamily("192.168.1.254", realProfileConfig(), transport, backoffMillis = { 0L })
        val engine = CapabilityEngine(driver, username = "admin", password = "secret")

        val first = engine.readCapability(CapabilityId.READ_WAN_STATUS) // login + 1a leitura (ok)
        assertTrue(first is CapabilityReadResult.Success)

        val second = engine.readCapability(CapabilityId.READ_WAN_STATUS) // 2a leitura sob o mesmo login -> rejeitada -> renova -> 1a leitura do novo login (ok)
        assertTrue(second is CapabilityReadResult.Success)
        assertTrue(engine.isSessionActive)

        assertEquals(2, transport.postCallCount) // 2 logins (o segundo é a renovação automática)
    }

    @Test
    fun `a raw SessionExpired straight from the DriverFamily is distinguishable from a generic Failure`() = runTest {
        val transport = SessionExpiringFakeNokiaHttpTransport(
            loginPageBody = loginPageWithGeneratedKey(),
            loginResponses = mutableListOf(successfulLoginResponse()),
            healthyReadsPerLoginBeforeExpiry = 0, // já rejeita na 1a leitura autenticada
        )
        val driver = NokiaGponDriverFamily("192.168.1.254", realProfileConfig(), transport, backoffMillis = { 0L })

        val authResult = driver.authenticate("admin", "secret")
        assertTrue(authResult is com.nethal.core.catalog.DriverFamilyAuthResult.Success)

        val readResult = driver.readCapability(CapabilityId.READ_WAN_STATUS)
        assertTrue(readResult is CapabilityReadResult.SessionExpired)
    }

    @Test
    fun `credentials are never present in any failure reason produced along the authenticated read path`() = runTest {
        val secretPassword = "Sup3rS3nhaDoRoteador"
        val transport = SessionExpiringFakeNokiaHttpTransport(
            loginPageBody = "<html>sem pubkey aqui</html>",
            loginResponses = mutableListOf(),
            healthyReadsPerLoginBeforeExpiry = 10,
        )
        val driver = NokiaGponDriverFamily("192.168.1.254", realProfileConfig(), transport, backoffMillis = { 0L })
        val engine = CapabilityEngine(driver, username = "admin", password = secretPassword)

        val result = engine.readCapability(CapabilityId.READ_WAN_STATUS)

        assertTrue(result is CapabilityReadResult.Unavailable)
        assertTrue(!(result as CapabilityReadResult.Unavailable).reason.contains(secretPassword))
        assertTrue(!engine.toString().contains(secretPassword))
    }
}
