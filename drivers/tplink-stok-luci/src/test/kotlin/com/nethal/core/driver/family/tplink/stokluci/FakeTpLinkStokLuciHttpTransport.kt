package com.nethal.core.driver.family.tplink.stokluci

import com.nethal.core.protocol.http.HttpTransport
import com.nethal.core.protocol.http.HttpTransportResponse
import java.math.BigInteger
import java.security.KeyFactory
import java.security.spec.RSAPrivateKeySpec
import javax.crypto.Cipher

/**
 * Fake determinístico de [HttpTransport] para a plataforma `tplink-stok-luci`. Roteia por
 * substring da URL (`form=keys`, `form=auth`, `form=login`, ou qualquer outro path autenticado) —
 * o fluxo real confirmado por evidência ao vivo **definitiva** (captura completa via Playwright,
 * terceira rodada) usa **duas** chamadas de preparação distintas: `form=keys` (chave RSA de senha,
 * 1024-bit) e `form=auth` (chave RSA de assinatura, 512-bit, + `seq`) — repostas nesta rodada após
 * a correção anterior (manifesto `catalog-2026.07.17.json`) ter removido `form=keys` por engano,
 * baseada em captura incompleta.
 *
 * Diferente do fake anterior, este simula o servidor de verdade quando `simulateRealServer=true`:
 * decifra o campo `sign` recebido com a chave RSA privada de teste de **assinatura**
 * ([TestSignKeyFixture], 512-bit) para extrair a chave/IV AES gerados pelo client, e usa essa mesma
 * chave/IV para cifrar a resposta de `form=login` — só assim o teste consegue validar o roundtrip
 * completo (client cifra com chave efêmera -> "servidor" decifra o envelope -> "servidor" cifra a
 * resposta com a mesma chave -> client decifra a resposta), sem hardcodar uma chave AES fixa que o
 * client nunca usaria de fato.
 *
 * A chave/IV extraídos de `k=`/`i=` são strings decimais de 16 dígitos usadas diretamente como
 * bytes ASCII (variante `EncryptionWrapperMR`, confirmada por captura byte a byte externa contra o
 * hardware real, ver KDoc de [TpLinkStokLuciCrypto]) — nunca hex de bytes binários aleatórios.
 */
