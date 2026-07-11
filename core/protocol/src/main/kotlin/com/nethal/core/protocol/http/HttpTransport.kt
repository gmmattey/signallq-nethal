package com.nethal.core.protocol.http

import com.nethal.core.protocol.PrivateIpRanges
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

private const val DEFAULT_USER_AGENT =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

/** Nomes de atributo de cookie (RFC 6265), nunca o nome de um cookie de verdade — usado por [DefaultHttpTransport.parseCookies]. */
private val COOKIE_ATTRIBUTE_NAMES = setOf(
    "path", "domain", "expires", "max-age", "secure", "httponly", "samesite",
)

/** Resposta HTTP crua, comum a qualquer Driver Family que fale HTTP (ver `docs/architecture/hal-layering-model.md` §5.5). */
data class HttpTransportResponse(
    val statusCode: Int,
    val body: String,
    val headers: Map<String, String>,
    val cookies: Map<String, String>,
)

/**
 * Transporte HTTP compartilhado por qualquer Driver Family baseada em `HttpURLConnection` — extraído
 * de `TplinkHttpTransport`/`NokiaHttpTransport`, que eram ~90% o mesmo código (ver
 * `docs/architecture/hal-layering-model.md` §3.2). As diferenças reais entre os dois (timeouts,
 * Content-Type default do POST, headers extras fixos e se segue redirect manualmente) viram
 * parâmetros de [HttpTransportConfig], não reimplementação.
 */
interface HttpTransport {
    @Throws(IOException::class)
    fun get(url: String, extraHeaders: Map<String, String> = emptyMap()): HttpTransportResponse

    @Throws(IOException::class)
    fun post(
        url: String,
        body: String,
        cookies: Map<String, String> = emptyMap(),
        extraHeaders: Map<String, String> = emptyMap(),
    ): HttpTransportResponse
}

/**
 * Parâmetros que hoje divergem entre TP-Link e Nokia, hardcoded em cada cópia. Nenhum default aqui
 * importa por si — cada driver continua fixando os próprios valores explicitamente ao construir o
 * transporte (ver `TplinkHttpTransport.kt`/`NokiaHttpTransport.kt`).
 *
 * @param followRedirectsManually replica o comportamento do Nokia: em vez de deixar o
 *   `HttpURLConnection` seguir redirect sozinho, acumula cookies entre hops e refaz o GET manualmente
 *   até 5 vezes (`maxRedirectHops`). TP-Link não segue redirect (`instanceFollowRedirects = false`
 *   e nenhum loop) — hoje isso só se aplica ao `get`, pois nenhum dos dois drivers precisa seguir
 *   redirect em `post`.
 */
data class HttpTransportConfig(
    val connectTimeoutMillis: Int,
    val getReadTimeoutMillis: Int,
    val postReadTimeoutMillis: Int = getReadTimeoutMillis,
    val userAgent: String = DEFAULT_USER_AGENT,
    val getAcceptHeader: String,
    val postAcceptHeader: String,
    val postContentType: String,
    val extraGetHeaders: Map<String, String> = emptyMap(),
    val extraPostHeaders: Map<String, String> = emptyMap(),
    val postRefererProvider: ((String) -> String)? = null,
    val followRedirectsManually: Boolean = false,
    val maxRedirectHops: Int = 5,
)

