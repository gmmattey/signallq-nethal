package com.nethal.core.driver.tplink

import java.io.IOException
import java.security.MessageDigest

/**
 * Motivo de falha de login do Archer C20. Vocabulário menor que o do C6/Nokia porque a
 * evidência disponível para este modelo é mais fraca (ver `tplink_archer_c20_v1` no catálogo) —
 * não há PoC de terceiros nem código de referência que documente um corpo de erro estruturado
 * como `[error]0`/`err_t` para esta família de firmware.
 */
internal enum class TplinkC20LoginFailureReason {
    INVALID_CREDENTIALS,
    UNEXPECTED_RESPONSE,
    UNKNOWN,
}

internal class TplinkC20LoginException(
    val reason: TplinkC20LoginFailureReason,
    message: String,
) : IOException(message)

/**
 * Sessão autenticada contra a WebUI do TP-Link Archer C20 (profile `tplink_archer_c20_v1`).
 *
 * **Mecanismo especulativo** — diferente do C6 (onde o handshake RSA+AES "web encrypted
 * password"/`cgi_gdpr` tem confirmação de pesquisa de segurança independente com PoC funcional),
 * não existe fonte primária ou secundária encontrada nesta pesquisa que documente literalmente o
 * corpo/campos do login do Archer C20. A evidência real disponível é indireta:
 *
 * - CVE-2024-57049 (ACL bypass, TP-Link Archer C20 firmware ≤ V6.6_230412, GHSA-qr32-fcm4-m5h9):
 *   confirma que a WebUI deste modelo expõe endpoints sob o caminho `/cgi` (não `/cgi_gdpr`, não
 *   `/cgi-bin/luci`), e que parte da checagem de "autorizado" do firmware depende do header
 *   `Referer` (bypass documentado: enviar `Referer: http://tplinkwifi.net` sem sessão válida já
 *   basta para receber 200 em vez de 403 em parte das rotas). Isso é consistente com uma geração
 *   de firmware mais simples que a linha GDPR/RSA do C6, sem handshake de chave pública.
 * - Nenhum projeto comunitário consultado (`AlexandrErohin/TP-Link-Archer-C6U`,
 *   `AlexandrErohin/home-assistant-tplink-router`, `ericpignet/home-assistant-tplink_router`) lista
 *   o Archer C20 entre os modelos testados/suportados — nenhum deles documenta um esquema de auth
 *   confirmado para este modelo especificamente.
 * - A convenção mais comum documentada para firmwares TP-Link desta geração/classe (linha
 *   Archer/TL antiga, pré-GDPR) é login via POST simples de formulário com o campo de senha
 *   transformado em hash MD5 do lado do cliente antes do envio (sem RSA, sem AES, sem chave
 *   pública do servidor) — mas esta pesquisa não encontrou uma fonte que reproduza o nome exato do
 *   campo/endpoint para o C20. A implementação abaixo assume essa hipótese (MD5 simples do campo
 *   de senha, POST em `/cgi/login`, cookie de sessão em `Set-Cookie`) como **melhor palpite**, não
 *   como fato confirmado.
 *
 * Todo o corpo desta classe deve ser tratado como `DISCOVERY_ONLY`/especulativo até a primeira
 * execução real de `tplinkC20ManualCheck` — ver `TplinkC20OntDriver` e o catálogo para o que fica
 * incerto até lá. Se o teste real mostrar que o mecanismo é outro (ex.: Basic Auth puro, ou senha
 * em claro sem hash), esta classe precisa ser reescrita, não só ajustada.
 */
internal class TplinkC20AuthenticationClient(
    private val host: String,
    private val transport: TplinkHttpTransport = DefaultTplinkHttpTransport(),
) {
    private val baseUrl = "http://$host"

    private var sessionCookies: Map<String, String> = emptyMap()

    val isAuthenticated: Boolean get() = sessionCookies.isNotEmpty()

    @Throws(IOException::class)
    fun login(username: String, password: String) {
        // Hipótese de trabalho: hash MD5 simples do password, sem chave pública do servidor —
        // ver nota de classe acima. Username enviado em claro, como convenção comum nesta geração
        // de firmware doméstico (form login HTML tradicional).
        val passwordHash = TplinkC20AuthCrypto.md5Hex(password)
        val requestBody = "username=${urlEncode(username)}&password=${urlEncode(passwordHash)}"

        val response = transport.post("$baseUrl/cgi/login", requestBody)

        val bodyLooksSuccessful = response.statusCode == 200 &&
            !response.body.contains("error", ignoreCase = true) &&
            !response.body.contains("fail", ignoreCase = true)

        if (bodyLooksSuccessful && response.cookies.isNotEmpty()) {
            sessionCookies = response.cookies
            return
        }

        throw TplinkC20LoginException(
            classifyFailure(response.statusCode, response.body),
            "login falhou: status=${response.statusCode}",
        )
    }

    @Throws(IOException::class)
    fun fetchAuthenticated(path: String): String {
        check(isAuthenticated) { "fetchAuthenticated chamado antes de login() bem-sucedido" }
        return transport.get("$baseUrl$path", sessionHeaders()).body
    }

    private fun sessionHeaders(): Map<String, String> {
        if (sessionCookies.isEmpty()) return emptyMap()
        return mapOf("Cookie" to sessionCookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
    }

    private fun classifyFailure(statusCode: Int, body: String): TplinkC20LoginFailureReason = when {
        statusCode == 401 || statusCode == 403 -> TplinkC20LoginFailureReason.INVALID_CREDENTIALS
        body.contains("error", ignoreCase = true) || body.contains("fail", ignoreCase = true) ->
            TplinkC20LoginFailureReason.INVALID_CREDENTIALS
        body.isBlank() -> TplinkC20LoginFailureReason.UNEXPECTED_RESPONSE
        else -> TplinkC20LoginFailureReason.UNKNOWN
    }

    private fun urlEncode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name())
}

/** MD5 do lado do cliente para o campo de senha — hipótese de trabalho, ver nota de classe acima. */
internal object TplinkC20AuthCrypto {
    fun md5Hex(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
