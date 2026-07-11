package com.nethal.core.driver.family.tplink.legacycgi

import com.nethal.core.auth.AuthenticationStrategy
import com.nethal.core.driver.tplink.DefaultTplinkHttpTransport
import com.nethal.core.driver.tplink.TplinkHttpTransport
import java.io.IOException
import java.util.Base64

/**
 * Motivo de falha de login da plataforma `tplink-legacy-cgi`. Vocabulário fechado com base no
 * protocolo real confirmado por captura via DevTools contra unidade física do Luiz (2026-07-06,
 * ver SIG-337/SIG-338) — substitui o vocabulário do mecanismo especulativo anterior (MD5+POST,
 * REFUTED por HTTP 500 em teste real).
 */
internal enum class TpLinkLegacyCgiLoginFailureReason {
    INVALID_CREDENTIALS,
    UNEXPECTED_RESPONSE,
    UNKNOWN,

    /**
     * Sinaliza que uma chamada autenticada ([fetchAuthenticated]) pós-login falhou com HTTP 401/403
     * — mesma heurística conservadora de [com.nethal.core.driver.family.tplink.stokluci.TpLinkStokLuciLoginFailureReason.SESSION_EXPIRED],
     * adaptada a este protocolo: aqui não existe token de sessão de fato (a "sessão" é o cookie
     * `Authorization` reenviado a cada chamada), mas um 401/403 numa leitura que veio depois de um
     * `login()` bem-sucedido com o mesmo cookie é o único sinal observável de "a credencial que
     * funcionava parou de funcionar entre chamadas" — distinto de [INVALID_CREDENTIALS] (usado só
     * durante o próprio [TpLinkLegacyCgiAuthenticationClient.login]). Sem confirmação por evidência
     * ao vivo desse cenário específico contra o hardware do Luiz.
     */
    SESSION_EXPIRED,
}

internal class TpLinkLegacyCgiLoginException(
    val reason: TpLinkLegacyCgiLoginFailureReason,
    message: String,
) : IOException(message)

/**
 * Sessão autenticada contra a WebUI de equipamentos da plataforma `tplink-legacy-cgi` (hoje só o
 * profile `tplink_archer_c20_v1`) usando o protocolo real confirmado por captura via DevTools
 * (Network tab) contra a WebUI real da unidade física do Luiz em 2026-07-06 (SIG-337/SIG-338).
 *
 * Movido de `driver/tplink/TplinkC20AuthenticationClient.kt` no passo 4 do plano de refatoração
 * HAL (`docs/architecture/hal-layering-model.md` §10) — mesmo mecanismo, sem mudança de
 * comportamento. Única mudança real: [loginValidationSections] deixou de ser uma constante
 * hardcoded (`LOGIN_VALIDATION_SECTIONS`) e passou a ser recebido como parâmetro, vindo de
 * `profile.driverConfig` (`TpLinkLegacyCgiDriverConfig.loginValidationSections()`) — dado de
 * modelo não pertence ao código da Driver Family (`hal-layering-model.md` §3 item 6).
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
 *   inicial) e checando HTTP 200 + `[error]0` + `modelName=` no corpo.
 * - Duas rodadas de teste real (2026-07-06/07) contra o hardware do Luiz retornaram `[error]71111`
 *   com senha correta, mesmo depois de corrigir o bundle de seções acima. Causa raiz real, achada
 *   comparando um segundo HAR completo (login real, 130 requisições `/cgi`) byte a byte com o que
 *   este driver enviava: [TpLinkLegacyCgiResponseParser.buildRequestBody] usava `\n` como
 *   terminador de linha, mas as 130 requisições reais usam `\r\n` (CRLF) sem exceção — o parser de
 *   linha do firmware provavelmente exige CRLF. Corrigido lá, não aqui.
 * - Essa é uma decisão de design deste driver, não algo capturado literalmente: a captura real não
 *   inclui um caso de credencial inválida (não observamos o que o roteador devolve nesse caso), então
 *   assumimos o padrão HTTP Basic (401 para credencial inválida) e tratamos qualquer corpo sem
 *   `[error]0` ou sem os campos esperados como falha de autenticação/resposta inesperada,
 *   nunca como sucesso silencioso.
 * - Content-Type do POST é `text/plain` (não form-urlencoded, não JSON).
 *
 * A credencial nunca é logada, persistida ou exposta em texto legível: `authorizationCookieValue`
 * guarda só o valor Base64 do cookie em memória durante a vida da instância, nunca aparece em
 * mensagem de exceção nem em `toString()`.
 */
