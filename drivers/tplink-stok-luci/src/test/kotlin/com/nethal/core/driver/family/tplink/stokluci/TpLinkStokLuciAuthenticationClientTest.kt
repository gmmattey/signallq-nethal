package com.nethal.core.driver.family.tplink.stokluci

import com.nethal.core.protocol.http.HttpTransportResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testes do mecanismo de autenticação da plataforma `tplink-stok-luci`, corrigido a partir de
 * evidência ao vivo **definitiva** (terceira rodada, 2026-07-07): captura via Playwright, com
 * `page.on('response')` interceptando o corpo completo de request E response de cada chamada
 * `cgi-bin/luci` durante um login real bem-sucedido contra o hardware físico do Luiz (Archer C6
 * v2.0, firmware `1.1.10 Build 20230830 rel.69433(5553)`). Ver KDoc de
 * [TpLinkStokLuciAuthenticationClient] para o detalhe completo do protocolo e o que ainda não está
 * confirmado byte a byte.
 */
class TpLinkStokLuciAuthenticationClientTest {

    @Test
    fun `login succeeds and extracts stok via full round-trip through a simulated real server`() {
        val transport = FakeTpLinkStokLuciHttpTransport(
            simulateRealServerStok = "mytoken123",
        )
        val client = TpLinkStokLuciAuthenticationClient("192.168.0.1", transport)

        val session = client.login("admin", "secret")

        assertTrue(client.isAuthenticated)
        assertEquals("mytoken123", session.stok)
    }

    @Test
    fun `login extracts stok from the real captured success shape success plus data plus stok`() {
        val transport = FakeTpLinkStokLuciHttpTransport(
            keysResponse = passwordKeySuccessResponse(),
            authResponse = authSuccessResponse(),
            simulateRealServerEncryptedLoginPayload = """{"success":true,"data":{"stok":"nested-token-123"}}""",
        )
        val client = TpLinkStokLuciAuthenticationClient("192.168.0.1", transport)

        val session = client.login("admin", "admin")

        assertEquals("nested-token-123", session.stok)
    }

    @Test
    fun `login generates a 16-decimal-digit AES key and IV used directly as bytes, never random binary hex - EncryptionWrapperMR variant`() {
        // O fake decifra o envelope sign com a chave RSA privada de teste de assinatura e expoe os
        // digitos k=/i= extraidos - se o roundtrip completo (que inclui o servidor fake decifrando a
        // resposta com essa mesma chave/IV) funciona, a chave/IV realmente geradas pelo client sao
        // strings decimais de 16 digitos usadas como bytes ASCII diretos, nao bytes aleatorios
        // hex-encodados (formato antigo, incorreto).
        val transport = FakeTpLinkStokLuciHttpTransport(
            simulateRealServerStok = "mytoken123",
        )
        val client = TpLinkStokLuciAuthenticationClient("192.168.0.1", transport)

        val session = client.login("admin", "secret")

        assertEquals("mytoken123", session.stok)
        assertNotNull(transport.lastCapturedAesKeyDigits)
        assertNotNull(transport.lastCapturedAesIvDigits)
        assertTrue(
            "chave AES capturada do envelope sign deve ser 16 digitos decimais, nao hex de bytes aleatorios",
            transport.lastCapturedAesKeyDigits!!.matches(Regex("^[0-9]{16}$")),
        )
        assertTrue(
            "IV AES capturado do envelope sign deve ser 16 digitos decimais, nao hex de bytes aleatorios",
            transport.lastCapturedAesIvDigits!!.matches(Regex("^[0-9]{16}$")),
        )
    }

    @Test
    fun `login uses md5 of username plus password in h of sign envelope`() {
        val transport = FakeTpLinkStokLuciHttpTransport(
            simulateRealServerStok = "mytoken123",
        )
        val client = TpLinkStokLuciAuthenticationClient("192.168.0.1", transport)

        client.login("admin", "admin")

        assertEquals("f6fdffe48c908deb0f4c3bd36c032e72", transport.lastCapturedSignHash)
    }

