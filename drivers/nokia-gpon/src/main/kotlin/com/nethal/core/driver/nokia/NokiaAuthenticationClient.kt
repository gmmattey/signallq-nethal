package com.nethal.core.driver.nokia

import com.nethal.core.auth.AuthenticationStrategy
import java.io.IOException
import java.net.URLEncoder

/**
 * Motivo da falha de login sinalizado pelo firmware via `err_t` no corpo da resposta.
 * Vocabulário fechado porque só estes 3 valores foram observados no driver Nokia de produção do
 * SignallQ (produto irmão) — qualquer outro valor de `err_t`, ou ausência dele, cai em [UNKNOWN].
 */
internal enum class NokiaLoginFailureReason {
    SESSION_IN_USE, // err_t=0
    INVALID_CREDENTIALS, // err_t=1
    TOKEN_EXPIRED, // err_t=2 — nonce/csrf ficaram velhos, retry do zero resolve
    UNKNOWN,
}

internal class NokiaLoginException(
    val reason: NokiaLoginFailureReason,
    message: String,
) : IOException(message)

/** Estado de sessão pós-login: `sid` (sessão atual), `lsid` (sessão legada) e idioma da UI. */
internal data class NokiaSession(
    val sessionId: String,
    val legacySessionId: String,
    val language: String,
)

/**
 * Sessão autenticada contra um Nokia G-1425G-B. A credencial (`username`/`password`) só existe
 * como parâmetro de `login()` e como variável local durante o handshake — nunca é armazenada em
 * campo desta classe nem logada, conforme regra de segurança do NetHAL (CLAUDE.md/SECURITY.md).
 * Só o identificador de sessão (`sid`) resultante do login fica retido, e apenas em memória.
 *
 * Handshake replicado (lendo o código do driver de produção do SignallQ como referência, não
 * copiado literalmente — ver NokiaAuthCrypto para o porquê de cada escolha de encoding/padding):
 * 1. GET na raiz extrai `pubkey` (RSA), `nonce` e `csrf_token` do HTML embarcado.
 * 2. Gera 4 blocos aleatórios de 16 bytes: aesKey/iv (cifram o payload) e decKey/decIv (chaves de
 *    sessão internas, enviadas cifradas dentro do próprio payload).
 * 3. Payload = "&username=...&password=...&csrf_token=...&nonce=...&enckey=...&enciv=...",
 *    cifrado com AES-CBC/ISO7816-4 → `ct`; `"b64(aesKey) b64(iv)"` cifrado com RSA-PKCS1v1.5 → `ck`.
 * 4. POST /login.cgi com `encrypted=1&ct=...&ck=...`. Sessão vem em header `X-SID` ou cookie `sid`.
 */
