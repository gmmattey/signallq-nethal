package com.nethal.core.driver.family.tplink.stokluci

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Codec do protocolo JSON real da plataforma `tplink-stok-luci`, corrigido a partir de evidência ao
 * vivo **definitiva** (terceira rodada, 2026-07-07): captura via Playwright, com
 * `page.on('response')` interceptando corpo completo de request E response de cada chamada
 * `cgi-bin/luci` durante um login real bem-sucedido contra o hardware físico do Luiz (Archer C6
 * v2.0, firmware `1.1.10 Build 20230830 rel.69433(5553)`) — inclusive chamadas autenticadas
 * pós-login, com `stok` real funcionando. Ver KDoc de [TpLinkStokLuciCrypto] para o detalhe
 * completo da correção e o que ainda não está confirmado byte a byte.
 *
 * Formato real de resposta de `form=keys` (`operation=read`, primeira chamada de preparação —
 * chave RSA **só para cifrar a senha**):
 * ```json
 * {"success":true,"data":{"password":["<256 caracteres hex, RSA 1024-bit>","010001"],"mode":"router","username":""}}
 * ```
 *
 * Formato real de resposta de `form=auth` (`operation=read`, segunda chamada de preparação — chave
 * RSA **só para assinar o envelope `sign`**, distinta da de `form=keys`):
 * ```json
 * {"success":true,"data":{"key":["<128 caracteres hex, RSA 512-bit>","010001"],"seq":123}}
 * ```
 *
 * Formato real de resposta de `form=login` bem-sucedido:
 * ```json
 * {"data": "<base64, ciphertext AES-CBC cifrado com a chave/IV da sessão de login>"}
 * ```
 * (sem campo `success` visível no corpo, diferente das respostas de `form=keys`/`form=auth` — o
 * corpo decifrado contém um JSON com `stok`, mesmo padrão de `loads(aes_decrypt(data['data']))` da
 * lib de referência; estrutura exata do JSON decifrado não confirmada byte a byte além do campo
 * `stok` em si.)
 *
 * **Correção sobre a rodada anterior** (manifesto `catalog-2026.07.17.json`): aquela rodada
 * concluiu, por engano, que só existia a chamada `form=auth` e uma única chave RSA reaproveitada
 * para senha e assinatura. Essa conclusão veio de uma captura incompleta (extensão Chrome pulou
 * `form=keys` por cache/estado do navegador). A captura completa via Playwright desta rodada
 * confirma as duas chamadas com duas chaves distintas.
 */
internal object TpLinkStokLuciResponseParser {

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class PasswordKeyResponseData(val password: List<String> = emptyList())

    @Serializable
    private data class PasswordKeyResponse(val success: Boolean = false, val data: PasswordKeyResponseData? = null)

    @Serializable
    private data class AuthResponseData(val seq: Long = 0L, val key: List<String> = emptyList())

    @Serializable
    private data class AuthResponse(val success: Boolean = false, val data: AuthResponseData? = null)

    @Serializable
    private data class LoginResponseEnvelope(val data: String? = null)

    @Serializable
    internal data class DecryptedLoginErrorData(
        val failureCount: Int? = null,
        val attemptsAllowed: Int? = null,
    )

    @Serializable
    internal data class DecryptedLoginEnvelope(
        val stok: String? = null,
        val success: Boolean? = null,
        @SerialName("errorcode") val errorCode: String? = null,
        val data: DecryptedLoginSuccessData? = null,
    )

    @Serializable
    internal data class DecryptedLoginSuccessData(
        val stok: String? = null,
    )

    @Serializable
    internal data class DecryptedLoginErrorEnvelope(
        val success: Boolean? = null,
        @SerialName("errorcode") val errorCode: String? = null,
        val data: DecryptedLoginErrorData? = null,
    )

    /**
     * Extrai a chave RSA de cifra de senha (1024-bit) da resposta real de `form=keys`
     * (`data.password = [modulus_hex, exponent_hex]`). Retorna `null` se a resposta não tiver o
     * formato esperado — nunca lança, tratamento defensivo consistente com o resto do NetHAL.
     */
    fun parsePasswordKey(body: String): TpLinkStokLuciPasswordKey? {
        val parsed = runCatching { json.decodeFromString(PasswordKeyResponse.serializer(), body) }.getOrNull()
        val data = parsed?.data ?: return null
        if (data.password.size < 2) return null
        return TpLinkStokLuciPasswordKey(
            key = TpLinkStokLuciRsaKey(modulusHex = data.password[0], exponentHex = data.password[1]),
        )
    }

    /**
     * Extrai a chave RSA de assinatura (512-bit) + sequência da resposta real de `form=auth`
     * (`data.key = [modulus_hex, exponent_hex]`, `data.seq`). Retorna `null` se a resposta não tiver
     * o formato esperado — nunca lança, tratamento defensivo consistente com o resto do NetHAL.
     */
    fun parseAuthKeys(body: String): TpLinkStokLuciAuthKeys? {
        val parsed = runCatching { json.decodeFromString(AuthResponse.serializer(), body) }.getOrNull()
        val data = parsed?.data ?: return null
        if (data.key.size < 2) return null
        return TpLinkStokLuciAuthKeys(
            seq = data.seq,
            key = TpLinkStokLuciRsaKey(modulusHex = data.key[0], exponentHex = data.key[1]),
        )
    }

    /** Extrai o campo `data` (base64, ciphertext) do envelope de resposta de `form=login`. `null` se ausente/malformado. */
    fun parseLoginCiphertextBase64(body: String): String? {
        val parsed = runCatching { json.decodeFromString(LoginResponseEnvelope.serializer(), body) }.getOrNull()
        return parsed?.data?.takeIf { it.isNotBlank() }
    }

    /** Extrai o `stok` do JSON decifrado (texto plano) do payload de login bem-sucedido. `null` se ausente/malformado. */
    fun parseDecryptedStok(decryptedJson: String): String? {
        val parsed = runCatching { json.decodeFromString(DecryptedLoginEnvelope.serializer(), decryptedJson) }.getOrNull()
        return listOf(parsed?.stok, parsed?.data?.stok).firstOrNull { !it.isNullOrBlank() }
    }

    /** Lê o envelope JSON já decifrado de `form=login`, quando ele representa erro ou sucesso sem `stok`. */
    fun parseDecryptedLoginEnvelope(decryptedJson: String): DecryptedLoginErrorEnvelope? =
        runCatching { json.decodeFromString(DecryptedLoginErrorEnvelope.serializer(), decryptedJson) }.getOrNull()
}

/** Vocabulário `err_code` observado em respostas de erro deste protocolo, quando presente no corpo (campo top-level `error_code`). */
@Serializable
internal data class TpLinkStokLuciErrorEnvelope(
    @SerialName("error_code") val errorCode: Int? = null,
)