    @Test
    fun `login calls keys, auth and login endpoints in this exact order - two separate preparation calls`() {
        val transport = FakeTpLinkStokLuciHttpTransport(
            simulateRealServerStok = "tok",
        )
        val client = TpLinkStokLuciAuthenticationClient("192.168.0.1", transport)

        client.login("admin", "secret")

        assertEquals(3, transport.postCallCount)
        assertTrue(transport.postedUrls[0].contains("form=keys"))
        assertTrue(transport.postedUrls[1].contains("form=auth"))
        assertTrue(transport.postedUrls[2].contains("form=login"))
    }

    @Test
    fun `login body is a sign+data envelope, never operation=login&password=`() {
        val transport = FakeTpLinkStokLuciHttpTransport(
            simulateRealServerStok = "tok",
        )
        val client = TpLinkStokLuciAuthenticationClient("192.168.0.1", transport)
        val distinctivePassword = "S3nh4-Distintiva-StokLuci"

        client.login("admin", distinctivePassword)

        val sentBody = transport.lastLoginBody.orEmpty()
        assertFalse("corpo do login vazou a senha em claro", sentBody.contains(distinctivePassword))
        assertTrue("corpo do login deve seguir o envelope sign/data real", sentBody.startsWith("sign="))
        assertTrue(sentBody.contains("&data="))
        assertFalse("corpo do login nao deve usar o formato antigo operation=login&password=", sentBody.contains("operation=login&password="))
    }

    @Test
    fun `login populates passwordKey from form=keys and authKeys from form=auth - two distinct RSA keys`() {
        val transport = FakeTpLinkStokLuciHttpTransport(
            authResponse = authSuccessResponse(seq = 12345),
            simulateRealServerStok = "tok",
        )
        val client = TpLinkStokLuciAuthenticationClient("192.168.0.1", transport)

        client.login("admin", "secret")

        assertEquals(TestRsaKeyFixture.MODULUS_HEX, client.passwordKey?.key?.modulusHex)
        assertEquals(12345L, client.authKeys?.seq)
        assertEquals(TestSignKeyFixture.MODULUS_HEX, client.authKeys?.key?.modulusHex)
        assertFalse(
            "chave de senha (form=keys) e chave de assinatura (form=auth) devem ser distintas",
            client.passwordKey?.key?.modulusHex == client.authKeys?.key?.modulusHex,
        )
    }

    @Test
    fun `login fails fast when keys endpoint is unavailable`() {
        val transport = FakeTpLinkStokLuciHttpTransport(keysResponse = null, simulateRealServerStok = null)
        val client = TpLinkStokLuciAuthenticationClient("192.168.0.1", transport)

        val exception = assertThrows(TpLinkStokLuciLoginException::class.java) {
            client.login("admin", "secret")
        }
        assertEquals(TpLinkStokLuciLoginFailureReason.AUTH_ENDPOINT_UNAVAILABLE, exception.reason)
        assertEquals(1, transport.postCallCount)
    }

    @Test
    fun `login fails fast when auth endpoint is unavailable after keys succeeds`() {
        val transport = FakeTpLinkStokLuciHttpTransport(
            keysResponse = passwordKeySuccessResponse(),
            authResponse = null,
        )
        val client = TpLinkStokLuciAuthenticationClient("192.168.0.1", transport)

        val exception = assertThrows(TpLinkStokLuciLoginException::class.java) {
            client.login("admin", "secret")
        }
        assertEquals(TpLinkStokLuciLoginFailureReason.AUTH_ENDPOINT_UNAVAILABLE, exception.reason)
        assertEquals(2, transport.postCallCount)
    }

    @Test
    fun `login maps response without data field to INVALID_CREDENTIALS`() {
        val transport = FakeTpLinkStokLuciHttpTransport(
            keysResponse = passwordKeySuccessResponse(),
            authResponse = authSuccessResponse(),
            loginResponse = loginFailureResponse(),
        )
        val client = TpLinkStokLuciAuthenticationClient("192.168.0.1", transport)

        val exception = assertThrows(TpLinkStokLuciLoginException::class.java) {
            client.login("admin", "wrong-password")
        }
        assertEquals(TpLinkStokLuciLoginFailureReason.INVALID_CREDENTIALS, exception.reason)
    }