internal class FakeTpLinkStokLuciHttpTransport(
    private val keysResponse: HttpTransportResponse? = null,
    private val authResponse: HttpTransportResponse? = null,
    private val loginResponse: HttpTransportResponse? = null,
    private val statusResponse: HttpTransportResponse? = null,
    private val simulateRealServerStok: String? = null,
    private val simulateRealServerEncryptedLoginPayload: String? = null,
    /**
     * Simula expiração de sessão (issue #16): a partir da (N+1)-ésima leitura autenticada feita sob
     * o mesmo login (`N` = este valor), o "servidor" fake passa a responder 401 em vez de cifrar o
     * corpo normalmente — até o próximo `form=login` bem-sucedido, que reseta a contagem. `null`
     * (default) desliga a simulação, mantendo o comportamento anterior (nunca expira).
     */
    private val expireAuthenticatedReadsAfter: Int? = null,
) : HttpTransport {

    var postCallCount = 0
        private set
    val postedUrls = mutableListOf<String>()
    var lastLoginBody: String? = null
        private set
    private var authenticatedReadCountSinceLogin = 0

    /** Strings decimais de 16 digitos (chave/IV AES) extraidas do envelope `sign` decifrado no ultimo login simulado - ver [simulateLoginResponse]. */
    var lastCapturedAesKeyDigits: String? = null
        private set
    var lastCapturedAesIvDigits: String? = null
        private set
    var lastCapturedSignHash: String? = null
        private set
    var lastAuthenticatedRequestBody: String? = null
        private set

    /** Corpo HTTP cru (`sign=...&data=<base64 cifrado>`) de TODAS as chamadas autenticadas desta sessão — [lastAuthenticatedRequestBody] só guarda a última, insuficiente para testes de duas chamadas (ex.: write+read do diagnóstico nativo de ping). Decifrar `data` com [lastCapturedAesKeyDigits]/[lastCapturedAesIvDigits] para inspecionar o texto plano real enviado. */
    val authenticatedRequestBodies: MutableList<String> = mutableListOf()

    override fun get(url: String, extraHeaders: Map<String, String>): HttpTransportResponse =
        HttpTransportResponse(404, "", emptyMap(), emptyMap())

    override fun post(
        url: String,
        body: String,
        cookies: Map<String, String>,
        extraHeaders: Map<String, String>,
    ): HttpTransportResponse {
        postCallCount++
        postedUrls.add(url)
        return when {
            url.contains("form=keys") -> keysResponse
                ?: (if (simulateRealServerStok != null) passwordKeySuccessResponse() else HttpTransportResponse(404, "", emptyMap(), emptyMap()))
            url.contains("form=auth") -> authResponse
                ?: (if (simulateRealServerStok != null) authSuccessResponse() else HttpTransportResponse(404, "", emptyMap(), emptyMap()))
            url.contains("form=login") -> {
                lastLoginBody = body
                authenticatedReadCountSinceLogin = 0
                when {
                    simulateRealServerEncryptedLoginPayload != null -> simulateEncryptedLoginResponse(body, simulateRealServerEncryptedLoginPayload)
                    simulateRealServerStok != null -> simulateLoginResponse(body, simulateRealServerStok)
                    else -> loginResponse ?: HttpTransportResponse(404, "", emptyMap(), emptyMap())
                }
            }
            else -> {
                lastAuthenticatedRequestBody = body
                authenticatedRequestBodies.add(body)
                when {
                    simulateRealServerStok != null -> {
                        authenticatedReadCountSinceLogin++
                        val expireAfter = expireAuthenticatedReadsAfter
                        if (expireAfter != null && authenticatedReadCountSinceLogin > expireAfter) {
                            HttpTransportResponse(401, "", emptyMap(), emptyMap())
                        } else {
                            simulateAuthenticatedReadResponse(body)
                        }
                    }
                    else -> statusResponse ?: HttpTransportResponse(200, "{}", emptyMap(), emptyMap())
                }
            }
        }
    }

    /**
     * Decifra o `sign` recebido com a chave RSA privada de teste de **assinatura**
     * ([TestSignKeyFixture], 512-bit) para extrair `k`/`i` (chave/IV AES gerados pelo client), e
     * cifra `{"stok":"<stok>"}` com essa mesma chave/IV — simula fielmente o que o firmware real
     * faz (extrai a chave de sessão do envelope assinado, responde cifrado com ela), sem exigir que
     * o client exponha a chave AES gerada internamente.
     *
     * `k=`/`i=` são as strings decimais de 16 dígitos usadas **diretamente como bytes ASCII** da
     * chave/IV (variante `EncryptionWrapperMR`, confirmada por captura byte a byte externa contra o
     * hardware real — ver KDoc de [TpLinkStokLuciCrypto]), nunca hex de bytes binários aleatórios.
     */
    private fun simulateLoginResponse(requestBody: String, stok: String): HttpTransportResponse =
        simulateEncryptedLoginResponse(requestBody, """{"stok":"$stok"}""")

    private fun simulateEncryptedLoginResponse(requestBody: String, encryptedPayloadJson: String): HttpTransportResponse {
        val signHex = Regex("""sign=([0-9a-f]+)""").find(requestBody)?.groupValues?.get(1)
            ?: return HttpTransportResponse(400, "", emptyMap(), emptyMap())

        val signPlaintext = decryptRsaChunked(signHex, TestSignKeyFixture.MODULUS_HEX, TestSignKeyFixture.PRIVATE_EXPONENT_HEX)
        val aesKeyDigits = Regex("""k=(\d+)""").find(signPlaintext)?.groupValues?.get(1)
            ?: return HttpTransportResponse(400, "", emptyMap(), emptyMap())
        val aesIvDigits = Regex("""i=(\d+)""").find(signPlaintext)?.groupValues?.get(1)
            ?: return HttpTransportResponse(400, "", emptyMap(), emptyMap())
        val signHash = Regex("""h=([0-9a-f]+)""").find(signPlaintext)?.groupValues?.get(1)
            ?: return HttpTransportResponse(400, "", emptyMap(), emptyMap())
        lastCapturedAesKeyDigits = aesKeyDigits
        lastCapturedAesIvDigits = aesIvDigits
        lastCapturedSignHash = signHash

        val aesKey = aesKeyDigits.toByteArray(Charsets.US_ASCII)
        val aesIv = aesIvDigits.toByteArray(Charsets.US_ASCII)
        val ciphertextBase64 = encryptLoginResponsePayload(aesKey, aesIv, encryptedPayloadJson)

        return HttpTransportResponse(200, """{"data":"$ciphertextBase64"}""", emptyMap(), emptyMap())
    }

    private fun simulateAuthenticatedReadResponse(requestBody: String): HttpTransportResponse {
        val signHex = Regex("""sign=([0-9a-f]+)""").find(requestBody)?.groupValues?.get(1)
            ?: return HttpTransportResponse(400, "", emptyMap(), emptyMap())
        val signPlaintext = decryptRsaChunked(signHex, TestSignKeyFixture.MODULUS_HEX, TestSignKeyFixture.PRIVATE_EXPONENT_HEX)
        lastCapturedSignHash = Regex("""h=([0-9a-f]+)""").find(signPlaintext)?.groupValues?.get(1)

        val aesKeyDigits = lastCapturedAesKeyDigits ?: return HttpTransportResponse(400, "", emptyMap(), emptyMap())
        val aesIvDigits = lastCapturedAesIvDigits ?: return HttpTransportResponse(400, "", emptyMap(), emptyMap())
        val aesKey = aesKeyDigits.toByteArray(Charsets.US_ASCII)
        val aesIv = aesIvDigits.toByteArray(Charsets.US_ASCII)

        val payload = statusResponse?.body ?: """{"success":true,"data":{"status":"ok"}}"""
        val ciphertextBase64 = encryptLoginResponsePayload(aesKey, aesIv, payload)
        return HttpTransportResponse(200, """{"data":"$ciphertextBase64"}""", emptyMap(), emptyMap())
    }

    private fun decryptRsaChunked(signHex: String, modulusHex: String, privateExponentHex: String): String {
        val modulus = BigInteger(modulusHex, 16)
        val privateExponent = BigInteger(privateExponentHex, 16)
        val privateKey = KeyFactory.getInstance("RSA").generatePrivate(RSAPrivateKeySpec(modulus, privateExponent))
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        val blockHexSize = ((modulus.bitLength() + 7) / 8) * 2
        val plaintext = StringBuilder()
        var offset = 0
        while (offset < signHex.length) {
            val end = minOf(offset + blockHexSize, signHex.length)
            cipher.init(Cipher.DECRYPT_MODE, privateKey)
            val decryptedChunk = cipher.doFinal(TpLinkStokLuciCrypto.hexToBytes(signHex.substring(offset, end)))
            plaintext.append(String(decryptedChunk, Charsets.UTF_8))
            offset = end
        }
        return plaintext.toString()
    }
}

