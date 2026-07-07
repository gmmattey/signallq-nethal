package com.nethal.core.driver.tplink

import java.io.IOException
import java.util.Base64

/**
 * Motivo de falha de login do Archer C20. Vocabulário fechado com base no protocolo real
 * confirmado por captura via DevTools contra unidade física do Luiz (2026-07-06, ver
 * SIG-337/SIG-338) — substitui o vocabulário do mecanismo especulativo anterior (MD5+POST,
 * REFUTED por HTTP 500 em teste real).
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
 * Sessão autenticada contra a WebUI do TP-Link Archer C20 (profile `tplink_archer_c20_v1`) usando
 * o protocolo real confirmado por captura via DevTools (Network tab) contra a WebUI real da
 * unidade física do Luiz em 2026-07-06 (SIG-337/SIG-338) — não mais o mecanismo especulativo
 * MD5+POST em `/cgi/login` (REFUTED: teste real retornou HTTP 500).
 *
 * Mecanismo real:
 * - Autenticação é **HTTP Basic Auth** (`base64(usuario:senha)`), mas carregada via **cookie**
 *   chamado `Authorization` com valor `Basic <base64>` — não pelo header HTTP `Authorization:`
 *   padrão. A captura real mostrou este cookie sendo enviado em toda requisição de dados.
 * - **Não existe endpoint de login dedicado.** O dispatcher único `POST /cgi?1&1&1&8` (query
 *   string fixa comprovadamente funcional, capturada para a combinação
 *   IGD_DEV_INFO+ETH_SWITCH+SYS_MODE) processa a credencial do cookie a cada chamada.
 * - Como não há um endpoint de login separado, "autenticar" aqui significa **validar a credencial
 *   fazendo uma primeira leitura real**, replicando EXATAMENTE o único bundle de blocos com prova
 *   real de sucesso (`IGD_DEV_INFO+ETH_SWITCH+SYS_MODE+/cgi/info`, a carga da tela de Status
 *   inicial) e checando HTTP 200 + `[error]0` + `modelName=` no corpo. Uma tentativa anterior desta
 *   classe usava um bundle simplificado (só `IGD_DEV_INFO`, sem os outros três blocos) — combinação
 *   nunca provada, e que o teste real contra o hardware do Luiz em 2026-07-06 mostrou falhar com
 *   `[error]71111` (não é erro de credencial: a senha estava correta). Corrigido para replicar o
 *   bundle literal comprovado, sem simplificar ou extrapolar.
 * - Essa é uma decisão de design deste driver, não algo capturado literalmente: a captura real não
 *   inclui um caso de credencial inválida (não observamos o que o roteador devolve nesse caso), então
 *   assumimos o padrão HTTP Basic (401 para credencial inválida) e tratamos qualquer corpo sem
 *   `[error]0` ou sem os campos esperados como falha de autenticação/resposta inesperada,
 *   nunca como sucesso silencioso.
 * - Content-Type do POST é `text/plain` (não form-urlencoded, não JSON).
 *
 * A credencial nunca é logada, persistida ou exposta em texto legível: `sessionCookieValue`
 * guarda só o valor Base64 do cookie em memória durante a vida da instância, nunca aparece em
 * mensagem de exceção nem em `toString()`.
 */
internal class TplinkC20AuthenticationClient(
    private val host: String,
    private val transport: TplinkHttpTransport = DefaultTplinkHttpTransport(),
) {
    companion object {
        /**
         * Bundle de blocos com prova real de sucesso (captura DevTools, tela de Status inicial,
         * 2026-07-06) — usado tanto por [login] quanto pela leitura de device info em
         * `TplinkC20OntDriver`, para nunca divergir do único exemplo comprovado.
         */
        val LOGIN_VALIDATION_SECTIONS: List<Pair<String, List<String>>> = listOf(
            "IGD_DEV_INFO" to listOf("modelName", "description", "X_TP_isFD"),
            "ETH_SWITCH" to listOf("numberOfVirtualPorts"),
            "SYS_MODE" to listOf("mode"),
            "/cgi/info" to emptyList(),
        )
    }

    private val baseUrl = "http://$host"

    /** Query string fixa comprovadamente funcional para IGD_DEV_INFO+ETH_SWITCH+SYS_MODE, capturada real. */
    private val cgiEndpoint = "$baseUrl/cgi?1&1&1&8"

    private var authorizationCookieValue: String? = null

    val isAuthenticated: Boolean get() = authorizationCookieValue != null

    /**
     * Valida a credencial fazendo uma primeira leitura real, replicando exatamente o bundle de
     * blocos comprovado por captura real (IGD_DEV_INFO+ETH_SWITCH+SYS_MODE+/cgi/info — a carga da
     * tela de Status inicial). Não existe endpoint de login dedicado neste protocolo — a
     * "autenticação" é implícita em toda chamada de dados via cookie Basic Auth, então login() aqui
     * é, na prática, a primeira chamada autenticada.
     */
    @Throws(IOException::class)
    fun login(username: String, password: String) {
        val credentialBase64 = Base64.getEncoder().encodeToString("$username:$password".toByteArray(Charsets.UTF_8))
        val candidateCookie = "Basic $credentialBase64"

        val requestBody = TplinkC20ResponseParser.buildRequestBody(LOGIN_VALIDATION_SECTIONS)

        val response = transport.post(cgiEndpoint, requestBody, mapOf("Authorization" to candidateCookie))

        if (response.statusCode == 401 || response.statusCode == 403) {
            throw TplinkC20LoginException(
                TplinkC20LoginFailureReason.INVALID_CREDENTIALS,
                "login falhou: status=${response.statusCode}",
            )
        }
        if (response.statusCode != 200) {
            throw TplinkC20LoginException(
                TplinkC20LoginFailureReason.UNEXPECTED_RESPONSE,
                "login falhou: status=${response.statusCode}",
            )
        }

        val errorCode = TplinkC20ResponseParser.extractGlobalErrorCode(response.body)
        when {
            errorCode == 0 && response.body.contains("modelName=") -> {
                authorizationCookieValue = candidateCookie
                return
            }
            errorCode == null -> throw TplinkC20LoginException(
                TplinkC20LoginFailureReason.UNEXPECTED_RESPONSE,
                "login falhou: resposta sem marcador [error] reconhecido",
            )
            errorCode == 0 -> throw TplinkC20LoginException(
                TplinkC20LoginFailureReason.UNEXPECTED_RESPONSE,
                "login falhou: [error]0 mas resposta não contém modelName= (seção ausente/malformada, não é credencial inválida)",
            )
            else -> throw TplinkC20LoginException(
                TplinkC20LoginFailureReason.INVALID_CREDENTIALS,
                "login falhou: [error]$errorCode",
            )
        }
    }

    /**
     * Faz uma chamada de dados autenticada contra o dispatcher `/cgi`, reenviando o cookie
     * `Authorization` validado por [login]. `requestBody` deve ser montado via
     * [TplinkC20ResponseParser.buildRequestBody].
     */
    @Throws(IOException::class)
    fun fetchAuthenticated(requestBody: String): String {
        val cookieValue = authorizationCookieValue
        check(cookieValue != null) { "fetchAuthenticated chamado antes de login() bem-sucedido" }
        val response = transport.post(cgiEndpoint, requestBody, mapOf("Authorization" to cookieValue))
        return response.body
    }
}