    @Test
    fun `login maps decrypted firmware login failed envelope to INVALID_CREDENTIALS with counters`() {
        val transport = FakeTpLinkStokLuciHttpTransport(
            keysResponse = passwordKeySuccessResponse(),
            authResponse = authSuccessResponse(),
            simulateRealServerEncryptedLoginPayload = """{"errorcode":"login failed","success":false,"data":{"failureCount":1,"attemptsAllowed":9}}""",
        )
        val client = TpLinkStokLuciAuthenticationClient("192.168.0.1", transport)

        val exception = assertThrows(TpLinkStokLuciLoginException::class.java) {
            client.login("admin", "admin")
        }
        assertEquals(TpLinkStokLuciLoginFailureReason.INVALID_CREDENTIALS, exception.reason)
        assertTrue(exception.message!!.contains("errorcode=login failed"))
        assertTrue(exception.message!!.contains("failureCount=1"))
        assertTrue(exception.message!!.contains("attemptsAllowed=9"))
    }

    @Test
    fun `login maps HTTP 403 on the login endpoint to INVALID_CREDENTIALS - matches the real failure observed against hardware`() {
        val transport = FakeTpLinkStokLuciHttpTransport(
            keysResponse = passwordKeySuccessResponse(),
            authResponse = authSuccessResponse(),
            loginResponse = HttpTransportResponse(403, "", emptyMap(), emptyMap()),
        )
        val client = TpLinkStokLuciAuthenticationClient("192.168.0.1", transport)

        val exception = assertThrows(TpLinkStokLuciLoginException::class.java) {
            client.login("admin", "wrong-password")
        }
        assertEquals(TpLinkStokLuciLoginFailureReason.INVALID_CREDENTIALS, exception.reason)
    }

    @Test
    fun `login maps HTTP 401 on the login endpoint to INVALID_CREDENTIALS`() {
        val transport = FakeTpLinkStokLuciHttpTransport(
            keysResponse = passwordKeySuccessResponse(),
            authResponse = authSuccessResponse(),
            loginResponse = HttpTransportResponse(401, "", emptyMap(), emptyMap()),
        )
        val client = TpLinkStokLuciAuthenticationClient("192.168.0.1", transport)

        val exception = assertThrows(TpLinkStokLuciLoginException::class.java) {
            client.login("admin", "wrong-password")
        }
        assertEquals(TpLinkStokLuciLoginFailureReason.INVALID_CREDENTIALS, exception.reason)
    }

    @Test
    fun `login maps unexpected HTTP status on login endpoint to UNEXPECTED_RESPONSE`() {
        val transport = FakeTpLinkStokLuciHttpTransport(
            keysResponse = passwordKeySuccessResponse(),
            authResponse = authSuccessResponse(),
            loginResponse = HttpTransportResponse(500, "", emptyMap(), emptyMap()),
        )
        val client = TpLinkStokLuciAuthenticationClient("192.168.0.1", transport)

        val exception = assertThrows(TpLinkStokLuciLoginException::class.java) {
            client.login("admin", "secret")
        }
        assertEquals(TpLinkStokLuciLoginFailureReason.UNEXPECTED_RESPONSE, exception.reason)
    }

    @Test
    fun `no exception message ever contains the plaintext password`() {
        val distinctivePassword = "S3nh4-Muito-Distintiva-StokLuci-Para-Achar-Em-Qualquer-Lugar"
        val transport = FakeTpLinkStokLuciHttpTransport(keysResponse = null)
        val client = TpLinkStokLuciAuthenticationClient("192.168.0.1", transport)

        val exception = assertThrows(TpLinkStokLuciLoginException::class.java) {
            client.login("admin", distinctivePassword)
        }

        assertFalse(
            "vazou a senha na mensagem de excecao: ${exception.message}",
            exception.message?.contains(distinctivePassword) == true,
        )
        assertFalse("vazou a senha no toString() da excecao", exception.toString().contains(distinctivePassword))
    }

    @Test
    fun `fetchAuthenticated fails fast when called before a successful login`() {
        val transport = FakeTpLinkStokLuciHttpTransport()
        val client = TpLinkStokLuciAuthenticationClient("192.168.0.1", transport)

        assertThrows(IllegalStateException::class.java) {
            client.fetchAuthenticated("admin/status", "form=all&operation=read")
        }
    }

