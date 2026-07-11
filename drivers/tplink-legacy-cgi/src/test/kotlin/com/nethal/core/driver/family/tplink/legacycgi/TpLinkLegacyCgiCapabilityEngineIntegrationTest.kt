package com.nethal.core.driver.family.tplink.legacycgi

import com.nethal.core.capability.CapabilityEngine
import com.nethal.core.catalog.CapabilityReadResult
import com.nethal.core.model.CapabilityId
import com.nethal.core.model.CapabilityPayload
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ponta a ponta real (issue #19, mesmo molde de `TpLinkStokLuciCapabilityEngineIntegrationTest`):
 * [CapabilityEngine] gerenciando sessão de verdade contra [TpLinkLegacyCgiDriverFamily], sem mockar
 * `authenticate`/`readCapability`.
 */
class TpLinkLegacyCgiCapabilityEngineIntegrationTest {

    private fun realProfileConfig(): TpLinkLegacyCgiDriverConfig = TpLinkLegacyCgiDriverConfig(
        loginValidationBundle = TpLinkLegacyCgiBundleConfig(
            sections = listOf(
                TpLinkLegacyCgiSectionConfig("IGD_DEV_INFO", listOf("modelName", "description", "X_TP_isFD")),
                TpLinkLegacyCgiSectionConfig("ETH_SWITCH", listOf("numberOfVirtualPorts")),
                TpLinkLegacyCgiSectionConfig("SYS_MODE", listOf("mode")),
                TpLinkLegacyCgiSectionConfig("/cgi/info", emptyList()),
            ),
        ),
        deviceInfoIndex = 0,
        ethSwitchIndex = 1,
        sysModeIndex = 2,
        wifiStatusBundle = TpLinkLegacyCgiBundleConfig(
            sections = listOf(TpLinkLegacyCgiSectionConfig("LAN_WLAN", listOf("name", "SSID"))),
        ),
        wifiStatusIndex = 0,
        connectedClientsBundle = TpLinkLegacyCgiBundleConfig(
            sections = listOf(
                TpLinkLegacyCgiSectionConfig(
                    "LAN_HOST_ENTRY",
                    listOf("leaseTimeRemaining", "MACAddress", "hostName", "IPAddress"),
                ),
            ),
        ),
        connectedClientsIndex = 0,
    )

    private fun basicCookie(user: String, pass: String): String =
        "Basic " + java.util.Base64.getEncoder().encodeToString("$user:$pass".toByteArray(Charsets.UTF_8))

    private fun responsesForSuccessfulSnapshot(config: TpLinkLegacyCgiDriverConfig): Map<String, com.nethal.core.driver.tplink.TplinkHttpResponse> {
        val deviceInfoRequestBody = TpLinkLegacyCgiResponseParser.buildRequestBody(config.loginValidationSections())
        val wifiRequestBody = TpLinkLegacyCgiResponseParser.buildRequestBody(config.wifiStatusSections())
        val clientsRequestBody = TpLinkLegacyCgiResponseParser.buildRequestBody(config.connectedClientsSections())

        return mapOf(
            deviceInfoRequestBody to deviceInfoBundleResponse(),
            wifiRequestBody to lanWlanResponse(),
            clientsRequestBody to lanHostEntryResponse(),
        )
    }

    @Test
    fun `readCapability authenticates on first call and reuses the session on subsequent calls`() = runTest {
        val config = realProfileConfig()
        val transport = FakeTpLinkLegacyCgiHttpTransport(
            expectedAuthorizationCookie = basicCookie("admin", "secret"),
            responsesByRequestBody = responsesForSuccessfulSnapshot(config),
        )
        val driver = TpLinkLegacyCgiDriverFamily("192.168.0.1", config, transport, backoffMillis = { 0L })
        val engine = CapabilityEngine(driver, username = "admin", password = "secret")

        assertEquals(0, transport.postCallCount)

        val first = engine.readCapability(CapabilityId.READ_WIFI_STATUS)
        assertTrue(first is CapabilityReadResult.Success)
        val wifiPayload = (first as CapabilityReadResult.Success).payload as CapabilityPayload.Wifi
        assertEquals("Casa-2.4G", wifiPayload.status.radios.first().ssid)

        // authenticate() (1 chamada: valida credencial) + 1 leitura de wifi = 2.
        assertEquals(2, transport.postCallCount)

        val second = engine.readCapability(CapabilityId.READ_DEVICE_INFO)
        assertTrue(second is CapabilityReadResult.Success)
        val deviceInfoPayload = (second as CapabilityReadResult.Success).payload as CapabilityPayload.DeviceInfo
        assertEquals("Archer C20", deviceInfoPayload.info.model)

        // Segunda leitura não reautentica - só mais uma chamada (total 3, não 5).
        assertEquals(3, transport.postCallCount)
    }

    @Test
    fun `readCapability before any authentication never touches the transport`() = runTest {
        val transport = FakeTpLinkLegacyCgiHttpTransport()
        val driver = TpLinkLegacyCgiDriverFamily("192.168.0.1", realProfileConfig(), transport)

        val result = driver.readCapability(CapabilityId.READ_WIFI_STATUS)

        assertTrue(result is CapabilityReadResult.Unavailable)
        assertEquals(0, transport.postCallCount)
    }

    @Test
    fun `session expiry mid-flow is renewed automatically by the CapabilityEngine, transparently to the caller`() = runTest {
        val config = realProfileConfig()
        val transport = FakeTpLinkLegacyCgiHttpTransport(
            expectedAuthorizationCookie = basicCookie("admin", "secret"),
            responsesByRequestBody = responsesForSuccessfulSnapshot(config),
            loginRequestBody = TpLinkLegacyCgiResponseParser.buildRequestBody(config.loginValidationSections()),
            expireAfterCallCount = 1, // 1a leitura autenticada de cada login funciona, a 2a expira (401)
        )
        val driver = TpLinkLegacyCgiDriverFamily("192.168.0.1", config, transport, backoffMillis = { 0L })
        val engine = CapabilityEngine(driver, username = "admin", password = "secret")

        // READ_DEVICE_INFO usa o mesmo corpo de login() neste protocolo (login e leitura de device
        // info replicam o mesmo bundle) — usar WIFI/CLIENTS aqui, que têm bundle próprio, para o fake
        // conseguir distinguir "leitura autenticada" de "(re)login".
        val first = engine.readCapability(CapabilityId.READ_WIFI_STATUS) // login (1) + 1a leitura (2), ok
        assertTrue(first is CapabilityReadResult.Success)

        val second = engine.readCapability(CapabilityId.READ_CONNECTED_CLIENTS) // leitura (3) -> 401 -> renova (4) -> leitura do novo login (5), ok
        assertTrue(second is CapabilityReadResult.Success)
        assertTrue(engine.isSessionActive)

        assertEquals(5, transport.postCallCount)
    }

    @Test
    fun `a raw SessionExpired straight from the DriverFamily is distinguishable from a generic Failure`() = runTest {
        val config = realProfileConfig()
        val transport = FakeTpLinkLegacyCgiHttpTransport(
            expectedAuthorizationCookie = basicCookie("admin", "secret"),
            responsesByRequestBody = responsesForSuccessfulSnapshot(config),
            loginRequestBody = TpLinkLegacyCgiResponseParser.buildRequestBody(config.loginValidationSections()),
            expireAfterCallCount = 0, // login passa; qualquer leitura autenticada seguinte já expira
        )
        val driver = TpLinkLegacyCgiDriverFamily("192.168.0.1", config, transport, backoffMillis = { 0L })

        val authResult = driver.authenticate("admin", "secret")
        assertTrue(authResult is com.nethal.core.catalog.DriverFamilyAuthResult.Success)

        val readResult = driver.readCapability(CapabilityId.READ_WIFI_STATUS)
        assertTrue(readResult is CapabilityReadResult.SessionExpired)
    }

    @Test
    fun `credentials are never present in any failure reason produced along the authenticated read path`() = runTest {
        val secretPassword = "Sup3rS3nhaDoRoteador"
        val transport = FakeTpLinkLegacyCgiHttpTransport(
            expectedAuthorizationCookie = basicCookie("admin", "correct-password"),
        )
        val driver = TpLinkLegacyCgiDriverFamily("192.168.0.1", realProfileConfig(), transport, backoffMillis = { 0L })
        val engine = CapabilityEngine(driver, username = "admin", password = secretPassword)

        val result = engine.readCapability(CapabilityId.READ_WIFI_STATUS)

        assertTrue(result is CapabilityReadResult.Unavailable)
        assertFalse((result as CapabilityReadResult.Unavailable).reason.contains(secretPassword))
        assertFalse(engine.toString().contains(secretPassword))
    }
}
