package com.nethal.core.driver.family.nokia.gpon

import com.nethal.core.catalog.CapabilityReadResult
import com.nethal.core.catalog.DefaultDriverRegistry
import com.nethal.core.catalog.DriverFamilyAuthResult
import com.nethal.core.catalog.DriverFamilyRegistry
import com.nethal.core.catalog.loadEmbeddedCatalogResource
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
 * Prova de ponta a ponta: catálogo real embarcado → `DriverFamilyRegistry.resolve` →
 * `NokiaGponDriverFamilyFactory` → instância funcional → `authenticate` + `readCapability` reais
 * (transporte fake, sem hardware).
 *
 * Extraído do antigo `DriverFamilyRegistryIntegrationTest` (`:core`) na modularização da ADR 0002 —
 * a parte específica do Nokia G-1425G-B vive agora com o próprio driver. Onde antes usava
 * `defaultDriverFamilyRegistry()` (composition root, hoje em `:app`), monta um `DriverFamilyRegistry`
 * local só com a factory deste módulo; a verificação de que o agregador real registra esta família
 * é feita por `DriverFamilyCatalogIntegrityTest`/`DefaultDriverFamilyRegistryTest` em `:app`.
 */
class NokiaGponRegistryIntegrationTest {

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

        val registry = DriverFamilyRegistry(listOf(NokiaGponDriverFamilyFactory()))
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