    @Test
    fun `fetchAuthenticated uses the stok in the path`() {
        val transport = FakeTpLinkStokLuciHttpTransport(
            authResponse = authSuccessResponse(),
            simulateRealServerStok = "tok999",
        )
        val client = TpLinkStokLuciAuthenticationClient("192.168.0.1", transport)
        client.login("admin", "secret")

        client.fetchAuthenticated("admin/status", "form=all&operation=read")

        val statusUrl = transport.postedUrls.last()
        assertTrue(statusUrl.contains(";stok=tok999/admin/status"))
        assertTrue(statusUrl.contains("form=all"))
    }

    @Test
    fun `fetchAuthenticated reuses the authenticated sign plus data envelope and returns decrypted plaintext`() {
        val transport = FakeTpLinkStokLuciHttpTransport(
            simulateRealServerStok = "tok999",
            statusResponse = HttpTransportResponse(200, """{"success":true,"data":{"status":"ok"}}""", emptyMap(), emptyMap()),
        )
        val client = TpLinkStokLuciAuthenticationClient("192.168.0.1", transport)
        client.login("admin", "admin")

        val plaintext = client.fetchAuthenticated("admin/status", "form=all&operation=read")

        assertEquals("""{"success":true,"data":{"status":"ok"}}""", plaintext)
        assertTrue(transport.lastAuthenticatedRequestBody!!.startsWith("sign="))
        assertTrue(transport.lastAuthenticatedRequestBody!!.contains("&data="))
    }

    // --- Regressão issue #125: seq do envelope sign precisa avancar, nunca ser reusado como valor
    // fixo (login sempre bem-sucedido, TODA leitura autenticada seguinte falhando com 403, mesmo a
    // primeira, mesmo em sessao nova - ver KDoc de SessionEncryptorContext em
    // TpLinkStokLuciAuthenticationClient) ---

    @Test
    fun `first authenticated read already accounts for the seq advance consumed by login itself - issue 125`() {
        val transport = FakeTpLinkStokLuciHttpTransport(
            authResponse = authSuccessResponse(seq = 12345),
            simulateRealServerStok = "tok999",
            statusResponse = HttpTransportResponse(200, """{"success":true,"data":{"status":"ok"}}""", emptyMap(), emptyMap()),
        )
        val client = TpLinkStokLuciAuthenticationClient("192.168.0.1", transport)
        client.login("admin", "admin")

        client.fetchAuthenticated("admin/status", "form=all&operation=read")

        val firstReadSeq = transport.capturedAuthenticatedSeqValues.single()
        assertTrue(
            "s= da primeira leitura autenticada deve ser MAIOR que o seq cru devolvido por form=auth " +
                "(12345) - precisa contabilizar o avanco que o proprio login ja causou no piso esperado " +
                "pelo firmware; reusar o seq bruto (bug da issue #125) deixa a primeira leitura fora de " +
                "sincronia mesmo em uma sessao recem-aberta. capturado=$firstReadSeq",
            firstReadSeq > 12345L,
        )
    }

    @Test
    fun `seq advances between consecutive authenticated reads instead of reusing the same stale value - issue 125`() {
        val transport = FakeTpLinkStokLuciHttpTransport(
            authResponse = authSuccessResponse(seq = 12345),
            simulateRealServerStok = "tok999",
            statusResponse = HttpTransportResponse(200, """{"success":true,"data":{"status":"ok"}}""", emptyMap(), emptyMap()),
        )
        val client = TpLinkStokLuciAuthenticationClient("192.168.0.1", transport)
        client.login("admin", "admin")

        // Mesma query nas duas chamadas -> mesmo tamanho de plaintext/ciphertext/base64 nas duas -> a
        // unica variavel que pode mudar o `s=` entre elas e o `seq` ter avancado (ou nao) de uma
        // chamada pra outra.
        client.fetchAuthenticated("admin/status", "form=all&operation=read")
        client.fetchAuthenticated("admin/status", "form=all&operation=read")

        assertEquals(2, transport.capturedAuthenticatedSeqValues.size)
        val (firstSeq, secondSeq) = transport.capturedAuthenticatedSeqValues
        assertTrue(
            "s= da segunda leitura autenticada deve avancar em relacao a primeira - bug da issue #125 " +
                "reusava sempre o mesmo seq (nunca avancava), produzindo o MESMO s= em toda leitura da " +
                "mesma sessao mesmo com corpo identico. primeiro=$firstSeq segundo=$secondSeq",
            secondSeq > firstSeq,
        )
    }
}
