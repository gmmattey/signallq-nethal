package com.nethal.core.driver.tplink

import com.nethal.core.auth.AuthenticationStrategy
import java.io.IOException
import java.security.SecureRandom

/**
 * Motivo da falha de login. Vocabulário fechado com base no que a pesquisa de segurança
 * independente (Hex Fish / `0xf15h/tp_link_gdpr`) documenta como marcadores de resposta
 * (`[error]0`/`$.ret=0` de sucesso; qualquer outra coisa é tratado como falha) — o protocolo não
 * expõe um código de erro rico equivalente ao `err_t` do Nokia, então a granularidade aqui é
 * necessariamente menor.
 */
internal enum class TplinkLoginFailureReason {
    INVALID_CREDENTIALS,
    SESSION_IN_USE,
    UNEXPECTED_RESPONSE,
    UNKNOWN,
}

internal class TplinkLoginException(
    val reason: TplinkLoginFailureReason,
    message: String,
) : IOException(message)

/** Parâmetros RSA + sequência de sessão devolvidos por `/cgi/getParm`. */
internal data class TplinkRsaParams(
    val modulusHex: String,
    val exponentHex: String,
    val sequence: Long,
)

/**
 * Sessão autenticada contra a WebUI TP-Link Archer (profile `tplink_archer_c6_v1`), seguindo o
 * handshake "web encrypted password" descrito pela pesquisa de segurança independente de Hex Fish
 * ("TP-Link's Attempt at GDPR Compliance", https://hex.fish/2021/05/10/tp-link-gdpr/) e pela PoC
 * associada `0xf15h/tp_link_gdpr` (`authenticate.py`) — não copiado do projeto GPL
 * `TP-Link-Archer-C6U`.
 *
 * Handshake:
 * 1. POST `/cgi/getParm` com corpo `[/cgi/getParm#0,0,0,0,0,0#0,0,0,0,0,0]0,0` (texto plano, sem
 *    JSON) devolve `ee`/`nn` (expoente/módulo RSA em hex) e `seq` (sequência da sessão de login).
 * 2. Gera chave/IV AES aleatórios (16 bytes cada).
 * 3. Monta o envelope `sign`: `key=<hex>&iv=<hex>&h=<md5(username+password)>&s=<seq>`, cifrado com
 *    RSA sem padding (`TplinkAuthCrypto.rsaEncryptNoPadding`) usando `ee`/`nn` de `getParm`.
 * 4. Monta o payload `data`: corpo de login em texto plano
 *    `[/cgi/login#0,0,0,0,0,0#0,0,0,0,0,0]0,3\r\nusername=<user>\r\npassword=<pass>\r\n`, cifrado
 *    com AES (CBC ou GCM conforme [TplinkCipherVariant]) usando a chave/IV do passo 2.
 * 5. POST `/cgi_gdpr` com corpo `text/plain` `sign=<hex(sign)>\r\ndata=<base64(data)>\r\n`.
 * 6. Sucesso: resposta decifrada contém os marcadores `[error]0`/`$.ret=0`; cookie de sessão vem
 *    via `Set-Cookie` (nome observado na PoC: `JSESSIONID`, mas o firmware do C6 pode variar —
 *    ver nota em [sessionCookieName]). Qualquer outro conteúdo é tratado como falha de login.
 *
 * Nunca confirmado contra hardware real (nenhuma evidência de campo própria ainda, diferente do
 * Nokia) — ver `TplinkOntDriver` para o que fica incerto até o teste real.
 */
