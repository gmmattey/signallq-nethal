package com.nethal.core.driver.nokia

import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Primitivas criptográficas do handshake de login do Nokia G-1425G-B: RSA-PKCS#1v1.5 para as
 * chaves de sessão e AES-CBC com padding ISO/IEC 7816-4 (não PKCS5/PKCS7) para o payload de
 * credenciais, replicando o `crypto_page.js` embarcado no firmware.
 *
 * Esta lógica é uma adaptação, escrita do zero para o NetHAL, do driver Nokia de produção do
 * SignallQ (produto irmão, mesma empresa — `NokiaModemCrypto.kt` em
 * feature/fibra do app SignallQ). O esquema criptográfico em si vem do firmware do equipamento,
 * não é invenção de nenhum dos dois produtos; qualquer divergência de padding/encoding aqui
 * quebra o login silenciosamente (o modem responde `err_t=0/1/2`, nunca um erro HTTP claro).
 */
internal object NokiaAuthCrypto {

    private val PUBKEY_REGEX = Regex("""var\s+pubkey\s*=\s*'([^']+)'""", RegexOption.MULTILINE)
    private val NONCE_REGEX = Regex("""var\s+nonce\s*=\s*"([^"]+)"""")
    private val CSRF_TOKEN_REGEX = Regex("""var\s+token\s*=\s*"([^"]+)"""")

    /** Extrai a chave pública RSA (SubjectPublicKeyInfo/X.509, base64 sem cabeçalho PEM) do HTML de login. */
    fun extractPublicKeyBase64(html: String): String? {
        val pem = PUBKEY_REGEX.find(html)?.groupValues?.get(1)
            ?.replace("\\\r\n", "")?.replace("\\\n", "")
            ?: return null
        if (!pem.contains("-----BEGIN PUBLIC KEY-----")) return null
        val base64 = pem
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace("\n", "").replace("\r", "").trim()
        return base64.takeIf { it.length > 50 }
    }

    fun extractNonce(html: String): String? = NONCE_REGEX.find(html)?.groupValues?.get(1)

    fun extractCsrfToken(html: String): String? = CSRF_TOKEN_REGEX.find(html)?.groupValues?.get(1)

    fun generateSecureBytes(size: Int): ByteArray = ByteArray(size).also { SecureRandom().nextBytes(it) }

    /**
     * RSA/ECB/PKCS1Padding sobre `plaintext`, usando a chave pública em SubjectPublicKeyInfo DER
     * (base64 padrão, sem envelope PEM). Compatível com o JSEncrypt usado pelo firmware.
     */
    fun rsaEncryptPkcs1(publicKeyBase64: String, plaintext: String): ByteArray {
        val keyBytes = Base64.getDecoder().decode(publicKeyBase64)
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(keyBytes))
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        return cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
    }

    /**
     * AES-CBC com padding ISO/IEC 7816-4: acrescenta um byte `0x80` seguido de zeros até
     * completar o próximo bloco de 16 bytes. O SJCL do firmware usa este esquema — PKCS5/PKCS7
     * produziria um payload que o modem rejeita sem aviso claro.
     */
    fun aesCbcEncryptIso7816(key: ByteArray, iv: ByteArray, plaintext: ByteArray): ByteArray {
        val padLength = 16 - (plaintext.size % 16)
        val padded = ByteArray(plaintext.size + padLength)
        System.arraycopy(plaintext, 0, padded, 0, plaintext.size)
        padded[plaintext.size] = 0x80.toByte()
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(padded)
    }

    /** Variante URL-safe usada no parâmetro `ck` (e nas chaves internas `enckey`/`enciv`): `+`→`-`, `/`→`_`, `=`→`.`. */
    fun base64UrlEscape(bytes: ByteArray): String =
        Base64.getEncoder().encodeToString(bytes).replace('+', '-').replace('/', '_').replace('=', '.')

    /** Variante URL-safe usada no parâmetro `ct` (formato `base64url.fromBits` do SJCL): sem padding `=`. */
    fun base64UrlNoPad(bytes: ByteArray): String =
        Base64.getEncoder().encodeToString(bytes).replace('+', '-').replace('/', '_').replace("=", "")

    fun base64Encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
}
