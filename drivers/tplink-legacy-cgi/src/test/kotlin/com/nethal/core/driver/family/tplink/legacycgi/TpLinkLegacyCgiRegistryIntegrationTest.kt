package com.nethal.core.driver.family.tplink.legacycgi

import com.nethal.core.catalog.DefaultDriverRegistry
import com.nethal.core.catalog.DriverFamilyRegistry
import com.nethal.core.catalog.loadEmbeddedCatalogResource
import com.nethal.core.protocol.http.HttpTransport
import com.nethal.core.protocol.http.HttpTransportResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Prova de ponta a ponta do fluxo `CompatibilityProfile` (catálogo real embarcado) →
 * `DriverFamilyRegistry.resolve` → `TpLinkLegacyCgiDriverFamilyFactory` → instância funcional →
 * leitura real (via `readSnapshot`, transporte fake no lugar de hardware).
 *
 * Extraído do antigo `DriverFamilyRegistryIntegrationTest` (`:core`) na modularização da ADR 0002 —
 * a parte específica do Archer C20 (`tplink-legacy-cgi-driver`) vive agora com o próprio driver.
 */
class TpLinkLegacyCgiRegistryIntegrationTest {

    private fun basicCookie(user: String, pass: String): String =
        "Basic " + Base64.getEncoder().encodeToString("$user:$pass".toByteArray(Charsets.UTF_8))

    /** Fake mínimo de [HttpTransport] (não de `TplinkHttpTransport`) — prova que o adapter da factory realmente usa o transporte recebido via `DriverFamilyFactory.create`. */
    private class FakeHttpTransport(
        private val expectedAuthorizationCookie: String,
        private val responsesByRequestBody: Map<String, HttpTransportResponse>,
    ) : HttpTransport {
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
            if (cookies["Authorization"] != expectedAuthorizationCookie) {
                return HttpTransportResponse(401, "", emptyMap(), emptyMap())
            }
            return responsesByRequestBody[body] ?: HttpTransportResponse(200, "[error]0", emptyMap(), emptyMap())
        }
    }

    @Test
    fun `resolves tplink_archer_c20_v1 profile through DriverFamilyRegistry and reads a real snapshot end to end`() = runTest {
        val catalogRegistry = DefaultDriverRegistry(
            embeddedManifestLoader = { loadEmbeddedCatalogResource("catalog/catalog-2026.07.13.json") },
        )
        val profile = catalogRegistry.findProfile(vendor = "TP-Link", model = "Archer C20")
        requireNotNull(profile) { "profile tplink_archer_c20_v1 deveria existir no manifesto 2026.07.13" }
        assertEquals("tplink-legacy-cgi-driver", profile.driverFamilyId)

        val loginSections = listOf(
            "IGD_DEV_INFO" to listOf("modelName", "description", "X_TP_isFD"),
            "ETH_SWITCH" to listOf("numberOfVirtualPorts"),
            "SYS_MODE" to listOf("mode"),
            "/cgi/info" to emptyList(),
        )
        val wifiSections = listOf("LAN_WLAN" to listOf("name", "SSID"))
        val clientsSections = listOf(
            "LAN_HOST_ENTRY" to listOf("leaseTimeRemaining", "MACAddress", "hostName", "IPAddress"),
        )

        val deviceInfoBody = TpLinkLegacyCgiResponseParser.buildRequestBody(loginSections)
        val wifiBody = TpLinkLegacyCgiResponseParser.buildRequestBody(wifiSections)
        val clientsBody = TpLinkLegacyCgiResponseParser.buildRequestBody(clientsSections)

        val transport = FakeHttpTransport(
            expectedAuthorizationCookie = basicCookie("admin", "secret"),
            responsesByRequestBody = mapOf(
                deviceInfoBody to HttpTransportResponse(
                    statusCode = 200,
                    body = "[1,1,0,0,0,0]0\r\nmodelName=Archer C20\r\ndescription=Roteador\r\nX_TP_isFD=1\r\n" +
                        "[1,1,0,0,0,0]1\r\nnumberOfVirtualPorts=4\r\n[1,1,0,0,0,0]2\r\nmode=ETH\r\n[error]0",
                    headers = emptyMap(),
                    cookies = emptyMap(),
                ),
                wifiBody to HttpTransportResponse(
                    statusCode = 200,
                    body = "[1,1,0,0,0,0]0\r\nname=wlan0\r\nSSID=Casa-2.4G\r\n[error]0",
                    headers = emptyMap(),
                    cookies = emptyMap(),
                ),
                clientsBody to HttpTransportResponse(
                    statusCode = 200,
                    body = "[1,0,0,0,0,0]0\r\nleaseTimeRemaining=100\r\nMACAddress=AA:BB:CC:DD:EE:FF\r\n" +
                        "hostName=Notebook\r\nIPAddress=192.168.0.50\r\n[error]0",
                    headers = emptyMap(),
                    cookies = emptyMap(),
                ),
            ),
        )

        val driverFamilyRegistry = DriverFamilyRegistry(listOf(TpLinkLegacyCgiDriverFamilyFactory()))
        val driverFamily = driverFamilyRegistry.resolve(profile, "192.168.0.1", transport)

        assertTrue("factory deveria produzir uma TpLinkLegacyCgiDriverFamily", driverFamily is TpLinkLegacyCgiDriverFamily)

        val result = (driverFamily as TpLinkLegacyCgiDriverFamily).readSnapshot("admin", "secret")

        assertTrue(result is TpLinkLegacyCgiReadOutcome.Success)
        val snapshot = (result as TpLinkLegacyCgiReadOutcome.Success).snapshot
        assertEquals("Archer C20", snapshot.deviceInfo?.modelName)
        assertEquals(4, snapshot.deviceInfo?.numberOfVirtualPorts)
        assertEquals("ETH", snapshot.deviceInfo?.mode)
        assertEquals(1, snapshot.wifi.size)
        assertEquals("Casa-2.4G", snapshot.wifi.first().ssid)
        assertEquals(1, snapshot.connectedClients.size)
        assertEquals("AA:BB:CC:DD:EE:FF", snapshot.connectedClients.first().macAddress)
        assertTrue("transporte recebido pela factory deveria ter sido de fato usado", transport.postCallCount > 0)
    }
}
