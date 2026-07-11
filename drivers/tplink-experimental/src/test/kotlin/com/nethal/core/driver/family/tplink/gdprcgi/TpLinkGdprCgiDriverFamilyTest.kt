package com.nethal.core.driver.family.tplink.gdprcgi

import com.nethal.core.catalog.CapabilityReadResult
import com.nethal.core.catalog.DriverFamilyAuthResult
import com.nethal.core.model.CapabilityId
import com.nethal.core.model.CapabilityPayload
import com.nethal.core.model.CapabilityState
import com.nethal.core.protocol.http.HttpTransport
import com.nethal.core.protocol.http.HttpTransportResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.net.URLDecoder
import java.security.KeyFactory
import java.security.spec.RSAPrivateKeySpec
import javax.crypto.Cipher

class TpLinkGdprCgiDriverFamilyTest {

    @Test
    fun `rejects public host for GDPR family`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            TpLinkGdprCgiDriverFamily(
                "8.8.8.8",
                c50Config(),
                FakeTpLinkGdprCgiHttpTransport(c50Config(), TpLinkGdprCgiCryptoMode.AES_CBC),
            )
        }
        assertTrue(exception.message!!.contains("8.8.8.8"))
    }

    @Test
    fun `cbc login and raw read succeed for GDPR CGI family`() = runTest {
        val config = c50Config()
        val transport = FakeTpLinkGdprCgiHttpTransport(config, TpLinkGdprCgiCryptoMode.AES_CBC)
        val driver = TpLinkGdprCgiDriverFamily("192.168.1.1", config, transport, backoffMillis = { 0L })

        val result = driver.readRaw("admin", "secret")

        assertTrue(result is TpLinkGdprCgiReadOutcome.Success)
        assertEquals("""$.ret=0;""", (result as TpLinkGdprCgiReadOutcome.Success).rawBody)
        assertEquals("token-cbc", transport.lastTokenId)
    }

    @Test
    fun `gcm login and raw read succeed for GDPR CGI family`() = runTest {
        val config = exGcmConfig()
        val transport = FakeTpLinkGdprCgiHttpTransport(config, TpLinkGdprCgiCryptoMode.AES_GCM)
        val driver = TpLinkGdprCgiDriverFamily("192.168.1.1", config, transport, backoffMillis = { 0L })

        val result = driver.readRaw("user", "secret")

        assertTrue(result is TpLinkGdprCgiReadOutcome.Success)
        assertEquals("""$.ret=0;""", (result as TpLinkGdprCgiReadOutcome.Success).rawBody)
        assertEquals("token-gcm", transport.lastTokenId)
    }

    // --- authenticate()/readCapability() reais (issue #20 — parser experimental por capability) ---

    @Test
    fun `authenticate caches a session reused by readCapability without a new login`() = runTest {
        val config = c50ConfigWithCapabilities()
        val transport = FakeTpLinkGdprCgiHttpTransport(config, TpLinkGdprCgiCryptoMode.AES_CBC)
        val driver = TpLinkGdprCgiDriverFamily("192.168.1.1", config, transport, backoffMillis = { 0L })

        val authResult = driver.authenticate("admin", "secret")
        assertTrue(authResult is DriverFamilyAuthResult.Success)

        val first = driver.readCapability(CapabilityId.READ_WIFI_STATUS)
        assertTrue(first is CapabilityReadResult.Success)
        val callsAfterFirstRead = transport.postCallCount
        // login (rsaKey + login) = 2 chamadas + 1a leitura = 3; nenhuma chamada nova de login.
        assertEquals(3, callsAfterFirstRead)

        val second = driver.readCapability(CapabilityId.READ_CONNECTED_CLIENTS)
        assertTrue(second is CapabilityReadResult.Success)
        // Sessão reaproveitada: só mais uma chamada (a segunda leitura), sem novo login.
        assertEquals(callsAfterFirstRead + 1, transport.postCallCount)
    }

    @Test
    fun `readCapability for READ_WIFI_STATUS returns EXPERIMENTAL state, never AVAILABLE, even when parsing succeeds`() = runTest {
        val config = c50ConfigWithCapabilities()
        val transport = FakeTpLinkGdprCgiHttpTransport(config, TpLinkGdprCgiCryptoMode.AES_CBC)
        val driver = TpLinkGdprCgiDriverFamily("192.168.1.1", config, transport, backoffMillis = { 0L })
        driver.authenticate("admin", "secret")

        val result = driver.readCapability(CapabilityId.READ_WIFI_STATUS) as CapabilityReadResult.Success

        assertEquals(CapabilityState.EXPERIMENTAL, result.capability.state)
        assertTrue(result.capability.reason!!.contains("sem validação contra hardware real"))
        val payload = result.payload as CapabilityPayload.Wifi
        assertEquals("Casa-2.4G", payload.status.radios.first().ssid)
    }

    @Test
    fun `readCapability for READ_CONNECTED_CLIENTS returns EXPERIMENTAL state with parsed fields`() = runTest {
        val config = c50ConfigWithCapabilities()
        val transport = FakeTpLinkGdprCgiHttpTransport(config, TpLinkGdprCgiCryptoMode.AES_CBC)
        val driver = TpLinkGdprCgiDriverFamily("192.168.1.1", config, transport, backoffMillis = { 0L })
        driver.authenticate("admin", "secret")

        val result = driver.readCapability(CapabilityId.READ_CONNECTED_CLIENTS) as CapabilityReadResult.Success

        assertEquals(CapabilityState.EXPERIMENTAL, result.capability.state)
        val payload = result.payload as CapabilityPayload.ConnectedClients
        val client = payload.clients.clients.first()
        assertEquals("Notebook", client.hostname)
        assertEquals("192.168.1.50", client.ipAddress)
        assertEquals("AA:BB:CC:DD:EE:FF", client.macAddress)
    }

    @Test
    fun `readCapability without a prior authenticate call is honestly Unavailable`() = runTest {
        val config = c50ConfigWithCapabilities()
        val transport = FakeTpLinkGdprCgiHttpTransport(config, TpLinkGdprCgiCryptoMode.AES_CBC)
        val driver = TpLinkGdprCgiDriverFamily("192.168.1.1", config, transport, backoffMillis = { 0L })

        val result = driver.readCapability(CapabilityId.READ_WIFI_STATUS) as CapabilityReadResult.Unavailable

        assertTrue(result.reason.contains("authenticate"))
        assertEquals(0, transport.postCallCount)
    }

    @Test
    fun `readCapability for a capability outside SUPPORTED_CAPABILITIES stays Unavailable`() = runTest {
        val config = c50ConfigWithCapabilities()
        val transport = FakeTpLinkGdprCgiHttpTransport(config, TpLinkGdprCgiCryptoMode.AES_CBC)
        val driver = TpLinkGdprCgiDriverFamily("192.168.1.1", config, transport, backoffMillis = { 0L })
        driver.authenticate("admin", "secret")

        val result = driver.readCapability(CapabilityId.READ_WAN_STATUS) as CapabilityReadResult.Unavailable

        assertTrue(result.reason.contains("não implementa parsing"))
    }

    @Test
    fun `readCapability never attempts parsing for login styles other than C50_GDPR_BODY_LOGIN`() = runTest {
        val config = exGcmConfig()
        val transport = FakeTpLinkGdprCgiHttpTransport(config, TpLinkGdprCgiCryptoMode.AES_GCM)
        val driver = TpLinkGdprCgiDriverFamily("192.168.1.1", config, transport, backoffMillis = { 0L })
        driver.authenticate("user", "secret")

        val result = driver.readCapability(CapabilityId.READ_WIFI_STATUS) as CapabilityReadResult.Unavailable

        assertTrue(result.reason.contains("C50_GDPR_BODY_LOGIN"))
    }

    private fun c50Config() = TpLinkGdprCgiDriverConfig(
        rsaKeyPath = "cgi/getParm",
        loginPath = "cgi_gdpr",
        loginStyle = TpLinkGdprCgiLoginStyle.C50_GDPR_BODY_LOGIN,
        cryptoMode = TpLinkGdprCgiCryptoMode.AES_CBC,
        rsaPaddingMode = TpLinkGdprCgiRsaPaddingMode.PKCS1_V1_5,
        tokenPath = "/",
        authenticatedReadPath = "cgi_gdpr",
        authenticatedReadPlaintext = "1\r\n[/cgi/info#0,0,0,0,0,0#0,0,0,0,0,0]0,0\r\n",
    )

    private fun c50ConfigWithCapabilities() = c50Config().copy(
        capabilitySections = listOf(
            TpLinkGdprCgiCapabilitySection(
                capabilityId = "READ_WIFI_STATUS",
                oid = "LAN_WLAN",
                fields = listOf("name", "SSID"),
            ),
            TpLinkGdprCgiCapabilitySection(
                capabilityId = "READ_CONNECTED_CLIENTS",
                oid = "LAN_HOST_ENTRY",
                fields = listOf("hostName", "IPAddress", "MACAddress"),
            ),
        ),
    )

    private fun exGcmConfig() = TpLinkGdprCgiDriverConfig(
        rsaKeyPath = "cgi/getGDPRParm",
        loginPath = "cgi_gdpr?9",
        loginStyle = TpLinkGdprCgiLoginStyle.EX_JSON_GDPR_BODY_LOGIN,
        cryptoMode = TpLinkGdprCgiCryptoMode.AES_GCM,
        rsaPaddingMode = TpLinkGdprCgiRsaPaddingMode.PKCS1_V1_5,
        tokenPath = "/",
        authenticatedReadPath = "cgi_gdpr?9",
        authenticatedReadPlaintext = """{"operation":"go","oid":"DEV2_DEV_INFO","data":{"stack":"0,0,0,0,0,0","pstack":"0,0,0,0,0,0"}}""",
    )
}

