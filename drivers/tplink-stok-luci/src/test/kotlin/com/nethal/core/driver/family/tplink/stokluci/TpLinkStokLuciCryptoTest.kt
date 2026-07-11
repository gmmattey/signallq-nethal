package com.nethal.core.driver.family.tplink.stokluci

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TpLinkStokLuciCryptoTest {

    @Test
    fun `rsaEncryptChunkedToHex produces a hex string, never the plaintext`() {
        val encrypted = TpLinkStokLuciCrypto.rsaEncryptChunkedToHex(
            TestRsaKeyFixture.MODULUS_HEX,
            TestRsaKeyFixture.EXPONENT_HEX,
            "k=aabb&i=ccdd&h=deadbeef&s=1",
        )

        assertTrue(encrypted.matches(Regex("^[0-9a-f]+$")))
        assertNotEquals("k=aabb&i=ccdd&h=deadbeef&s=1", encrypted)
    }

    @Test
    fun `rsaEncryptChunkedToHex chunks plaintext larger than one RSA block and round-trips via RSA decryption`() {
        // sign plaintext tipico (k=32 hex + i=32 hex + h=32 hex + s=numero) excede facilmente um
        // unico bloco PKCS1v1.5 de uma chave RSA 1024-bit (~117 bytes utilizaveis) quando o
        // chunkSizeBytes configurado for pequeno o suficiente para forcar mais de um pedaco.
        val plaintext = "k=" + "a".repeat(32) + "&i=" + "b".repeat(32) + "&h=" + "c".repeat(32) + "&s=999999"
        val encryptedHex = TpLinkStokLuciCrypto.rsaEncryptChunkedToHex(
            TestRsaKeyFixture.MODULUS_HEX,
            TestRsaKeyFixture.EXPONENT_HEX,
            plaintext,
            chunkSizeBytes = 53,
        )

        val blockHexSize = ((java.math.BigInteger(TestRsaKeyFixture.MODULUS_HEX, 16).bitLength() + 7) / 8) * 2
        val expectedChunks = (plaintext.toByteArray(Charsets.UTF_8).size + 52) / 53
        assertEquals(expectedChunks * blockHexSize, encryptedHex.length)
    }

    @Test
    fun `aesCbcEncrypt then aesCbcDecrypt round-trips the original plaintext`() {
        val key = TpLinkStokLuciCrypto.generateRandomBytes(TpLinkStokLuciCrypto.AES_KEY_SIZE_BYTES)
        val iv = TpLinkStokLuciCrypto.generateRandomBytes(TpLinkStokLuciCrypto.AES_IV_SIZE_BYTES)
        val plaintext = """{"stok":"abc123"}"""

        val ciphertext = TpLinkStokLuciCrypto.aesCbcEncrypt(key, iv, plaintext.toByteArray(Charsets.UTF_8))
        val decrypted = TpLinkStokLuciCrypto.aesCbcDecrypt(key, iv, ciphertext)

        assertEquals(plaintext, String(decrypted, Charsets.UTF_8))
    }

    @Test
    fun `generateAesKeyOrIvDigits produces exactly 16 decimal ASCII digits, never hex or binary bytes`() {
        val digits = TpLinkStokLuciCrypto.generateAesKeyOrIvDigits()

        assertEquals(16, digits.length)
        assertTrue("deve conter so digitos decimais 0-9, formato real confirmado por captura byte a byte", digits.matches(Regex("^[0-9]{16}$")))
    }

    @Test
    fun `generateAesKeyOrIvDigits string used directly as UTF-8 bytes round-trips through AES-CBC`() {
        // Confirma o uso real: a string decimal de 16 caracteres vira, sem hex-decodificar, os 16
        // bytes ASCII usados como SecretKeySpec/IvParameterSpec (variante EncryptionWrapperMR).
        val keyDigits = TpLinkStokLuciCrypto.generateAesKeyOrIvDigits()
        val ivDigits = TpLinkStokLuciCrypto.generateAesKeyOrIvDigits()
        val key = keyDigits.toByteArray(Charsets.US_ASCII)
        val iv = ivDigits.toByteArray(Charsets.US_ASCII)
        assertEquals(TpLinkStokLuciCrypto.AES_KEY_SIZE_BYTES, key.size)
        assertEquals(TpLinkStokLuciCrypto.AES_IV_SIZE_BYTES, iv.size)

        val plaintext = "operation=login&password=deadbeef"
        val ciphertext = TpLinkStokLuciCrypto.aesCbcEncrypt(key, iv, plaintext.toByteArray(Charsets.UTF_8))
        val decrypted = TpLinkStokLuciCrypto.aesCbcDecrypt(key, iv, ciphertext)

        assertEquals(plaintext, String(decrypted, Charsets.UTF_8))
    }

    @Test
    fun `buildLoginPlaintext embeds the rsa-encrypted password hex, never confirm=true, never a username field`() {
        val rsaEncryptedPasswordHex = "ab".repeat(128)
        val plaintext = TpLinkStokLuciCrypto.buildLoginPlaintext(rsaEncryptedPasswordHex)

        assertEquals("operation=login&password=$rsaEncryptedPasswordHex", plaintext)
        assertTrue(plaintext.contains("password=$rsaEncryptedPasswordHex"))
        assertTrue(plaintext.contains("operation=login"))
        assertTrue("nao deve mais conter confirm=true, confirmado por captura real do texto plano", !plaintext.contains("confirm=true"))
        assertTrue("nao deve conter campo de usuario, firmware autentica so por senha", !plaintext.contains("username"))
    }

    @Test
    fun `buildSignPlaintext embeds aes key digits, iv digits, md5 hash of username+password and seq plus base64 length`() {
        // k=/i= recebem strings decimais de 16 digitos (formato real confirmado por captura byte a
        // byte externa), nunca hex de bytes aleatorios - buildSignPlaintext so concatena, quem gera
        // o formato certo e generateAesKeyOrIvDigits (ver TpLinkStokLuciAuthenticationClient).
        val plaintext = TpLinkStokLuciCrypto.buildSignPlaintext("5945270769887026", "3257785177414969", "admin", "minhasenha", 42L, 44)

        assertTrue(plaintext.startsWith("k=5945270769887026&i=3257785177414969&h="))
        assertTrue(plaintext.endsWith("&s=86"))
        assertTrue(plaintext.contains(TpLinkStokLuciCrypto.md5Hex("adminminhasenha")))
    }

    @Test
    fun `buildSignPlaintext matches the real captured hash for admin plus admin and applies seq plus base64 length`() {
        // Reproduz a FORMA exata do texto puro capturado por ferramenta externa (nao Claude Code)
        // via interceptacao de um login real bem-sucedido contra o hardware fisico do Luiz (Archer
        // C6 v2.0, firmware 1.1.10 Build 20230830 rel.69433(5553)):
        // "k=5945270769887026&i=3257785177414969&h=f6fdffe48c908deb0f4c3bd36c032e72&s=855135262"
        // k=/i= sao strings decimais de 16 digitos (nunca hex de bytes aleatorios), h=32 caracteres
        // hex (MD5) e, no login real do Luiz, bate exatamente com md5("adminadmin"), provando que
        // a formula correta e md5(username+password), nao md5(password). O s= do firmware soma o
        // seq ao comprimento da string Base64 do campo data antes do URL-encoding.
        val plaintext = TpLinkStokLuciCrypto.buildSignPlaintext("5945270769887026", "3257785177414969", "admin", "admin", 855134878L, 384)

        assertEquals("k=5945270769887026&i=3257785177414969&h=f6fdffe48c908deb0f4c3bd36c032e72&s=855135262", plaintext)
        assertTrue(Regex("""^k=\d{16}&i=\d{16}&h=[0-9a-f]{32}&s=\d+$""").matches(plaintext))
    }

    @Test
    fun `buildAuthenticatedSignPlaintext matches the real post-login envelope shape without k and i`() {
        val plaintext = TpLinkStokLuciCrypto.buildAuthenticatedSignPlaintext(
            hash = "f6fdffe48c908deb0f4c3bd36c032e72",
            seq = 569536341L,
            encryptedDataBase64Length = 24,
        )

        assertEquals("h=f6fdffe48c908deb0f4c3bd36c032e72&s=569536365", plaintext)
        assertTrue(!plaintext.contains("k="))
        assertTrue(!plaintext.contains("i="))
    }

    @Test
    fun `extractSysauthCookie parses value from a raw Set-Cookie header with other attributes`() {
        val value = TpLinkStokLuciCrypto.extractSysauthCookie("sysauth=deadbeef1234; Path=/; HttpOnly")
        assertEquals("deadbeef1234", value)
    }

    @Test
    fun `extractSysauthCookie returns null when the header has no sysauth value`() {
        assertNull(TpLinkStokLuciCrypto.extractSysauthCookie("otherCookie=abc; Path=/"))
        assertNull(TpLinkStokLuciCrypto.extractSysauthCookie(null))
        assertNull(TpLinkStokLuciCrypto.extractSysauthCookie(""))
    }
}
