package com.nethal.core.driver.tplink

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Primitivas criptográficas do handshake "web encrypted password" da WebUI TP-Link (profile
 * `tplink_archer_c6_v1`): RSA sem padding para o envelope de sessão (`sign`) e AES para o payload
 * de credenciais (`data`) — CBC em firmwares mais antigos, GCM em firmwares recentes.
 *
 * Esta lógica é entendimento próprio do protocolo, reimplementado do zero para o NetHAL — não é
 * cópia do projeto comunitário `TP-Link-Archer-C6U` (GPL-3.0, `AlexandrErohin`), que o NetHAL trata
 * só como referência de existência de protocolo (ver driver-adoption-strategy.md e o profile no
 * catálogo). O desenho concreto do handshake (endpoints `/cgi/getParm` + `/cgi_gdpr`, campos
 * `sign`/`data`, MD5 de `username+password`, RSA sem padding determinístico) segue a descrição
 * publicada independentemente por pesquisa de segurança de terceiros (Hex Fish, "TP-Link's Attempt
 * at GDPR Compliance", https://hex.fish/2021/05/10/tp-link-gdpr/, e a prova de conceito associada
 * `0xf15h/tp_link_gdpr` no GitHub), não do código GPL.
 *
 * Nunca confirmado contra hardware real do Luiz — ver `TplinkAuthenticationClient` para o que fica
 * incerto até o teste real (SIG-334 quando aberto).
 */
internal object TplinkAuthCrypto {

    /**
     * RSA sem padding (ECB/NoPadding) sobre o bloco de assinatura, como descrito pela pesquisa de
     * segurança independente citada acima: o firmware usa RSA determinístico (sem OAEP/PKCS1) para
     * cifrar o envelope `sign` contendo chave/IV AES + hash de credencial + sequência de sessão.
     * Isso é uma escolha do firmware, não do NetHAL — o próprio pesquisador aponta como fraqueza
     * criptográfica conhecida do dispositivo, não como recomendação de design.
     */
    fun rsaEncryptNoPadding(modulusHex: String, exponentHex: String, plaintext: ByteArray): ByteArray {
        val modulus = java.math.BigInteger(modulusHex, 16)
        val exponent = java.math.BigInteger(exponentHex, 16)
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, exponent))
        val cipher = Cipher.getInstance("RSA/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)

        // RSA/ECB/NoPadding do JCE exige bloco do tamanho exato do módulo — completa com zeros à
        // esquerda (equivalente a um inteiro menor que o módulo), igual ao comportamento observado
        // na implementação de referência.
        val blockSize = (modulus.bitLength() + 7) / 8
        val block = ByteArray(blockSize)
        val offset = blockSize - plaintext.size
        require(offset >= 0) { "plaintext maior que o modulo RSA" }
        System.arraycopy(plaintext, 0, block, offset, plaintext.size)

        return cipher.doFinal(block)
    }

    fun md5Hex(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun generateSecureBytes(size: Int, random: SecureRandom = SecureRandom()): ByteArray =
        ByteArray(size).also { random.nextBytes(it) }

    /** AES-CBC/PKCS5Padding — variante de firmware antigo (`TPLinkMRClient` na nomenclatura da lib de referência). */
    fun aesCbcEncrypt(key: ByteArray, iv: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(plaintext)
    }

    fun aesCbcDecrypt(key: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(ciphertext)
    }

    /**
     * AES-GCM/NoPadding — variante de firmware recente (`TPLinkMRClientGCM` na nomenclatura da lib
     * de referência). Tag de 128 bits anexada ao final do ciphertext, como é convenção do JCE.
     */
    fun aesGcmEncrypt(key: ByteArray, iv: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return cipher.doFinal(plaintext)
    }

    fun aesGcmDecrypt(key: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }

    fun base64Encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    fun base64Decode(value: String): ByteArray = Base64.getDecoder().decode(value)

    fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    fun hexToBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { i ->
        hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
}