/**
 * Guard obrigatório de IP privado para qualquer requisição que saia de [DefaultHttpTransport] —
 * defesa em profundidade citada na issue #55: o Network Security Config libera cleartext HTTP
 * globalmente (NSC não suporta CIDR), então a restrição real a "só fala com a LAN local" precisa
 * ser aplicada aqui em runtime, não deixada como responsabilidade de cada chamador.
 *
 * Aceita RFC 1918 (via [PrivateIpRanges], mesma faixa usada no guard de SSRF do
 * `UpnpIgdProbe.isLanUrl`) **mais loopback** (`127.0.0.0/8`). Isso é deliberadamente mais
 * permissivo que [PrivateIpRanges], que exclui loopback por design (ver KDoc de
 * `PrivateIpRanges`) — mas a ameaça ali é outra: aquele guard filtra URL fornecida por um
 * dispositivo não confiável (redirect SSDP/descriptor), enquanto este filtra o destino de
 * qualquer chamada feita pelo próprio app. Loopback nunca sai da máquina, então não amplia
 * superfície de ataque nenhuma — e é o que o harness de teste local (`DefaultHttpTransportTest`,
 * `com.sun.net.httpserver`) usa para simular um equipamento real sem hardware físico.
 *
 * Só aceita IP literal (IPv4 de 4 octetos) — hostname (exigiria resolução DNS) e IPv6 (não
 * suportado por [PrivateIpRanges]) são rejeitados por padrão, falha segura.
 *
 * Público (não `internal`) para reaproveito fora deste arquivo — é o mesmo padrão de guard que a
 * futura capability de Verificação de porta (issues #100/#94) deve consultar, recebendo só
 * IP/host, sem depender de montar uma URL.
 */
object HttpTransportIpGuard {

    fun isAllowedHost(host: String): Boolean = PrivateIpRanges.isPrivate(host) || isLoopback(host)

    private fun isLoopback(ip: String): Boolean {
        val octets = ip.trim().split(".").mapNotNull { it.toIntOrNull() }
        if (octets.size != 4 || octets.any { it !in 0..255 }) return false
        return octets[0] == 127
    }
}

/**
 * Implementação real de [HttpTransport] via `HttpURLConnection`. Todo o comportamento observável
 * (timeouts, headers, redirect) é decidido por [config] — a lógica de leitura/parsing de resposta e
 * cookie é a única parte de fato compartilhada entre TP-Link e Nokia.
 */
class DefaultHttpTransport(private val config: HttpTransportConfig) : HttpTransport {

    override fun get(url: String, extraHeaders: Map<String, String>): HttpTransportResponse {
        if (!config.followRedirectsManually) {
            return performGet(url, extraHeaders, emptyMap())
        }
        return getFollowingRedirectsManually(url, extraHeaders)
    }

    private fun getFollowingRedirectsManually(url: String, extraHeaders: Map<String, String>): HttpTransportResponse {
        val accumulatedCookies = mutableMapOf<String, String>()
        var currentUrl = url
        var hops = 0

        while (hops < config.maxRedirectHops) {
            val response = performGet(currentUrl, extraHeaders, accumulatedCookies)
            accumulatedCookies.putAll(response.cookies)

            if (response.statusCode in 301..303 || response.statusCode == 307 || response.statusCode == 308) {
                val location = response.headers["location"] ?: break
                currentUrl = if (location.startsWith("http")) location else {
                    val base = URL(currentUrl)
                    "${base.protocol}://${base.host}${portSuffix(base)}$location"
                }
                hops++
                continue
            }
            return response.copy(cookies = accumulatedCookies)
        }
        throw IOException("demasiados redirecionamentos ao acessar $url")
    }

    private fun performGet(
        url: String,
        extraHeaders: Map<String, String>,
        cookiesToSend: Map<String, String>,
    ): HttpTransportResponse {
        requireAllowedHost(url)
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = config.connectTimeoutMillis
            readTimeout = config.getReadTimeoutMillis
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", config.userAgent)
            setRequestProperty("Accept", config.getAcceptHeader)
            if (cookiesToSend.isNotEmpty()) {
                setRequestProperty("Cookie", cookiesToSend.entries.joinToString("; ") { "${it.key}=${it.value}" })
            }
            config.extraGetHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
            extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        return readResponse(connection)
    }

