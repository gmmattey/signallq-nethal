package com.nethal.core.driver.family.tplink.xdrds

import com.nethal.core.catalog.CapabilityReadResult
import com.nethal.core.catalog.DriverFamilyAuthResult
import com.nethal.core.model.CapabilityId
import com.nethal.core.protocol.http.HttpTransport
import com.nethal.core.protocol.http.HttpTransportResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TpLinkXdrDsDriverFamilyTest {

    @Test
    fun `rejects public host for XDR family`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            TpLinkXdrDsDriverFamily(
                "8.8.8.8",
                config(),
                FakeTpLinkXdrDsHttpTransport(expectNonceFlow = true),
            )
        }
        assertTrue(exception.message!!.contains("8.8.8.8"))
    }

    @Test
    fun `nonce based login and authenticated read succeed`() = runTest {
        val transport = FakeTpLinkXdrDsHttpTransport(expectNonceFlow = true)
        val driver = TpLinkXdrDsDriverFamily("192.168.0.1", config(), transport, backoffMillis = { 0L })

        val result = driver.readRaw("admin", "secret")

        assertTrue(result is TpLinkXdrDsReadOutcome.Success)
        assertEquals("""{"error_code":0}""", (result as TpLinkXdrDsReadOutcome.Success).rawBody)
        assertEquals("3", transport.lastEncryptType)
    }

    @Test
    fun `legacy encoded password login still succeeds when encrypt info probe is unsupported`() = runTest {
        val transport = FakeTpLinkXdrDsHttpTransport(expectNonceFlow = false)
        val driver = TpLinkXdrDsDriverFamily("192.168.0.1", config(), transport, backoffMillis = { 0L })

        val result = driver.login("admin", "secret")

        assertTrue(result is TpLinkXdrDsLoginOutcome.Success)
        assertEquals("legacy", transport.lastLoginMode)
    }

    // --- authenticate()/readCapability() reais (issue #20) ---
    // Ver KDoc de TpLinkXdrDsDriverFamily: readCapability nunca promove dado a Success nesta
    // rodada (nenhum campo de resposta de /ds tem formato confirmado) — os testes abaixo cobrem a
    // sessão real (authenticate cacheado, reaproveitado por leituras subsequentes) e a distinção
    // honesta de motivo em cada Unavailable.

    @Test
    fun `authenticate caches a session reused by readCapability without a new login`() = runTest {
        val transport = FakeTpLinkXdrDsHttpTransport(expectNonceFlow = true)
        val driver = TpLinkXdrDsDriverFamily("192.168.0.1", config(), transport, backoffMillis = { 0L })

        val authResult = driver.authenticate("admin", "secret")
        assertTrue(authResult is DriverFamilyAuthResult.Success)

        driver.readCapability(CapabilityId.READ_WIFI_STATUS)
        val callsAfterFirstRead = transport.postCallCount
        // login (get_encrypt_info + login) = 2 chamadas + 1a leitura = 3.
        assertEquals(3, callsAfterFirstRead)

        driver.readCapability(CapabilityId.READ_CONNECTED_CLIENTS)
        // Sessão reaproveitada: só mais uma chamada (a segunda leitura), sem novo login.
        assertEquals(callsAfterFirstRead + 1, transport.postCallCount)
    }

    @Test
    fun `readCapability without a prior authenticate call is honestly Unavailable`() = runTest {
        val transport = FakeTpLinkXdrDsHttpTransport(expectNonceFlow = true)
        val driver = TpLinkXdrDsDriverFamily("192.168.0.1", config(), transport, backoffMillis = { 0L })

        val result = driver.readCapability(CapabilityId.READ_WIFI_STATUS) as CapabilityReadResult.Unavailable

        assertTrue(result.reason.contains("authenticate"))
        assertEquals(0, transport.postCallCount)
    }

    @Test
    fun `readCapability for a capability outside SUPPORTED_CAPABILITIES stays Unavailable`() = runTest {
        val transport = FakeTpLinkXdrDsHttpTransport(expectNonceFlow = true)
        val driver = TpLinkXdrDsDriverFamily("192.168.0.1", config(), transport, backoffMillis = { 0L })
        driver.authenticate("admin", "secret")

        val result = driver.readCapability(CapabilityId.READ_FIRMWARE) as CapabilityReadResult.Unavailable

        assertTrue(result.reason.contains("não implementa parsing"))
    }

    /**
     * Prova central da decisão de honestidade desta rodada: mesmo com sessão real e leitura
     * autenticada real bem-sucedida (`error_code=0` confirmado no corpo), nenhuma capability vira
     * `Success`/`EXPERIMENTAL` — o motivo explica que a leitura funcionou, mas nenhum campo de
     * capability tem formato confirmado (ver KDoc da classe).
     */
    @Test
    fun `readCapability executes the real authenticated read but never promotes it to capability data`() = runTest {
        val transport = FakeTpLinkXdrDsHttpTransport(expectNonceFlow = true)
        val driver = TpLinkXdrDsDriverFamily("192.168.0.1", config(), transport, backoffMillis = { 0L })
        driver.authenticate("admin", "secret")

        val result = driver.readCapability(CapabilityId.READ_WIFI_STATUS) as CapabilityReadResult.Unavailable

        assertTrue(result.reason.contains("error_code=0"))
        assertTrue(result.reason.contains("READ_WIFI_STATUS"))
    }

    private fun config() = TpLinkXdrDsDriverConfig(
        encryptInfoPath = "/",
        loginPath = "/",
        authenticatedPathTemplate = "/stok={stok}/ds",
        authenticatedReadPayloadJson = """{"method":"get","device_info":{"name":"info"}}""",
    )
}

private class FakeTpLinkXdrDsHttpTransport(
    private val expectNonceFlow: Boolean,
) : HttpTransport {
    var lastEncryptType: String? = null
        private set
    var lastLoginMode: String? = null
        private set
    var postCallCount = 0
        private set

    override fun get(url: String, extraHeaders: Map<String, String>): HttpTransportResponse =
        HttpTransportResponse(404, "", emptyMap(), emptyMap())

    override fun post(
        url: String,
        body: String,
        cookies: Map<String, String>,
        extraHeaders: Map<String, String>,
    ): HttpTransportResponse {
        postCallCount++
        return when {
            body.contains("get_encrypt_info") && expectNonceFlow -> HttpTransportResponse(
                200,
                """{"error_code":0,"encrypt_type":["3"],"nonce":"nonce-123"}""",
                emptyMap(),
                emptyMap(),
            )
            body.contains("get_encrypt_info") -> HttpTransportResponse(
                200,
                """{"error_code":1}""",
                emptyMap(),
                emptyMap(),
            )
            body.contains(""""login"""") -> {
                lastEncryptType = Regex(""""encrypt_type":"?([^",}]+)""").find(body)?.groupValues?.get(1)
                lastLoginMode = if (body.contains("encrypt_type")) "nonce" else "legacy"
                HttpTransportResponse(200, """{"stok":"stok-123"}""", emptyMap(), emptyMap())
            }
            url.contains("/stok=stok-123/ds") -> HttpTransportResponse(200, """{"error_code":0}""", emptyMap(), emptyMap())
            else -> HttpTransportResponse(404, "", emptyMap(), emptyMap())
        }
    }
}
