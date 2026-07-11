package com.nethal.core.driver.family.tplink.legacycgi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Testes do mecanismo real de autenticação da plataforma `tplink-legacy-cgi` (cookie
 * `Authorization: Basic <base64(user:pass)>`, sem endpoint de login dedicado), confirmado por
 * captura via DevTools contra unidade física do Luiz (2026-07-06, ver SIG-337/SIG-338).
 *
 * Movido de `driver/tplink/TplinkC20AuthenticationClientTest.kt` no passo 4 do plano de
 * refatoração HAL (`docs/architecture/hal-layering-model.md` §10) — mesma cobertura, adaptada só
 * para receber `loginValidationSections` explicitamente no construtor (antes uma constante
 * hardcoded `LOGIN_VALIDATION_SECTIONS`, agora vem de `profile.driverConfig` em produção).
 */
class TpLinkLegacyCgiAuthenticationClientTest {

    /** Mesmo bundle usado pelo profile real `tplink_archer_c20_v1` no catálogo (ver `catalog-2026.07.13.json`). */
    private val loginValidationSections: List<Pair<String, List<String>>> = listOf(
        "IGD_DEV_INFO" to listOf("modelName", "description", "X_TP_isFD"),
        "ETH_SWITCH" to listOf("numberOfVirtualPorts"),
        "SYS_MODE" to listOf("mode"),
        "/cgi/info" to emptyList(),
    )

    private fun basicCookie(user: String, pass: String): String =
        "Basic " + Base64.getEncoder().encodeToString("$user:$pass".toByteArray(Charsets.UTF_8))

    @Test
    fun `login succeeds when first read returns 200 and error code zero`() {
        val expectedCookie = basicCookie("admin", "secret")
        val transport = FakeTpLinkLegacyCgiHttpTransport(
            expectedAuthorizationCookie = expectedCookie,
            defaultResponse = deviceInfoOnlyResponse(),
        )
        val client = TpLinkLegacyCgiAuthenticationClient("192.168.0.1", transport, loginValidationSections)

        client.login("admin", "secret")

        assertTrue(client.isAuthenticated)
        assertEquals(1, transport.postCallCount)
    }

    @Test
    fun `login sends the Authorization cookie with base64(user colon pass), never the plaintext password in the request body`() {
        val transport = FakeTpLinkLegacyCgiHttpTransport(
            expectedAuthorizationCookie = basicCookie("admin", "S3nh4-Distintiva-C20"),
            defaultResponse = deviceInfoOnlyResponse(),
        )
        val client = TpLinkLegacyCgiAuthenticationClient("192.168.0.1", transport, loginValidationSections)
        val distinctivePassword = "S3nh4-Distintiva-C20"

        client.login("admin", distinctivePassword)

        val sentBody = transport.lastRequestBody.orEmpty()
        assertFalse("corpo da requisicao vazou a senha em claro", sentBody.contains(distinctivePassword))
        assertEquals(basicCookie("admin", distinctivePassword), transport.lastCookieHeaderSent)
    }

    @Test
    fun `login maps 401 status to INVALID_CREDENTIALS`() {
        val transport = FakeTpLinkLegacyCgiHttpTransport(
            expectedAuthorizationCookie = basicCookie("admin", "correct-password"),
            defaultResponse = deviceInfoOnlyResponse(),
        )
        val client = TpLinkLegacyCgiAuthenticationClient("192.168.0.1", transport, loginValidationSections)

        val exception = assertThrows(TpLinkLegacyCgiLoginException::class.java) {
            client.login("admin", "wrong-password")
        }
        assertEquals(TpLinkLegacyCgiLoginFailureReason.INVALID_CREDENTIALS, exception.reason)
    }