internal class TplinkAuthenticationClient(
    private val host: String,
    private val transport: TplinkHttpTransport = DefaultTplinkHttpTransport(),
    private val cipherVariant: TplinkCipherVariant = TplinkCipherVariant.AES_CBC,
    private val random: SecureRandom = SecureRandom(),
) : AuthenticationStrategy<Map<String, String>> {
    private val baseUrl = "http://$host"

    /**
     * Nome do cookie de sessão observado na PoC de terceiros (`JSESSIONID`). Não confirmado contra
     * o firmware real do Archer C6 — o parser de cookie abaixo aceita qualquer cookie devolvido
     * pela resposta de login como candidato, e usa este nome só como preferência quando presente,
     * para não travar em uma suposição de nome que pode não se aplicar a este modelo.
     */
    private val sessionCookieNameHint = "JSESSIONID"

    private var sessionCookies: Map<String, String> = emptyMap()

    val isAuthenticated: Boolean get() = sessionCookies.isNotEmpty()

    @Throws(IOException::class)
    override fun login(username: String, password: String): Map<String, String> {
        val params = fetchRsaParams()

        val aesKey = TplinkAuthCrypto.generateSecureBytes(16, random)
        val aesIv = TplinkAuthCrypto.generateSecureBytes(16, random)

        val credentialHash = TplinkAuthCrypto.md5Hex("$username$password")
        val signPlaintext = "key=${TplinkAuthCrypto.bytesToHex(aesKey)}" +
            "&iv=${TplinkAuthCrypto.bytesToHex(aesIv)}" +
            "&h=$credentialHash" +
            "&s=${params.sequence}"

        val signBytes = TplinkAuthCrypto.rsaEncryptNoPadding(
            params.modulusHex,
            params.exponentHex,
            signPlaintext.toByteArray(Charsets.UTF_8),
        )

        val loginBody = "[/cgi/login#0,0,0,0,0,0#0,0,0,0,0,0]0,3\r\n" +
            "username=$username\r\n" +
            "password=$password\r\n"

        val dataBytes = when (cipherVariant) {
            TplinkCipherVariant.AES_CBC -> TplinkAuthCrypto.aesCbcEncrypt(aesKey, aesIv, loginBody.toByteArray(Charsets.UTF_8))
            TplinkCipherVariant.AES_GCM -> TplinkAuthCrypto.aesGcmEncrypt(aesKey, aesIv, loginBody.toByteArray(Charsets.UTF_8))
        }

        val requestBody = "sign=${TplinkAuthCrypto.bytesToHex(signBytes)}\r\n" +
            "data=${TplinkAuthCrypto.base64Encode(dataBytes)}\r\n"

        val response = transport.post("$baseUrl/cgi_gdpr", requestBody)

        val bodyLooksSuccessful = response.statusCode == 200 &&
            (response.body.contains("[error]0") || response.body.contains("\$.ret=0"))

        if (bodyLooksSuccessful && response.cookies.isNotEmpty()) {
            sessionCookies = response.cookies
            return sessionCookies
        }

        if (!bodyLooksSuccessful && response.cookies.isNotEmpty()) {
            // Cookie novo sem marcador de sucesso reconhecido: mais provável ser mensagem de erro
            // (ex.: sessão já em uso, credencial inválida) do que sucesso silencioso — não assume
            // sessão válida nesse caso.
        }

        throw TplinkLoginException(classifyFailure(response.statusCode, response.body), "login falhou: status=${response.statusCode}")
    }

    @Throws(IOException::class)
    fun fetchAuthenticated(path: String): String {
        check(isAuthenticated) { "fetchAuthenticated chamado antes de login() bem-sucedido" }
        return transport.get("$baseUrl$path", authenticatedHeaders(sessionCookies)).body
    }

    override fun authenticatedHeaders(session: Map<String, String>): Map<String, String> {
        if (session.isEmpty()) return emptyMap()
        return mapOf("Cookie" to session.entries.joinToString("; ") { "${it.key}=${it.value}" })
    }

    /**
     * `getParm` devolve texto plano (não JSON) contendo `var ee="..."`, `var nn="..."`,
     * `var seq=...`, conforme a PoC de terceiros. Corpo de requisição replicado literalmente da
     * PoC — qualquer desvio de formatação faz o firmware devolver uma resposta vazia/erro sem
     * status HTTP claro, mesmo padrão de risco silencioso já visto no Nokia.
     */
    private fun fetchRsaParams(): TplinkRsaParams {
        val response = transport.post("$baseUrl/cgi/getParm", "[/cgi/getParm#0,0,0,0,0,0#0,0,0,0,0,0]0,0\r\n")
        val body = response.body

        val exponent = Regex("""ee\s*=\s*"?([0-9a-fA-F]+)"?""").find(body)?.groupValues?.get(1)
            ?: throw IOException("expoente RSA (ee) nao encontrado na resposta de getParm")
        val modulus = Regex("""nn\s*=\s*"?([0-9a-fA-F]+)"?""").find(body)?.groupValues?.get(1)
            ?: throw IOException("modulo RSA (nn) nao encontrado na resposta de getParm")
        val sequence = Regex("""seq\s*=\s*"?(\d+)"?""").find(body)?.groupValues?.get(1)?.toLongOrNull()
            ?: throw IOException("sequencia (seq) nao encontrada na resposta de getParm")

        return TplinkRsaParams(modulusHex = modulus, exponentHex = exponent, sequence = sequence)
    }

    private fun classifyFailure(statusCode: Int, body: String): TplinkLoginFailureReason = when {
        body.contains("session", ignoreCase = true) && body.contains("busy", ignoreCase = true) ->
            TplinkLoginFailureReason.SESSION_IN_USE
        statusCode == 401 || statusCode == 403 -> TplinkLoginFailureReason.INVALID_CREDENTIALS
        body.contains("[error]") -> TplinkLoginFailureReason.INVALID_CREDENTIALS
        body.isBlank() -> TplinkLoginFailureReason.UNEXPECTED_RESPONSE
        else -> TplinkLoginFailureReason.UNKNOWN
    }
}
