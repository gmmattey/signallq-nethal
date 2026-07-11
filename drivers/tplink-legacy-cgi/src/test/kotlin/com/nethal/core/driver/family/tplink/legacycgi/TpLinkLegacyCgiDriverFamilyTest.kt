package com.nethal.core.driver.family.tplink.legacycgi

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Movido de `driver/tplink/TplinkC20OntDriverTest.kt` no passo 4 do plano de refatoração HAL
 * (`docs/architecture/hal-layering-model.md` §10) — mesma cobertura de comportamento (retry,
 * classificação de falha, parsing do snapshot completo), adaptada para receber
 * [TpLinkLegacyCgiDriverConfig] explicitamente (antes as seções/campos eram hardcoded na classe).
 */
class TpLinkLegacyCgiDriverFamilyTest {

    /** Mesmo `driverConfig` do profile real `tplink_archer_c20_v1` no catálogo (ver `catalog-2026.07.13.json`). */
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
        "Basic " + Base64.getEncoder().encodeToString("$user:$pass".toByteArray(Charsets.UTF_8))

    @Test
    fun `rejects public host at construction - never sends credentials outside the LAN`() {
        val transport = FakeTpLinkLegacyCgiHttpTransport()

        val exception = assertThrows(IllegalArgumentException::class.java) {
            TpLinkLegacyCgiDriverFamily("8.8.8.8", realProfileConfig(), transport)
        }
        assertTrue(exception.message!!.contains("8.8.8.8"))
    }

    @Test
    fun `rejects other well-known public hosts too - not a single hardcoded exception`() {
        val transport = FakeTpLinkLegacyCgiHttpTransport()

        listOf("1.1.1.1", "203.0.113.10", "142.250.0.1").forEach { publicHost ->
            assertThrows(IllegalArgumentException::class.java) {
                TpLinkLegacyCgiDriverFamily(publicHost, realProfileConfig(), transport)
            }
        }
    }

    @Test
    fun `accepts RFC1918 private host at construction`() {
        val transport = FakeTpLinkLegacyCgiHttpTransport()

        listOf("192.168.0.1", "10.0.0.1", "172.16.5.5").forEach { privateHost ->
            TpLinkLegacyCgiDriverFamily(privateHost, realProfileConfig(), transport) // não deve lançar
        }
    }

