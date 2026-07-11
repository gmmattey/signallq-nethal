package com.nethal.core.fingerprint

import com.nethal.core.protocol.PrivateIpRanges
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Evidência bruta capturada por um GET passivo contra a raiz/tela de login de um IP candidato.
 * Estritamente leitura: sem autenticação, sem POST, sem uso de `credentialConvention` do
 * catálogo — mesma regra do probe de UPnP (`UpnpIgdProbe`) e da regra explícita de
 * `compatibility-catalog.md` sobre fingerprint passivo.
 */
data class HttpFingerprintEvidence(
    val httpTitle: String?,
    val serverHeader: String?,
    val wwwAuthenticateHeader: String?,
    val statusCode: Int?,
)

interface HttpFingerprintProbe {
    /**
     * Retorna `null` quando o probe falha (timeout, conexão recusada, IP inalcançável) — isso
     * nunca deve lançar exceção para quem chama; ausência de evidência é um resultado válido,
     * não um erro de fluxo.
     */
    suspend fun probe(ip: String, port: Int = 80): HttpFingerprintEvidence?
}

class DefaultHttpFingerprintProbe(
    private val connectTimeoutMillis: Int = 2_000,
    private val readTimeoutMillis: Int = 2_000,
) : HttpFingerprintProbe {

    override suspend fun probe(ip: String, port: Int): HttpFingerprintEvidence? = withContext(Dispatchers.IO) {
        if (!isProbeAllowed(ip)) return@withContext null

        val url = URL("http://$ip:$port/")
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false

            val statusCode = try {
                connection.responseCode
            } catch (_: SecurityException) {
                // Cleartext bloqueado pela plataforma (Network Security Config/OS policy) — não é
                // timeout nem host inalcançável, é a rede recusando HTTP puro para este destino.
                // Contrato inalterado: `probe()` continua retornando `null` em qualquer falha; a
                // distinção existe só para quem lê o código, sem side-effect nem log aqui, para não
                // gerar ruído a cada IP inalcançável/timeout comum durante o discovery.
                null
            } catch (_: Exception) {
                null
            }

            val serverHeader = connection.getHeaderField("Server")
            val wwwAuthenticateHeader = connection.getHeaderField("WWW-Authenticate")

            val body = try {
                val stream = if ((statusCode ?: 0) in 200..299) connection.inputStream else connection.errorStream
                stream?.bufferedReader(Charsets.UTF_8)?.use(BufferedReader::readText)
            } catch (_: Exception) {
                null
            }

            HttpFingerprintEvidence(
                httpTitle = body?.let(::extractHtmlTitle),
                serverHeader = serverHeader,
                wwwAuthenticateHeader = wwwAuthenticateHeader,
                statusCode = statusCode,
            )
        } catch (_: SecurityException) {
            // Mesma distinção do catch interno (cleartext bloqueado pela plataforma) — cobre o caso
            // de a exceção surgir em outro ponto do fluxo (ex.: leitura de header/stream), não só em
            // `responseCode`. Resultado observável idêntico: `null`.
            null
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }
}

private val TITLE_REGEX = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

internal fun extractHtmlTitle(html: String): String? =
    TITLE_REGEX.find(html)?.groupValues?.get(1)?.trim()?.takeIf(String::isNotEmpty)

/**
 * Mitigação de SSRF apontada por Marisa na revisão de segurança da Feat 3: o IP passado a
 * `probe()` pode vir de entrada manual do usuário (Tela 2b/2c), não só do Discovery Engine —
 * sem essa checagem o app faria GET para qualquer host público que o usuário digitasse (por
 * engano ou não). Só permite prosseguir quando o IP é RFC 1918, mesma regra do `UpnpIgdProbe`.
 */
internal fun isProbeAllowed(ip: String): Boolean = PrivateIpRanges.isPrivate(ip)
