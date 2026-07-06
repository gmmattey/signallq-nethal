package com.nethal.core.driver.nokia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.util.Base64

/**
 * Testes do handshake de login contra um transporte fake. Gera um par de chaves RSA real em
 * tempo de teste (o handshake cifra `ck` com a pubkey extraída do HTML) para exercitar o fluxo
 * completo sem depender de hardware — não há unidade física disponível neste ambiente.
 */
class NokiaAuthenticationClientTest {

    private fun loginPageWithGeneratedKey(): String {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
        val pubkeyBase64 = Base64.getEncoder().encodeToString(keyPair.public.encoded)
        return """
            var pubkey = '-----BEGIN PUBLIC KEY-----$pubkeyBase64-----END PUBLIC KEY-----';
            var nonce = "test-nonce";
            var token = "test-csrf-token";
        """.trimIndent()
    }

    @Test
    fun `login succeeds and extracts session id from x-sid header`() {
        val transport = FakeNokiaHttpTransport(
            loginPageBody = loginPageWithGeneratedKey(),
            loginResponses = mutableListOf(successfulLoginResponse(sid = "sess-001")),
        )
        val client = NokiaAuthenticationClient("192.168.1.1", transport)

        client.login("admin", "secret")

        assertTrue(client.isAuthenticated)
        assertEquals(1, transport.postCallCount)
    }

    @Test
    fun `login throws IOException when pubkey is missing from login page`() {
        val transport = FakeNokiaHttpTransport(
            loginPageBody = "<html>sem pubkey aqui</html>",
            loginResponses = mutableListOf(),
        )
        val client = NokiaAuthenticationClient("192.168.1.1", transport)

        val exception = assertThrows(java.io.IOException::class.java) {
            client.login("admin", "secret")
        }
        assertTrue(exception.message!!.contains("pubkey"))
    }

    @Test
    fun `login maps err_t=0 to SESSION_IN_USE`() {
        val transport = FakeNokiaHttpTransport(
            loginPageBody = loginPageWithGeneratedKey(),
            loginResponses = mutableListOf(errorLoginResponse(errT = 0)),
        )
        val client = NokiaAuthenticationClient("192.168.1.1", transport)

        val exception = assertThrows(NokiaLoginException::class.java) {
            client.login("admin", "secret")
        }
        assertEquals(NokiaLoginFailureReason.SESSION_IN_USE, exception.reason)
    }

    @Test
    fun `login maps err_t=1 to INVALID_CREDENTIALS`() {
        val transport = FakeNokiaHttpTransport(
            loginPageBody = loginPageWithGeneratedKey(),
            loginResponses = mutableListOf(errorLoginResponse(errT = 1)),
        )
        val client = NokiaAuthenticationClient("192.168.1.1", transport)

        val exception = assertThrows(NokiaLoginException::class.java) {
            client.login("admin", "wrong-password")
        }
        assertEquals(NokiaLoginFailureReason.INVALID_CREDENTIALS, exception.reason)
    }

    @Test
    fun `login maps err_t=2 to TOKEN_EXPIRED`() {
        val transport = FakeNokiaHttpTransport(
            loginPageBody = loginPageWithGeneratedKey(),
            loginResponses = mutableListOf(errorLoginResponse(errT = 2)),
        )
        val client = NokiaAuthenticationClient("192.168.1.1", transport)

        val exception = assertThrows(NokiaLoginException::class.java) {
            client.login("admin", "secret")
        }
        assertEquals(NokiaLoginFailureReason.TOKEN_EXPIRED, exception.reason)
    }

    @Test
    fun `login maps unknown err_t values to UNKNOWN reason instead of throwing unrelated error`() {
        val transport = FakeNokiaHttpTransport(
            loginPageBody = loginPageWithGeneratedKey(),
            loginResponses = mutableListOf(errorLoginResponse(errT = 99)),
        )
        val client = NokiaAuthenticationClient("192.168.1.1", transport)

        val exception = assertThrows(NokiaLoginException::class.java) {
            client.login("admin", "secret")
        }
        assertEquals(NokiaLoginFailureReason.UNKNOWN, exception.reason)
    }

    @Test
    fun `no exception message ever contains the plaintext password - travado contra regressao`() {
        val distinctivePassword = "S3nh4-Muito-Distintiva-Para-Achar-Em-Qualquer-Lugar"

        val cases = listOf(
            errorLoginResponse(errT = 0) to "sessao em uso",
            errorLoginResponse(errT = 1) to "credencial invalida",
            errorLoginResponse(errT = 2) to "token expirado",
            errorLoginResponse(errT = 99) to "err_t desconhecido",
        )

        cases.forEach { (response, label) ->
            val transport = FakeNokiaHttpTransport(
                loginPageBody = loginPageWithGeneratedKey(),
                loginResponses = mutableListOf(response),
            )
            val client = NokiaAuthenticationClient("192.168.1.1", transport)

            val exception = assertThrows(NokiaLoginException::class.java) {
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
        val transport = FakeNokiaHttpTransport(loginPageBody = "", loginResponses = mutableListOf())
        val client = NokiaAuthenticationClient("192.168.1.1", transport)

        assertThrows(IllegalStateException::class.java) {
            client.fetchAuthenticated("/device_status.cgi")
        }
    }
}
