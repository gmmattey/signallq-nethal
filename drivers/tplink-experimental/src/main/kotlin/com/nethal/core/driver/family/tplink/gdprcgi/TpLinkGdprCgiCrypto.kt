package com.nethal.core.driver.family.tplink.gdprcgi

import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object TpLinkGdprCgiCrypto {
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val GCM_TAG_BASE64_LENGTH = 24

    fun md5Hex(input: String): String =
        MessageDigest.getInstance("MD5")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun randomAsciiDigits(length: Int, random: SecureRandom = SecureRandom()): String =
        buildString(length) {
            repeat(length) { append(random.nextInt(10)) }
        }

    fun base64Encode(value: ByteArray): String = Base64.getEncoder().encodeToString(value)

    fun base64Decode(value: String): ByteArray = Base64.getDecoder().decode(value)

    fun aesCbcEncrypt(keyAscii: String, ivAscii: String, plaintext: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(keyAscii.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(ivAscii.toByteArray(Charsets.UTF_8)),
        )
        return base64Encode(cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)))
    }

    fun aesCbcDecryptToString(keyAscii: String, ivAscii: String, ciphertextBase64: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(keyAscii.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(ivAscii.toByteArray(Charsets.UTF_8)),
        )
        return String(cipher.doFinal(base64Decode(ciphertextBase64)), Charsets.UTF_8)
    }

    fun aesGcmEncrypt(keyAscii: String, nonceAscii: String, plaintext: String): Pair<String, String> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(keyAscii.toByteArray(Charsets.UTF_8), "AES"),
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonceAscii.toByteArray(Charsets.UTF_8)),
        )
        val encryptedWithTag = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val ciphertext = encryptedWithTag.copyOfRange(0, encryptedWithTag.size - 16)
        val tag = encryptedWithTag.copyOfRange(encryptedWithTag.size - 16, encryptedWithTag.size)
        return base64Encode(ciphertext) to base64Encode(tag)
    }

    fun aesGcmDecryptCombinedToString(keyAscii: String, nonceAscii: String, combinedCiphertextAndTag: String): String {
        require(combinedCiphertextAndTag.length >= GCM_TAG_BASE64_LENGTH) {
            "resposta GCM invalida: corpo menor que o tag base64 esperado"
        }
        val ciphertextBase64 = combinedCiphertextAndTag.dropLast(GCM_TAG_BASE64_LENGTH)
        val tagBase64 = combinedCiphertextAndTag.takeLast(GCM_TAG_BASE64_LENGTH)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(keyAscii.toByteArray(Charsets.UTF_8), "AES"),
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonceAscii.toByteArray(Charsets.UTF_8)),
        )
        val payload = base64Decode(ciphertextBase64) + base64Decode(tagBase64)
        return String(cipher.doFinal(payload), Charsets.UTF_8)
    }

    fun rsaEncryptChunkedToHex(
        modulusHex: String,
        exponentHex: String,
        plaintext: String,
        paddingMode: TpLinkGdprCgiRsaPaddingMode,
    ): String {
        val publicKey = buildRsaPublicKey(modulusHex, exponentHex)
        val modulusBytes = (modulusHex.length / 2)
        val chunkSizeBytes = when (paddingMode) {
            TpLinkGdprCgiRsaPaddingMode.RAW_NO_PADDING -> modulusBytes
            TpLinkGdprCgiRsaPaddingMode.PKCS1_V1_5 -> modulusBytes - 11
        }
        val cipherAlgorithm = when (paddingMode) {
            TpLinkGdprCgiRsaPaddingMode.RAW_NO_PADDING -> "RSA/ECB/NoPadding"
            TpLinkGdprCgiRsaPaddingMode.PKCS1_V1_5 -> "RSA/ECB/PKCS1Padding"
        }
        val plaintextBytes = plaintext.toByteArray(Charsets.UTF_8)
        val builder = StringBuilder()
        var offset = 0
        while (offset < plaintextBytes.size) {
            val end = minOf(offset + chunkSizeBytes, plaintextBytes.size)
            val chunk = plaintextBytes.copyOfRange(offset, end)
            val encrypted = when (paddingMode) {
                TpLinkGdprCgiRsaPaddingMode.RAW_NO_PADDING -> {
                    val padded = ByteArray(modulusBytes)
                    chunk.copyInto(padded)
                    Cipher.getInstance(cipherAlgorithm).apply {
                        init(Cipher.ENCRYPT_MODE, publicKey)
                    }.doFinal(padded)
                }
                TpLinkGdprCgiRsaPaddingMode.PKCS1_V1_5 -> Cipher.getInstance(cipherAlgorithm).apply {
                    init(Cipher.ENCRYPT_MODE, publicKey)
                }.doFinal(chunk)
            }
            builder.append(encrypted.joinToString("") { "%02x".format(it) })
            offset = end
        }
        return builder.toString()
    }

    private fun buildRsaPublicKey(modulusHex: String, exponentHex: String) =
        KeyFactory.getInstance("RSA").generatePublic(
            RSAPublicKeySpec(BigInteger(modulusHex, 16), BigInteger(exponentHex, 16)),
        )
}