private class FakeTpLinkGdprCgiHttpTransport(
    private val config: TpLinkGdprCgiDriverConfig,
    private val cryptoMode: TpLinkGdprCgiCryptoMode,
) : HttpTransport {

    var lastTokenId: String? = null
        private set

    var postCallCount = 0
        private set

    private var aesKeyAscii: String? = null
    private var aesIvOrNonceAscii: String? = null

    override fun get(url: String, extraHeaders: Map<String, String>): HttpTransportResponse =
        if (url.endsWith("/") || url == "http://192.168.1.1/") {
            val token = if (cryptoMode == TpLinkGdprCgiCryptoMode.AES_CBC) "token-cbc" else "token-gcm"
            lastTokenId = token
            HttpTransportResponse(200, """<script>var token="$token";</script>""", emptyMap(), emptyMap())
        } else {
            HttpTransportResponse(404, "", emptyMap(), emptyMap())
        }

    override fun post(
        url: String,
        body: String,
        cookies: Map<String, String>,
        extraHeaders: Map<String, String>,
    ): HttpTransportResponse {
        postCallCount++
        return when {
            url.contains(config.rsaKeyPath) -> HttpTransportResponse(
                200,
                """var ee="10001";var nn="${TestSignKeyFixture.MODULUS_HEX}";var seq="12345";""",
                emptyMap(),
                emptyMap(),
            )
            extraHeaders["TokenID"] != null && url.contains(config.authenticatedReadPath) ->
                simulateAuthenticatedRead(body, extraHeaders)
            url.contains(config.loginPath) -> simulateEncryptedLogin(body)
            else -> HttpTransportResponse(404, "", emptyMap(), emptyMap())
        }
    }

    private fun simulateEncryptedLogin(body: String): HttpTransportResponse {
        captureAesContextFromBody(body)
        val responseBody = encryptedResponse("""$.ret=0;""")
        return HttpTransportResponse(
            200,
            responseBody,
            emptyMap(),
            mapOf("JSESSIONID" to "session-ok"),
        )
    }

    private fun simulateAuthenticatedRead(body: String, headers: Map<String, String>): HttpTransportResponse {
        require(headers["TokenID"] == lastTokenId) { "TokenID ausente ou incorreto" }
        captureAesContextFromBody(body)
        return HttpTransportResponse(200, encryptedResponse(responsePlaintextFor(body)), emptyMap(), emptyMap())
    }

    /**
     * Responde de forma diferente por seção pedida (`LAN_WLAN`/`LAN_HOST_ENTRY`), decifrando o
     * `data=` recebido — só para CBC (único modo usado pelos testes de capability desta rodada).
     * Fora desse caso (GCM, ou seção desconhecida) devolve o corpo canônico `$.ret=0;` já usado
     * pelos testes de `readRaw`, sem quebrar o comportamento anterior.
     */
    private fun responsePlaintextFor(body: String): String {
        if (cryptoMode != TpLinkGdprCgiCryptoMode.AES_CBC) return """$.ret=0;"""
        val key = aesKeyAscii ?: return """$.ret=0;"""
        val iv = aesIvOrNonceAscii ?: return """$.ret=0;"""
        val incomingDataBase64 = Regex("""data=([^\r\n]+)""").find(body)?.groupValues?.get(1) ?: return """$.ret=0;"""
        val incomingPlaintext = runCatching { TpLinkGdprCgiCrypto.aesCbcDecryptToString(key, iv, incomingDataBase64) }.getOrNull()
            ?: return """$.ret=0;"""
        return when {
            incomingPlaintext.contains("LAN_WLAN") -> "[1,1,0,0,0,0]0\r\nname=wlan0\r\nSSID=Casa-2.4G\r\n[error]0"
            incomingPlaintext.contains("LAN_HOST_ENTRY") ->
                "[1,0,0,0,0,0]0\r\nhostName=Notebook\r\nIPAddress=192.168.1.50\r\nMACAddress=AA:BB:CC:DD:EE:FF\r\n[error]0"
            else -> """$.ret=0;"""
        }
    }

    private fun captureAesContextFromBody(body: String) {
        val signHex = Regex("""sign=([0-9a-f]+)""").find(body)?.groupValues?.get(1)
            ?: error("sign ausente")
        val signPlaintext = decryptPkcs1Chunked(signHex)
        when (cryptoMode) {
            TpLinkGdprCgiCryptoMode.AES_CBC -> {
                Regex("""key=(\d{16})""").find(signPlaintext)?.groupValues?.get(1)?.let { aesKeyAscii = it }
                Regex("""iv=(\d{16})""").find(signPlaintext)?.groupValues?.get(1)?.let { aesIvOrNonceAscii = it }
            }
            TpLinkGdprCgiCryptoMode.AES_GCM -> {
                Regex("""key=([^&]+)""").find(signPlaintext)?.groupValues?.get(1)?.let {
                    aesKeyAscii = String(TpLinkGdprCgiCrypto.base64Decode(it), Charsets.UTF_8)
                }
                Regex("""iv=([^&]+)""").find(signPlaintext)?.groupValues?.get(1)?.let {
                    aesIvOrNonceAscii = String(TpLinkGdprCgiCrypto.base64Decode(it), Charsets.UTF_8)
                }
            }
        }
        if (body.contains("data=")) {
            val dataValue = Regex("""data=([^\r\n]+)""").find(body)?.groupValues?.get(1)
            if (dataValue != null && !body.contains("?data=")) {
                URLDecoder.decode(dataValue, Charsets.UTF_8)
            }
        }
    }

    private fun encryptedResponse(plaintext: String): String {
        val key = requireNotNull(aesKeyAscii)
        val ivOrNonce = requireNotNull(aesIvOrNonceAscii)
        return when (cryptoMode) {
            TpLinkGdprCgiCryptoMode.AES_CBC -> TpLinkGdprCgiCrypto.aesCbcEncrypt(key, ivOrNonce, plaintext)
            TpLinkGdprCgiCryptoMode.AES_GCM -> {
                val (ciphertext, tag) = TpLinkGdprCgiCrypto.aesGcmEncrypt(key, ivOrNonce, plaintext)
                ciphertext + tag
            }
        }
    }

    private fun decryptPkcs1Chunked(signHex: String): String {
        val modulus = BigInteger(TestSignKeyFixture.MODULUS_HEX, 16)
        val privateExponent = BigInteger(TestSignKeyFixture.PRIVATE_EXPONENT_HEX, 16)
        val privateKey = KeyFactory.getInstance("RSA").generatePrivate(RSAPrivateKeySpec(modulus, privateExponent))
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        val blockHexSize = ((modulus.bitLength() + 7) / 8) * 2
        val plaintext = StringBuilder()
        var offset = 0
        while (offset < signHex.length) {
            val end = minOf(offset + blockHexSize, signHex.length)
            cipher.init(Cipher.DECRYPT_MODE, privateKey)
            val decryptedChunk = cipher.doFinal(hexToBytes(signHex.substring(offset, end)))
            plaintext.append(String(decryptedChunk, Charsets.UTF_8))
            offset = end
        }
        return plaintext.toString()
    }

    private fun hexToBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { index ->
        hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}
