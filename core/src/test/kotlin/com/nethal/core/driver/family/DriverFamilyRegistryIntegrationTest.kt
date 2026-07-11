package com.nethal.core.driver.family

import com.nethal.core.catalog.CapabilityReadResult
import com.nethal.core.catalog.DefaultDriverRegistry
import com.nethal.core.catalog.DriverFamilyAuthResult
import com.nethal.core.catalog.DriverFamilyRegistry
import com.nethal.core.catalog.loadEmbeddedCatalogResource
import com.nethal.core.driver.family.nokia.gpon.NokiaGponDriverFamily
import com.nethal.core.driver.family.tplink.legacycgi.TpLinkLegacyCgiDriverFamily
import com.nethal.core.driver.family.tplink.legacycgi.TpLinkLegacyCgiDriverFamilyFactory
import com.nethal.core.driver.family.tplink.legacycgi.TpLinkLegacyCgiReadOutcome
import com.nethal.core.driver.family.tplink.legacycgi.TpLinkLegacyCgiResponseParser
import com.nethal.core.model.CapabilityId
import com.nethal.core.model.CapabilityPayload
import com.nethal.core.protocol.http.HttpTransport
import com.nethal.core.protocol.http.HttpTransportResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.util.Base64

/**
 * Prova de ponta a ponta do fluxo descrito em `docs/architecture/hal-layering-model.md` §8, passo
 * 4: `CompatibilityProfile` (catálogo real embarcado) → `DriverFamilyRegistry.resolve` →
 * `DriverFamilyFactory` registrada → instância de `DriverFamily` funcional → leitura real (via
 * `readSnapshot`, usando um transporte fake no lugar de hardware).
 *
 * Este é o primeiro teste do NetHAL a fechar essa cadeia inteira para uma Driver Family real — o
 * próprio objetivo do passo 4 do plano de refatoração ("provar que a arquitetura funciona antes de
 * qualquer expansão").
 */
class DriverFamilyRegistryIntegrationTest {

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
        assertEquals("AA:BB:CC:**:**:**", snapshot.connectedClients.first().macAddressMasked)
        assertTrue("transporte recebido pela factory deveria ter sido de fato usado", transport.postCallCount > 0)
    }

    @Test
    fun `defaultDriverFamilyRegistry already has the tplink-legacy-cgi-driver factory registered`() {
        val registry = defaultDriverFamilyRegistry()
        val catalogRegistry = DefaultDriverRegistry(
            embeddedManifestLoader = { loadEmbeddedCatalogResource("catalog/catalog-2026.07.13.json") },
        )
        val profile = requireNotNull(catalogRegistry.findProfile(vendor = "TP-Link", model = "Archer C20"))

        val driverFamily = registry.resolve(
            profile,
            "192.168.0.1",
            object : HttpTransport {
                override fun get(url: String, extraHeaders: Map<String, String>) = HttpTransportResponse(404, "", emptyMap(), emptyMap())
                override fun post(
                    url: String,
                    body: String,
                    cookies: Map<String, String>,
                    extraHeaders: Map<String, String>,
                ) = HttpTransportResponse(200, "[error]0", emptyMap(), emptyMap())
            },
        )

        assertTrue(driverFamily is TpLinkLegacyCgiDriverFamily)
    }

    /** Fake mínimo de [HttpTransport] que reproduz o handshake RSA+AES do Nokia (GET página de login, POST /login.cgi, GET autenticado). */
    private class FakeNokiaAdapterTransport(
        private val loginPageBody: String,
        private val loginResponses: MutableList<HttpTransportResponse>,
        private val authenticatedPages: Map<String, String>,
    ) : HttpTransport {
        var postCallCount = 0
            private set

        override fun get(url: String, extraHeaders: Map<String, String>): HttpTransportResponse {
            for ((path, body) in authenticatedPages) {
                if (url.endsWith(path)) return HttpTransportResponse(200, body, emptyMap(), emptyMap())
            }
            return HttpTransportResponse(200, loginPageBody, emptyMap(), emptyMap())
        }

        override fun post(
            url: String,
            body: String,
            cookies: Map<String, String>,
            extraHeaders: Map<String, String>,
        ): HttpTransportResponse {
            postCallCount++
            check(loginResponses.isNotEmpty()) { "FakeNokiaAdapterTransport: nenhuma resposta de login configurada" }
            return loginResponses.removeAt(0)
        }
    }

    /**
     * Fecha o descompasso catálogo↔registro apontado na auditoria original (issue #18): antes desta
     * migração, `DriverFamilyRegistry.resolve()` para `nokia_g1425gb_v1` lançava
     * `UnknownDriverFamilyException` porque nenhuma factory estava registrada sob a chave
     * `nokia-ont-gpon-driver`. Prova a cadeia inteira: catálogo real embarcado →
     * `DriverFamilyRegistry.resolve` → `NokiaGponDriverFamilyFactory` → instância funcional →
     * `authenticate` + `readCapability` reais (transporte fake, sem hardware).
     */
    @Test
    fun `resolves nokia_g1425gb_v1 profile through DriverFamilyRegistry and reads a real capability end to end`() = runTest {
        val catalogRegistry = DefaultDriverRegistry(embeddedManifestLoader = ::loadEmbeddedCatalogResource)
        val profile = catalogRegistry.findProfile(vendor = "Nokia", model = "G-1425G-B")
        requireNotNull(profile) { "profile nokia_g1425gb_v1 deveria existir no manifesto embarcado" }
        assertEquals("nokia-ont-gpon-driver", profile.driverFamilyId)

        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
        val pubkeyBase64 = Base64.getEncoder().encodeToString(keyPair.public.encoded)
        val loginPage = """
            var pubkey = '-----BEGIN PUBLIC KEY-----$pubkeyBase64-----END PUBLIC KEY-----';
            var nonce = "test-nonce";
            var token = "test-csrf-token";
        """.trimIndent()

        val transport = FakeNokiaAdapterTransport(
            loginPageBody = loginPage,
            loginResponses = mutableListOf(
                HttpTransportResponse(
                    statusCode = 299,
                    body = "",
                    headers = mapOf("x-sid" to "sess-e2e"),
                    cookies = mapOf("sid" to "sess-e2e", "lsid" to "legacy-sess-e2e", "lang" to "eng"),
                ),
            ),
            authenticatedPages = mapOf(
                "/device_status.cgi" to
                    """{"ModelName":"G-1425G-B","Manufacturer":"Nokia","SerialNumber":"ALCLXXXXXXXX","SoftwareVersion":"v1","HardwareVersion":"1.0","UpTime":100}""",
            ),
        )

        val registry = defaultDriverFamilyRegistry()
        val driverFamily = registry.resolve(profile, "192.168.1.254", transport)

        assertTrue("factory deveria produzir uma NokiaGponDriverFamily", driverFamily is NokiaGponDriverFamily)

        val authResult = (driverFamily as NokiaGponDriverFamily).authenticate("admin", "secret")
        assertTrue(authResult is DriverFamilyAuthResult.Success)

        val result = driverFamily.readCapability(CapabilityId.READ_DEVICE_INFO)
        assertTrue(result is CapabilityReadResult.Success)
        val payload = (result as CapabilityReadResult.Success).payload as CapabilityPayload.DeviceInfo
        assertEquals("G-1425G-B", payload.info.model)
        assertTrue("transporte recebido pela factory deveria ter sido de fato usado", transport.postCallCount > 0)
    }
}