    @Test
    fun `login maps error code different from zero to INVALID_CREDENTIALS`() {
        val transport = FakeTpLinkLegacyCgiHttpTransport(defaultResponse = globalErrorResponse(code = 1))
        val client = TpLinkLegacyCgiAuthenticationClient("192.168.0.1", transport, loginValidationSections)

        val exception = assertThrows(TpLinkLegacyCgiLoginException::class.java) {
            client.login("admin", "secret")
        }
        assertEquals(TpLinkLegacyCgiLoginFailureReason.INVALID_CREDENTIALS, exception.reason)
    }

    @Test
    fun `login maps error code zero without modelName field to UNEXPECTED_RESPONSE, not INVALID_CREDENTIALS`() {
        val transport = FakeTpLinkLegacyCgiHttpTransport(defaultResponse = globalErrorResponse(code = 0))
        val client = TpLinkLegacyCgiAuthenticationClient("192.168.0.1", transport, loginValidationSections)

        val exception = assertThrows(TpLinkLegacyCgiLoginException::class.java) {
            client.login("admin", "secret")
        }
        assertEquals(TpLinkLegacyCgiLoginFailureReason.UNEXPECTED_RESPONSE, exception.reason)
    }

    @Test
    fun `login maps response without recognizable error marker to UNEXPECTED_RESPONSE`() {
        val transport = FakeTpLinkLegacyCgiHttpTransport(
            defaultResponse = com.nethal.core.driver.tplink.TplinkHttpResponse(200, "", emptyMap(), emptyMap()),
        )
        val client = TpLinkLegacyCgiAuthenticationClient("192.168.0.1", transport, loginValidationSections)

        val exception = assertThrows(TpLinkLegacyCgiLoginException::class.java) {
            client.login("admin", "secret")
        }
        assertEquals(TpLinkLegacyCgiLoginFailureReason.UNEXPECTED_RESPONSE, exception.reason)
    }

    @Test
    fun `no exception message ever contains the plaintext password`() {
        val distinctivePassword = "S3nh4-Muito-Distintiva-C20-Para-Achar-Em-Qualquer-Lugar"
        val transport = FakeTpLinkLegacyCgiHttpTransport(defaultResponse = globalErrorResponse(code = 1))
        val client = TpLinkLegacyCgiAuthenticationClient("192.168.0.1", transport, loginValidationSections)

        val exception = assertThrows(TpLinkLegacyCgiLoginException::class.java) {
            client.login("admin", distinctivePassword)
        }

        assertTrue(
            "vazou a senha na mensagem de excecao: ${exception.message}",
            exception.message?.contains(distinctivePassword) != true,
        )
        assertFalse("vazou a senha no toString() da excecao", exception.toString().contains(distinctivePassword))
    }

    @Test
    fun `no exception message ever contains the Authorization cookie value (base64 secret)`() {
        val distinctivePassword = "Outra-Senha-Bem-Distintiva"
        val transport = FakeTpLinkLegacyCgiHttpTransport(defaultResponse = globalErrorResponse(code = 1))
        val client = TpLinkLegacyCgiAuthenticationClient("192.168.0.1", transport, loginValidationSections)
        val cookieValue = basicCookie("admin", distinctivePassword)

        val exception = assertThrows(TpLinkLegacyCgiLoginException::class.java) {
            client.login("admin", distinctivePassword)
        }

        assertFalse("vazou o cookie Authorization na mensagem de excecao", exception.message.orEmpty().contains(cookieValue))
        assertFalse("vazou o cookie Authorization no toString() da excecao", exception.toString().contains(cookieValue))
    }

    @Test
    fun `fetchAuthenticated fails fast when called before a successful login`() {
        val transport = FakeTpLinkLegacyCgiHttpTransport()
        val client = TpLinkLegacyCgiAuthenticationClient("192.168.0.1", transport, loginValidationSections)

        assertThrows(IllegalStateException::class.java) {
            client.fetchAuthenticated(TpLinkLegacyCgiResponseParser.buildRequestBody(listOf("IGD_DEV_INFO" to listOf("modelName"))))
        }
    }
}