/**
 * Par de chave RSA de teste **1024-bit** gerado localmente com `KeyPairGenerator` — simula a chave
 * real devolvida por `form=keys` (cifra de senha). Módulo/expoentes público e privado. Não é chave
 * de produção nem foi extraída de nenhum equipamento real.
 */
internal object TestRsaKeyFixture {
    const val MODULUS_HEX =
        "a629b0aa30a9013bd241e5f56a97de85c69bc49b3084d5f439e38147eefe1b5" +
            "550fbe50b3ed1cc9380ac71f36b40bce4b124815d1632995e20500f79019dd2" +
            "f0382a6b3979b0684dead224d6125e52df3b7e37ce607507b0ded475018d422" +
            "f7dc6c5f2c0a1f250b40570f1c9406433c42f2c139fc384f0b38707acff726e" +
            "93e3"
    const val EXPONENT_HEX = "10001"
    const val PRIVATE_EXPONENT_HEX =
        "4ef5cabce548ba8c59daf4d30da74398208c0efe8c2ce39b1e132d712871da3" +
            "d4db195e32523ff6a2ca045ba1dc272c0de28f1cc716af414959855f1f3c1b2" +
            "e5acde82846f2ec346f66985897ec282a1e031b26d452c5825145728a94aa13" +
            "f814f567f031e53f99acd83c7f83e902c36d853799882a1563d759923f3057e" +
            "6381"
}

