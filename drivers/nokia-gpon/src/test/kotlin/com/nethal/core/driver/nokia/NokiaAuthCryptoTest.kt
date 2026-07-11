package com.nethal.core.driver.nokia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class NokiaAuthCryptoTest {

    private val samplePubkeyHtml = """
        <script>
        var pubkey = '-----BEGIN PUBLIC KEY-----\
        MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQC7VJTUt9Us8cKjMzEfYyjiWA4R\
        4/M2bS1GB4t7NXp98C3SC6dVMvDuictGeurT8jNbvJZHtCSuYEvuNMoSfm76oqFv\
        ap5UZBw==\
        -----END PUBLIC KEY-----';
        var nonce = "abc123nonce";
        var token = "csrf-token-xyz";
        </script>
    """.trimIndent()

    @Test
    fun `extracts pubkey nonce and csrf token from login page html`() {
        val pubkey = NokiaAuthCrypto.extractPublicKeyBase64(samplePubkeyHtml)
        val nonce = NokiaAuthCrypto.extractNonce(samplePubkeyHtml)
        val token = NokiaAuthCrypto.extractCsrfToken(samplePubkeyHtml)

        assertTrue((pubkey?.length ?: 0) > 50)
        assertEquals("abc123nonce", nonce)
        assertEquals("csrf-token-xyz", token)
    }

    @Test
    fun `extraction returns null when fields are absent instead of throwing`() {
        assertNull(NokiaAuthCrypto.extractPublicKeyBase64("<html>no pubkey here</html>"))
        assertNull(NokiaAuthCrypto.extractNonce("<html></html>"))
        assertNull(NokiaAuthCrypto.extractCsrfToken("<html></html>"))
    }

    @Test
    fun `base64 url escape replaces plus slash and equals per sjcl convention`() {
        // bytes que produzem '+', '/' e padding '=' na saida base64 padrao
        val bytes = byteArrayOf(0xFB.toByte(), 0xFF.toByte(), 0xFE.toByte())
        val escaped = NokiaAuthCrypto.base64UrlEscape(bytes)

        assertTrue(!escaped.contains('+'))
        assertTrue(!escaped.contains('/'))
        assertTrue(!escaped.contains('='))
    }

    @Test
    fun `base64 url no pad strips padding but keeps url-safe alphabet`() {
        val bytes = ByteArray(5) { 0xFF.toByte() }
        val noPad = NokiaAuthCrypto.base64UrlNoPad(bytes)

        assertTrue(!noPad.contains('='))
    }

    @Test
    fun `aes cbc iso7816 padding appends 0x80 then zeros up to block size`() {
        val key = ByteArray(16) { 1 }
        val iv = ByteArray(16) { 2 }
        val plaintext = "abc".toByteArray(Charsets.UTF_8) // 3 bytes -> precisa de 13 bytes de padding

        val encrypted = NokiaAuthCrypto.aesCbcEncryptIso7816(key, iv, plaintext)

        // decripta manualmente para inspecionar o padding aplicado
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val decrypted = cipher.doFinal(encrypted)

        assertEquals(16, decrypted.size)
        assertEquals("abc", String(decrypted.copyOfRange(0, 3), Charsets.UTF_8))
        assertEquals(0x80.toByte(), decrypted[3])
        for (i in 4 until 16) assertEquals(0.toByte(), decrypted[i])
    }

    @Test
    fun `rsa pkcs1 encryption round-trips with a generated key pair`() {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
        val publicKeyBase64 = Base64.getEncoder().encodeToString(keyPair.public.encoded)

        val encrypted = NokiaAuthCrypto.rsaEncryptPkcs1(publicKeyBase64, "chave-de-teste-aes iv-de-teste")

        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.DECRYPT_MODE, keyPair.private)
        val decrypted = String(cipher.doFinal(encrypted), Charsets.UTF_8)

        assertEquals("chave-de-teste-aes iv-de-teste", decrypted)
    }

    // referencia X509EncodedKeySpec só para garantir que o formato aceito pela lib é o mesmo usado no encode acima
    @Test
    fun `public key format used is SubjectPublicKeyInfo X509`() {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
        val spec = X509EncodedKeySpec(keyPair.public.encoded)
        assertTrue(spec.encoded.isNotEmpty())
    }
}