    private fun responsesForSuccessfulSnapshot(config: TpLinkLegacyCgiDriverConfig): Map<String, com.nethal.core.driver.tplink.TplinkHttpResponse> {
        // login() e a leitura de device info usam exatamente o mesmo bundle de blocos
        // (config.loginValidationSections()) — é o único bundle com prova real de sucesso, por
        // isso as duas chamadas produzem o mesmo request body.
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
    fun `readSnapshot succeeds on first attempt and parses all confirmed sections`() = runTest {
        val config = realProfileConfig()
        val transport = FakeTpLinkLegacyCgiHttpTransport(
            expectedAuthorizationCookie = basicCookie("admin", "secret"),
            responsesByRequestBody = responsesForSuccessfulSnapshot(config),
        )
        val driver = TpLinkLegacyCgiDriverFamily("192.168.0.1", config, transport, backoffMillis = { 0L })

        val result = driver.readSnapshot("admin", "secret")

        assertTrue(result is TpLinkLegacyCgiReadOutcome.Success)
        val snapshot = (result as TpLinkLegacyCgiReadOutcome.Success).snapshot
        assertEquals("Archer C20", snapshot.deviceInfo?.modelName)
        assertEquals(4, snapshot.deviceInfo?.numberOfVirtualPorts)
        assertEquals("ETH", snapshot.deviceInfo?.mode)
        assertEquals(2, snapshot.wifi.size)
        assertEquals("Casa-2.4G", snapshot.wifi[0].ssid)
        assertEquals(1, snapshot.connectedClients.size)
        assertEquals("AA:BB:CC:DD:EE:FF", snapshot.connectedClients.first().macAddress)
    }

    @Test
    fun `readSnapshot fails fast on invalid credentials without exhausting retries`() = runTest {
        val config = realProfileConfig()
        val transport = FakeTpLinkLegacyCgiHttpTransport(
            expectedAuthorizationCookie = basicCookie("admin", "correct-password"),
            responsesByRequestBody = responsesForSuccessfulSnapshot(config),
        )
        val driver = TpLinkLegacyCgiDriverFamily("192.168.0.1", config, transport, maxAttempts = 2, backoffMillis = { 0L })

        val result = driver.readSnapshot("admin", "wrong")

        assertTrue(result is TpLinkLegacyCgiReadOutcome.Failure)
        assertEquals(TpLinkLegacyCgiFailureReason.INVALID_CREDENTIALS, (result as TpLinkLegacyCgiReadOutcome.Failure).reason)
        assertEquals(1, transport.postCallCount) // sem retry para credencial invalida, so 1 chamada
    }

    @Test
    fun `readSnapshot respects conservative max attempts default of two`() = runTest {
        val transport = FakeTpLinkLegacyCgiHttpTransport(
            defaultResponse = com.nethal.core.driver.tplink.TplinkHttpResponse(500, "", emptyMap(), emptyMap()),
        )
        var backoffCalls = 0
        val driver = TpLinkLegacyCgiDriverFamily(
            "192.168.0.1",
            realProfileConfig(),
            transport,
            backoffMillis = { backoffCalls++; 0L },
        )

        val result = driver.readSnapshot("admin", "secret")

        assertTrue(result is TpLinkLegacyCgiReadOutcome.Failure)
        assertEquals(1, backoffCalls) // só 1 backoff entre as 2 tentativas (default maxAttempts=2)
    }

    @Test
    fun `readCapability on an unsupported capability id returns Unavailable, never throws`() = runTest {
        val driver = TpLinkLegacyCgiDriverFamily("192.168.0.1", realProfileConfig(), FakeTpLinkLegacyCgiHttpTransport())

        val result = driver.readCapability(com.nethal.core.model.CapabilityId.READ_WAN_STATUS)

        assertTrue(result is com.nethal.core.catalog.CapabilityReadResult.Unavailable)
    }

    @Test
    fun `readCapability without a prior authenticate() call always returns Unavailable`() = runTest {
        val transport = FakeTpLinkLegacyCgiHttpTransport()
        val driver = TpLinkLegacyCgiDriverFamily("192.168.0.1", realProfileConfig(), transport)

        val result = driver.readCapability(com.nethal.core.model.CapabilityId.READ_DEVICE_INFO)

        assertTrue(result is com.nethal.core.catalog.CapabilityReadResult.Unavailable)
        assertEquals(0, transport.postCallCount)
    }

    @Test
    fun `readCapability distinguishes unsupported capability from supported-but-sessionless in the reason`() = runTest {
        val transport = FakeTpLinkLegacyCgiHttpTransport()
        val driver = TpLinkLegacyCgiDriverFamily("192.168.0.1", realProfileConfig(), transport)

        val unsupported = driver.readCapability(com.nethal.core.model.CapabilityId.READ_WAN_STATUS)
            as com.nethal.core.catalog.CapabilityReadResult.Unavailable
        assertTrue(unsupported.reason.contains("não implementa leitura"))

        val supportedButSessionless = driver.readCapability(com.nethal.core.model.CapabilityId.READ_WIFI_STATUS)
            as com.nethal.core.catalog.CapabilityReadResult.Unavailable
        assertTrue(supportedButSessionless.reason.contains("authenticate"))
    }

    @Test
    fun `authenticate succeeds against a well-formed fake response and caches the session for readCapability`() = runTest {
        val config = realProfileConfig()
        val transport = FakeTpLinkLegacyCgiHttpTransport(
            expectedAuthorizationCookie = basicCookie("admin", "secret"),
            responsesByRequestBody = responsesForSuccessfulSnapshot(config),
        )
        val driver = TpLinkLegacyCgiDriverFamily("192.168.0.1", config, transport, backoffMillis = { 0L })

        val authResult = driver.authenticate("admin", "secret")

        assertTrue(authResult is com.nethal.core.catalog.DriverFamilyAuthResult.Success)
        assertEquals(1, transport.postCallCount) // authenticate() faz só a leitura de validação, sem ler wifi/clients ainda

        val deviceInfo = driver.readCapability(com.nethal.core.model.CapabilityId.READ_DEVICE_INFO)
        assertTrue(deviceInfo is com.nethal.core.catalog.CapabilityReadResult.Success)
        val deviceInfoPayload = (deviceInfo as com.nethal.core.catalog.CapabilityReadResult.Success).payload
            as com.nethal.core.model.CapabilityPayload.DeviceInfo
        assertEquals("TP-Link", deviceInfoPayload.info.vendor)
        assertEquals("Archer C20", deviceInfoPayload.info.model)

        val wifi = driver.readCapability(com.nethal.core.model.CapabilityId.READ_WIFI_STATUS)
        assertTrue(wifi is com.nethal.core.catalog.CapabilityReadResult.Success)
        val wifiPayload = (wifi as com.nethal.core.catalog.CapabilityReadResult.Success).payload as com.nethal.core.model.CapabilityPayload.Wifi
        assertEquals(2, wifiPayload.status.radios.size)
        assertEquals("Casa-2.4G", wifiPayload.status.radios[0].ssid)

        val clients = driver.readCapability(com.nethal.core.model.CapabilityId.READ_CONNECTED_CLIENTS)
        assertTrue(clients is com.nethal.core.catalog.CapabilityReadResult.Success)
        val clientsPayload = (clients as com.nethal.core.catalog.CapabilityReadResult.Success).payload
            as com.nethal.core.model.CapabilityPayload.ConnectedClients
        assertEquals(1, clientsPayload.clients.clients.size)
        assertEquals("AA:BB:CC:DD:EE:FF", clientsPayload.clients.clients.first().macAddress)

        // authenticate (1) + 3 leituras por-capability (1 cada) = 4, nenhum novo login.
        assertEquals(4, transport.postCallCount)
    }

    @Test
    fun `authenticate fails fast on invalid credentials and never caches a session`() = runTest {
        val config = realProfileConfig()
        val transport = FakeTpLinkLegacyCgiHttpTransport(
            expectedAuthorizationCookie = basicCookie("admin", "correct-password"),
            responsesByRequestBody = responsesForSuccessfulSnapshot(config),
        )
        val driver = TpLinkLegacyCgiDriverFamily("192.168.0.1", config, transport, maxAttempts = 2, backoffMillis = { 0L })

        val authResult = driver.authenticate("admin", "wrong")

        assertTrue(authResult is com.nethal.core.catalog.DriverFamilyAuthResult.InvalidCredentials)
        assertEquals(1, transport.postCallCount) // sem retry para credencial invalida

        val readAfterFailedAuth = driver.readCapability(com.nethal.core.model.CapabilityId.READ_DEVICE_INFO)
        assertTrue(readAfterFailedAuth is com.nethal.core.catalog.CapabilityReadResult.Unavailable)
    }

    @Test
    fun `readCapability surfaces SessionExpired when a cached session stops being accepted mid-flow`() = runTest {
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

        val result = driver.readCapability(com.nethal.core.model.CapabilityId.READ_WIFI_STATUS)

        assertTrue(result is com.nethal.core.catalog.CapabilityReadResult.SessionExpired)
    }

    @Test
    fun `SUPPORTED_CAPABILITIES covers only capabilities with real structured parsing`() {
        assertEquals(
            setOf(
                com.nethal.core.model.CapabilityId.READ_DEVICE_INFO,
                com.nethal.core.model.CapabilityId.READ_WIFI_STATUS,
                com.nethal.core.model.CapabilityId.READ_CONNECTED_CLIENTS,
            ),
            TpLinkLegacyCgiDriverFamily.SUPPORTED_CAPABILITIES,
        )
    }

    @Test
    fun `REBOOT_DEVICE is not implemented here - restricted to the C6 stok-luci driver by product decision (issues 95 e 103)`() = runTest {
        val config = realProfileConfig()
        val transport = FakeTpLinkLegacyCgiHttpTransport(
            expectedAuthorizationCookie = basicCookie("admin", "secret"),
            responsesByRequestBody = responsesForSuccessfulSnapshot(config),
            loginRequestBody = TpLinkLegacyCgiResponseParser.buildRequestBody(config.loginValidationSections()),
        )
        val driver = TpLinkLegacyCgiDriverFamily("192.168.0.1", config, transport, backoffMillis = { 0L })
        driver.authenticate("admin", "secret")

        val result = driver.executeAction(com.nethal.core.model.CapabilityId.REBOOT_DEVICE)

        assertTrue(result is com.nethal.core.catalog.CapabilityActionResult.Unavailable)
    }
}