    override fun post(
        url: String,
        body: String,
        cookies: Map<String, String>,
        extraHeaders: Map<String, String>,
    ): HttpTransportResponse {
        requireAllowedHost(url)
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = config.connectTimeoutMillis
            readTimeout = config.postReadTimeoutMillis
            instanceFollowRedirects = false
            doOutput = true
            setRequestProperty("Content-Type", config.postContentType)
            setRequestProperty("User-Agent", config.userAgent)
            setRequestProperty("Accept", config.postAcceptHeader)
            config.postRefererProvider?.let { provider ->
                setRequestProperty("Referer", provider(url))
            }
            config.extraPostHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
            if (cookies.isNotEmpty()) {
                setRequestProperty("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
            }
            extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
            setFixedLengthStreamingMode(bodyBytes.size)
            outputStream.write(bodyBytes)
            outputStream.flush()
        }
        return readResponse(connection)
    }

    private fun readResponse(connection: HttpURLConnection): HttpTransportResponse {
        return try {
            val statusCode = connection.responseCode
            val body = (if (statusCode >= 400) connection.errorStream else connection.inputStream)
                ?.bufferedReader(Charsets.UTF_8)?.readText().orEmpty()
            val headers = mutableMapOf<String, String>()
            connection.headerFields.forEach { (key, values) ->
                if (key != null) headers[key.lowercase()] = values.joinToString(", ")
            }
            HttpTransportResponse(statusCode, body, headers, parseCookies(connection))
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Alguns firmwares (confirmado no Nokia G-1425G-B em probe real: `/device_status.cgi` sem
     * sessão respondeu `Set-Cookie: sid=deleted; lsid=deleted; expires=...; path=/;`) empacotam
     * VÁRIOS cookies num único header `Set-Cookie`, em vez de um header por cookie (o padrão RFC
     * 6265). A versão anterior deste parser só olhava o primeiro par `nome=valor` de cada header —
     * cookies além do primeiro (ex.: `lsid` quando `sid` vem antes no mesmo header) eram
     * descartados silenciosamente. Isso quebrava sessões que dependem de mais de um cookie: o
     * cliente reenviava só `sid`, e o firmware rejeitava a leitura autenticada como se não houvesse
     * sessão nenhuma — mesmo o login tendo "funcionado" (retornou `sid` via header `X-SID`).
     */
    private fun parseCookies(connection: HttpURLConnection): Map<String, String> {
        val result = mutableMapOf<String, String>()
        // Nomes de header HTTP são case-insensitive (RFC 7230) — usar a chave exata "Set-Cookie" no
        // Map de connection.headerFields já foi flagrado quebrando contra pelo menos um servidor de
        // teste local que devolve o header com case diferente; busca case-insensitive é a forma
        // correta de qualquer forma, não só um workaround de teste.
        val setCookieValues = connection.headerFields.entries
            .firstOrNull { (key, _) -> key?.equals("Set-Cookie", ignoreCase = true) == true }
            ?.value
            .orEmpty()
        setCookieValues.forEach { headerValue ->
            headerValue.split(";").forEach { segment ->
                val trimmed = segment.trim()
                val eqIndex = trimmed.indexOf('=')
                if (eqIndex > 0) {
                    val name = trimmed.substring(0, eqIndex).trim()
                    if (name.lowercase() !in COOKIE_ATTRIBUTE_NAMES) {
                        result[name] = trimmed.substring(eqIndex + 1).trim()
                    }
                }
            }
        }
        return result
    }

    private fun portSuffix(url: URL): String =
        if (url.port !in listOf(-1, 80, 443)) ":${url.port}" else ""

    private fun requireAllowedHost(url: String) {
        val host = try {
            URL(url).host
        } catch (e: Exception) {
            throw IOException("URL inválida para DefaultHttpTransport: $url", e)
        }
        if (!HttpTransportIpGuard.isAllowedHost(host)) {
            throw IOException(
                "DefaultHttpTransport recusou conexão fora da LAN local (RFC 1918/loopback): host=$host",
            )
        }
    }
}