internal class NokiaAuthenticationClient(
    private val host: String,
    private val transport: NokiaHttpTransport = DefaultNokiaHttpTransport(),
    private val clock: () -> Long = System::currentTimeMillis,
) : AuthenticationStrategy<NokiaSession> {
    private val baseUrl = "http://$host"

    private var sessionId: String = ""
    private var legacySessionId: String = ""
    private var language: String = "eng"

    /**
     * Evidência de fingerprint passivo (título HTML, header `Server`) capturada do mesmo GET na
     * raiz que `login()` já faz para extrair `pubkey`/`nonce`/`csrf_token` — nenhuma chamada de
     * rede extra. Fica populada após uma tentativa de `login()`, mesmo que ela falhe depois
     * (o GET da página de login acontece antes da tentativa de autenticação em si).
     */
    var loginPageEvidence: NokiaLoginPageEvidence? = null
        private set

    val isAuthenticated: Boolean get() = sessionId.isNotEmpty()

    @Throws(IOException::class)
    override fun login(username: String, password: String): NokiaSession {
        val loginPage = transport.get("$baseUrl/?t=${clock()}&lang=eng")
        val html = loginPage.body

        loginPageEvidence = NokiaLoginPageEvidence(
            httpTitle = extractLoginPageTitle(html),
            serverHeader = loginPage.headers["server"],
        )

        val publicKeyBase64 = NokiaAuthCrypto.extractPublicKeyBase64(html)
            ?: throw IOException("pubkey nao encontrado na pagina de login")
        val nonce = NokiaAuthCrypto.extractNonce(html)
            ?: throw IOException("nonce nao encontrado na pagina de login")
        val csrfToken = NokiaAuthCrypto.extractCsrfToken(html)
            ?: throw IOException("csrf_token nao encontrado na pagina de login")

        val aesKey = NokiaAuthCrypto.generateSecureBytes(16)
        val iv = NokiaAuthCrypto.generateSecureBytes(16)
        val sessionEncKey = NokiaAuthCrypto.generateSecureBytes(16)
        val sessionEncIv = NokiaAuthCrypto.generateSecureBytes(16)

        val aesKeyBase64 = NokiaAuthCrypto.base64Encode(aesKey)
        val ivBase64 = NokiaAuthCrypto.base64Encode(iv)

        val plainPayload = "&username=$username" +
            "&password=${URLEncoder.encode(password, "UTF-8")}" +
            "&csrf_token=$csrfToken" +
            "&nonce=$nonce" +
            "&enckey=${NokiaAuthCrypto.base64UrlEscape(sessionEncKey)}" +
            "&enciv=${NokiaAuthCrypto.base64UrlEscape(sessionEncIv)}"

        val ct = NokiaAuthCrypto.base64UrlNoPad(
            NokiaAuthCrypto.aesCbcEncryptIso7816(aesKey, iv, plainPayload.toByteArray(Charsets.UTF_8)),
        )
        val ck = NokiaAuthCrypto.base64UrlEscape(
            NokiaAuthCrypto.rsaEncryptPkcs1(publicKeyBase64, "$aesKeyBase64 $ivBase64"),
        )

        val response = transport.post("$baseUrl/login.cgi", "encrypted=1&ct=$ct&ck=$ck", loginPage.cookies)

        val headerSessionId = response.headers["x-sid"]?.trim().orEmpty()
        val cookieSessionId = response.cookies["sid"]?.trim().orEmpty()
        val resolvedSessionId = cookieSessionId.ifEmpty { headerSessionId }

        if ((response.statusCode == 299 || response.statusCode == 200) && resolvedSessionId.isNotEmpty()) {
            sessionId = resolvedSessionId
            legacySessionId = response.cookies["lsid"]?.trim().orEmpty()
            language = response.cookies["lang"]?.trim()?.ifEmpty { "eng" } ?: "eng"
            return NokiaSession(sessionId, legacySessionId, language)
        }

        val errT = Regex("""err_t\s*=\s*\[([^\]]*)]""").find(response.body)?.groupValues?.get(1)?.trim()
        val reason = when (errT) {
            "0" -> NokiaLoginFailureReason.SESSION_IN_USE
            "1" -> NokiaLoginFailureReason.INVALID_CREDENTIALS
            "2" -> NokiaLoginFailureReason.TOKEN_EXPIRED
            else -> NokiaLoginFailureReason.UNKNOWN
        }
        throw NokiaLoginException(reason, "login falhou: status=${response.statusCode} err_t=$errT")
    }

    @Throws(IOException::class)
    fun fetchAuthenticated(path: String): String {
        check(isAuthenticated) { "fetchAuthenticated chamado antes de login() bem-sucedido" }
        return transport.get("$baseUrl$path", authenticatedHeaders(NokiaSession(sessionId, legacySessionId, language))).body
    }

    override fun authenticatedHeaders(session: NokiaSession): Map<String, String> {
        val cookieParts = mutableListOf("lang=${session.language}")
        if (session.sessionId.isNotEmpty()) cookieParts.add(0, "sid=${session.sessionId}")
        if (session.legacySessionId.isNotEmpty()) cookieParts.add("lsid=${session.legacySessionId}")
        return mapOf(
            "Cookie" to cookieParts.joinToString("; "),
            "X-SID" to session.sessionId,
            "Referer" to "$baseUrl/",
            "X-Requested-With" to "XMLHttpRequest",
        )
    }
}

/**
 * Equivalente local a `extractHtmlTitle` de `HttpFingerprintProbe.kt` (pacote `fingerprint`,
 * `internal` lá e portanto não visível aqui) — mesma regex, evitando acoplar o driver Nokia a um
 * pacote de probe de discovery só por causa de uma função utilitária de uma linha.
 */
private val LOGIN_PAGE_TITLE_REGEX = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

private fun extractLoginPageTitle(html: String): String? =
    LOGIN_PAGE_TITLE_REGEX.find(html)?.groupValues?.get(1)?.trim()?.takeIf(String::isNotEmpty)
