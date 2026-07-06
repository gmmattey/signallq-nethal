package com.nethal.core.driver.tplink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testes do mecanismo de login especulativo assumido para o Archer C20 (POST simples com hash
 * MD5 de senha, sem RSA/AES) — cobre parsing, sucesso/falha e não-vazamento de credencial. Não
 * valida contra hardware real: o mecanismo em si ainda não foi confirmado (ver
 * `TplinkC20AuthenticationClient` e o profile `tplink_archer_c20_v1` no catálogo).
 */
class TplinkC20AuthenticationClientTest {

    @Test
    fun `login succeeds and extracts session cookie`() {
        val transport = FakeTplinkC20HttpTransport(
            loginResponses = mutableListOf(successfulTplinkC20LoginResponse(sessionId = "c20-001")),
        )
        val client = TplinkC20AuthenticationClient("192.168.0.1", transport)

        client.login("admin", "secret")

        assertTrue(client.isAuthenticated)
        assertEquals(1, transport.postCallCount)
    }

    @Test
    fun `login sends md5 hash of password, never the plaintext password`() {
        val transport = FakeTplinkC20HttpTransport(
            loginResponses = mutableListOf(successfulTplinkC20LoginResponse()),
        )
        val client = TplinkC20AuthenticationClient("192.168.0.1", transport)
        val distinctivePassword = "S3nh4-Distintiva-C20"

        client.login("admin", distinctivePassword)

        val sentBody = transport.lastLoginRequestBody.orEmpty()
        assertFalse("corpo da requisicao de login vazou a senha em claro", sentBody.contains(distinctivePassword))
        assertTrue("corpo deveria conter o hash MD5 da senha", sentBody.contains(TplinkC20AuthCrypto.md5Hex(distinctivePassword)))
    }

    @Test
    fun `login maps 401 status to INVALID_CREDENTIALS`() {
        val transport = FakeTplinkC20HttpTransport(
            loginResponses = mutableListOf(invalidCredentialsTplinkC20Response()),
        )
        val client = TplinkC20AuthenticationClient("192.168.0.1", transport)

        val exception = assertThrows(TplinkC20LoginException::class.java) {
            client.login("admin", "wrong-password")
        }
        assertEquals(TplinkC20LoginFailureReason.INVALID_CREDENTIALS, exception.reason)
    }

    @Test
    fun `login maps blank body to UNEXPECTED_RESPONSE`() {
        val transport = FakeTplinkC20HttpTransport(
            loginResponses = mutableListOf(unexpectedEmptyTplinkC20Response()),
        )
        val client = TplinkC20AuthenticationClient("192.168.0.1", transport)

        val exception = assertThrows(TplinkC20LoginException::class.java) {
            client.login("admin", "secret")
        }
        assertEquals(TplinkC20LoginFailureReason.UNEXPECTED_RESPONSE, exception.reason)
    }

    @Test
    fun `no exception message ever contains the plaintext password`() {
        val distinctivePassword = "S3nh4-Muito-Distintiva-C20-Para-Achar-Em-Qualquer-Lugar"

        val transport = FakeTplinkC20HttpTransport(
            loginResponses = mutableListOf(invalidCredentialsTplinkC20Response()),
        )
        val client = TplinkC20AuthenticationClient("192.168.0.1", transport)

        val exception = assertThrows(TplinkC20LoginException::class.java) {
            client.login("admin", distinctivePassword)
        }

        assertTrue(
            "vazou a senha na mensagem de excecao: ${exception.message}",
            exception.message?.contains(distinctivePassword) != true,
        )
        assertFalse("vazou a senha no toString() da excecao", exception.toString().contains(distinctivePassword))
    }

    @Test
    fun `fetchAuthenticated fails fast when called before a successful login`() {
        val transport = FakeTplinkC20HttpTransport(loginResponses = mutableListOf())
        val client = TplinkC20AuthenticationClient("192.168.0.1", transport)

        assertThrows(IllegalStateException::class.java) {
            client.fetchAuthenticated("/cgi/getDeviceInfo")
        }
    }
}
