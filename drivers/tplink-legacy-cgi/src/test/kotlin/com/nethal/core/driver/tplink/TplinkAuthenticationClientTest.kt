package com.nethal.core.driver.tplink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testes do handshake de login contra um transporte fake. `getParm` devolve um par RSA gerado em
 * tempo de teste (o handshake cifra `sign` com o módulo/expoente extraídos da resposta) para
 * exercitar o fluxo completo sem depender de hardware — não há unidade física disponível neste
 * ambiente.
 */
class TplinkAuthenticationClientTest {

    @Test
    fun `login succeeds and extracts session cookie`() {
        val transport = FakeTplinkHttpTransport(
            getParmResponse = sampleGetParmResponse(),
            loginResponses = mutableListOf(successfulTplinkLoginResponse(sessionId = "sess-001")),
        )
        val client = TplinkAuthenticationClient("192.168.0.1", transport)

        client.login("admin", "secret")

        assertTrue(client.isAuthenticated)
        assertEquals(2, transport.postCallCount) // getParm + cgi_gdpr
    }

    @Test
    fun `login works with AES-GCM cipher variant`() {
        val transport = FakeTplinkHttpTransport(
            getParmResponse = sampleGetParmResponse(),
            loginResponses = mutableListOf(successfulTplinkLoginResponse()),
        )
        val client = TplinkAuthenticationClient("192.168.0.1", transport, cipherVariant = TplinkCipherVariant.AES_GCM)

        client.login("admin", "secret")

        assertTrue(client.isAuthenticated)
    }

    @Test
    fun `login throws when RSA exponent is missing from getParm response`() {
        val transport = FakeTplinkHttpTransport(
            getParmResponse = TplinkHttpResponse(200, "var nn=\"abc\";", emptyMap(), emptyMap()),
            loginResponses = mutableListOf(),
        )
        val client = TplinkAuthenticationClient("192.168.0.1", transport)

        val exception = assertThrows(java.io.IOException::class.java) {
            client.login("admin", "secret")
        }
        assertTrue(exception.message!!.contains("ee") || exception.message!!.contains("expoente"))
    }

    @Test
    fun `login maps 401 status to INVALID_CREDENTIALS`() {
        val transport = FakeTplinkHttpTransport(
            getParmResponse = sampleGetParmResponse(),
            loginResponses = mutableListOf(invalidCredentialsTplinkResponse()),
        )
        val client = TplinkAuthenticationClient("192.168.0.1", transport)

        val exception = assertThrows(TplinkLoginException::class.java) {
            client.login("admin", "wrong-password")
        }
        assertEquals(TplinkLoginFailureReason.INVALID_CREDENTIALS, exception.reason)
    }

    @Test
    fun `login maps busy session body to SESSION_IN_USE`() {
        val transport = FakeTplinkHttpTransport(
            getParmResponse = sampleGetParmResponse(),
            loginResponses = mutableListOf(sessionInUseTplinkResponse()),
        )
        val client = TplinkAuthenticationClient("192.168.0.1", transport)

        val exception = assertThrows(TplinkLoginException::class.java) {
            client.login("admin", "secret")
        }
        assertEquals(TplinkLoginFailureReason.SESSION_IN_USE, exception.reason)
    }

    @Test
    fun `no exception message ever contains the plaintext password`() {
        val distinctivePassword = "S3nh4-Muito-Distintiva-Para-Achar-Em-Qualquer-Lugar"

        val cases = listOf(
            invalidCredentialsTplinkResponse() to "credencial invalida",
            sessionInUseTplinkResponse() to "sessao em uso",
        )

        cases.forEach { (response, label) ->
            val transport = FakeTplinkHttpTransport(
                getParmResponse = sampleGetParmResponse(),
                loginResponses = mutableListOf(response),
            )
            val client = TplinkAuthenticationClient("192.168.0.1", transport)

            val exception = assertThrows(TplinkLoginException::class.java) {
                client.login("admin", distinctivePassword)
            }

            assertTrue(
                "vazou a senha na mensagem de excecao ($label): ${exception.message}",
                exception.message?.contains(distinctivePassword) != true,
            )
            assertTrue(
                "vazou a senha no toString() da excecao ($label)",
                !exception.toString().contains(distinctivePassword),
            )
        }
    }

    @Test
    fun `fetchAuthenticated fails fast when called before a successful login`() {
        val transport = FakeTplinkHttpTransport(
            getParmResponse = sampleGetParmResponse(),
            loginResponses = mutableListOf(),
        )
        val client = TplinkAuthenticationClient("192.168.0.1", transport)

        assertThrows(IllegalStateException::class.java) {
            client.fetchAuthenticated("/cgi/getDeviceInfo")
        }
    }
}