/**
 * Par de chave RSA de teste **512-bit** gerado localmente com `KeyPairGenerator` — simula a chave
 * real devolvida por `form=auth` (assinatura do envelope `sign`), distinta de
 * [TestRsaKeyFixture]. Módulo de 128 caracteres hex = 64 bytes, consistente com o tamanho real
 * confirmado por captura via Playwright. Não é chave de produção nem foi extraída de nenhum
 * equipamento real.
 */
internal object TestSignKeyFixture {
    const val MODULUS_HEX =
        "9358029ac47a6c869cb1cd8df032c1534a386671f381b2a22d04dccab93d01d" +
            "132ea812945464ff2838a8bc9fa10e1a206bdb842fe6b78aa6404a3d972fa0c8d"
    const val EXPONENT_HEX = "10001"
    const val PRIVATE_EXPONENT_HEX =
        "f46981a889532ac3011a5007aaf2068ecb0753a8a26dfa8bda71be6ee967b1a" +
            "1648e25607219b394b577fe63f55f105a95f2f0009f73f622e3fecddda26d3c7"
}

/** Resposta real confirmada de `form=keys`: `{"success":true,"data":{"password":[nn, ee],"mode":"router","username":""}}`. */
internal fun passwordKeySuccessResponse(
    modulusHex: String = TestRsaKeyFixture.MODULUS_HEX,
    exponentHex: String = TestRsaKeyFixture.EXPONENT_HEX,
): HttpTransportResponse = HttpTransportResponse(
    statusCode = 200,
    body = """{"success":true,"data":{"password":["$modulusHex","$exponentHex"],"mode":"router","username":""}}""",
    headers = emptyMap(),
    cookies = emptyMap(),
)

/** Resposta real confirmada de `form=auth`: `{"success": true, "data": {"key": [nn, ee], "seq": N}}`. */
internal fun authSuccessResponse(
    modulusHex: String = TestSignKeyFixture.MODULUS_HEX,
    exponentHex: String = TestSignKeyFixture.EXPONENT_HEX,
    seq: Long = 12345,
): HttpTransportResponse = HttpTransportResponse(
    statusCode = 200,
    body = """{"success":true,"data":{"key":["$modulusHex","$exponentHex"],"seq":$seq}}""",
    headers = emptyMap(),
    cookies = emptyMap(),
)

internal fun loginFailureResponse(): HttpTransportResponse = HttpTransportResponse(
    statusCode = 200,
    body = """{"data":null}""",
    headers = emptyMap(),
    cookies = emptyMap(),
)

/** Cifra [plaintextJson] com AES-CBC/PKCS7 usando [aesKey]/[aesIv], devolvendo o base64 esperado no campo `data` da resposta real de `form=login`. */
internal fun encryptLoginResponsePayload(aesKey: ByteArray, aesIv: ByteArray, plaintextJson: String): String {
    val encrypted = TpLinkStokLuciCrypto.aesCbcEncrypt(aesKey, aesIv, plaintextJson.toByteArray(Charsets.UTF_8))
    return TpLinkStokLuciCrypto.base64Encode(encrypted)
}