internal class TpLinkLegacyCgiAuthenticationClient(
    private val host: String,
    private val transport: TplinkHttpTransport = DefaultTplinkHttpTransport(),
    /**
     * Bundle de blocos com prova real de sucesso (captura DevTools, tela de Status inicial,
     * 2026-07-06) — usado tanto por [login] quanto pela leitura de device info em
     * [TpLinkLegacyCgiDriverFamily], para nunca divergir do único exemplo comprovado. Vem de
     * `profile.driverConfig` (`TpLinkLegacyCgiDriverConfig.loginValidationSections()`), nunca
     * hardcoded nesta classe (dado de modelo pertence ao Profile, não ao código da Driver Family —
     * `hal-layering-model.md` §3 item 6).
     */
    private val loginValidationSections: List<Pair<String, List<String>>>,
) : AuthenticationStrategy<String> {

    private val baseUrl = "http://$host"

    /** Query string fixa comprovadamente funcional para IGD_DEV_INFO+ETH_SWITCH+SYS_MODE, capturada real. */
    private val cgiEndpoint = "$baseUrl/cgi?1&1&1&8"

    private var authorizationCookieValue: String? = null

    val isAuthenticated: Boolean get() = authorizationCookieValue != null

    /**
     * Valida a credencial fazendo uma primeira leitura real, replicando exatamente
     * [loginValidationSections] (tipicamente IGD_DEV_INFO+ETH_SWITCH+SYS_MODE+/cgi/info — a carga
     * da tela de Status inicial, para o profile do Archer C20). Não existe endpoint de login
     * dedicado neste protocolo — a "autenticação" é implícita em toda chamada de dados via cookie
     * Basic Auth, então login() aqui é, na prática, a primeira chamada autenticada.
     */
    @Throws(IOException::class)
    override fun login(username: String, password: String): String {
        val credentialBase64 = Base64.getEncoder().encodeToString("$username:$password".toByteArray(Charsets.UTF_8))
        val candidateCookie = "Basic $credentialBase64"

        val requestBody = TpLinkLegacyCgiResponseParser.buildRequestBody(loginValidationSections)

        val response = transport.post(cgiEndpoint, requestBody, mapOf("Authorization" to candidateCookie))

        if (response.statusCode == 401 || response.statusCode == 403) {
            throw TpLinkLegacyCgiLoginException(
                TpLinkLegacyCgiLoginFailureReason.INVALID_CREDENTIALS,
                "login falhou: status=${response.statusCode}",
            )
        }
        if (response.statusCode != 200) {
            throw TpLinkLegacyCgiLoginException(
                TpLinkLegacyCgiLoginFailureReason.UNEXPECTED_RESPONSE,
                "login falhou: status=${response.statusCode}",
            )
        }

        val errorCode = TpLinkLegacyCgiResponseParser.extractGlobalErrorCode(response.body)
        when {
            errorCode == 0 && response.body.contains("modelName=") -> {
                authorizationCookieValue = candidateCookie
                return candidateCookie
            }
            errorCode == null -> throw TpLinkLegacyCgiLoginException(
                TpLinkLegacyCgiLoginFailureReason.UNEXPECTED_RESPONSE,
                "login falhou: resposta sem marcador [error] reconhecido",
            )
            errorCode == 0 -> throw TpLinkLegacyCgiLoginException(
                TpLinkLegacyCgiLoginFailureReason.UNEXPECTED_RESPONSE,
                "login falhou: [error]0 mas resposta não contém modelName= (seção ausente/malformada, não é credencial inválida)",
            )
            else -> throw TpLinkLegacyCgiLoginException(
                TpLinkLegacyCgiLoginFailureReason.INVALID_CREDENTIALS,
                "login falhou: [error]$errorCode",
            )
        }
    }

    override fun authenticatedHeaders(session: String): Map<String, String> = mapOf("Authorization" to session)

    /**
     * Faz uma chamada de dados autenticada contra o dispatcher `/cgi`, reenviando o cookie
     * `Authorization` validado por [login]. `requestBody` deve ser montado via
     * [TpLinkLegacyCgiResponseParser.buildRequestBody].
     *
     * HTTP 401/403 aqui é tratado como [TpLinkLegacyCgiLoginFailureReason.SESSION_EXPIRED] — ver
     * KDoc do enum. Antes desta issue, este método nunca checava `statusCode` (só devolvia o corpo),
     * porque nada reaproveitava a sessão entre chamadas para uma expiração ter chance de acontecer.
     */
    @Throws(IOException::class)
    fun fetchAuthenticated(requestBody: String): String {
        val cookieValue = authorizationCookieValue
        check(cookieValue != null) { "fetchAuthenticated chamado antes de login() bem-sucedido" }
        val response = transport.post(cgiEndpoint, requestBody, authenticatedHeaders(cookieValue))

        if (response.statusCode == 401 || response.statusCode == 403) {
            throw TpLinkLegacyCgiLoginException(
                TpLinkLegacyCgiLoginFailureReason.SESSION_EXPIRED,
                "leitura autenticada falhou: status=${response.statusCode} (credencial que funcionava parou de ser aceita)",
            )
        }
        if (response.statusCode != 200) {
            throw TpLinkLegacyCgiLoginException(
                TpLinkLegacyCgiLoginFailureReason.UNEXPECTED_RESPONSE,
                "leitura autenticada falhou: status=${response.statusCode}",
            )
        }

        return response.body
    }
}
