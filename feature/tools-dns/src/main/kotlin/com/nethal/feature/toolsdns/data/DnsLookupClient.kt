package com.nethal.feature.toolsdns.data

import com.nethal.core.model.DnsLookupOutcome
import com.nethal.core.model.DnsLookupResult
import com.nethal.core.model.DnsRecordAnswer
import com.nethal.core.model.DnsRecordType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Executa uma consulta DNS Lookup para [hostname], resolvendo tipo A e AAAA. */
fun interface DnsLookupClient {
    suspend fun lookup(hostname: String): DnsLookupOutcome
}

private const val CONNECT_TIMEOUT_MILLIS = 4_000
private const val READ_TIMEOUT_MILLIS = 8_000

/**
 * Implementação DNS-over-HTTPS (RFC 8484), extensão de `BenchmarkDnsDoh.kt`
 * (`SignallQ/android/feature/dns`, issue #101) para o caso de uso "DNS Lookup": aquela origem só
 * media tempo de resposta e descartava o corpo da resposta — aqui o corpo é parseado e os
 * registros resolvidos são o resultado principal, latência é dado secundário.
 *
 * ## Decisão registrada (PR #93/#101): DoH fixo (Cloudflare), não DNS do sistema
 *
 * O protótipo `4e` (`docs/design/prototypes.dc.html`) mostra um campo "Servidor DNS" com um IP
 * concreto — para reportar isso com honestidade, a consulta precisa saber com certeza contra qual
 * servidor resolveu. `InetAddress.getByName` (DNS do sistema) não expõe qual resolvedor respondeu
 * sem depender de `ConnectivityManager`/`LinkProperties`, e mesmo assim o servidor exposto ali
 * normalmente é o roteador local (proxy DNS da rede), não um resolvedor público — não é o dado que
 * o protótipo pede. DoH contra um provedor fixo conhecido resolve isso sem ambiguidade, e também
 * garante consulta explícita por tipo (A/AAAA), independente de o aparelho ter rota IPv6 ativa.
 *
 * Único provedor (Cloudflare) nesta rodada — o protótipo não desenha seletor de provedor, só um
 * resultado. Suporte a múltiplos provedores fixos do SignallQ (Google, Quad9 etc., como no
 * benchmark de origem) fica para uma issue futura de UX, se a Vera desenhar um seletor.
 */
class CloudflareDohDnsLookupClient(
    private val baseUrl: String = "https://cloudflare-dns.com/dns-query",
    override val serverLabel: String = "Cloudflare (1.1.1.1)",
) : DnsLookupClient, DnsLookupServerAware {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun lookup(hostname: String): DnsLookupOutcome {
        val trimmed = hostname.trim()
        if (trimmed.isEmpty()) {
            return DnsLookupOutcome.Failure(trimmed, "Informe um hostname para consultar.")
        }

        return withContext(Dispatchers.IO) {
            val start = System.nanoTime()
            val perType = DnsRecordType.entries.map { type ->
                async { type to queryRecord(trimmed, type) }
            }.awaitAll()
            val elapsedMillis = (System.nanoTime() - start) / 1_000_000

            when {
                perType.all { (_, result) -> result is RecordQueryResult.NxDomain } ->
                    DnsLookupOutcome.Failure(trimmed, "Domínio não encontrado (NXDOMAIN) em $serverLabel.")

                perType.all { (_, result) -> result is RecordQueryResult.QueryFailure } ->
                    DnsLookupOutcome.Failure(
                        trimmed,
                        "Falha ao consultar $serverLabel: " +
                            (perType.first().second as RecordQueryResult.QueryFailure).reason,
                    )

                else -> DnsLookupOutcome.Success(
                    DnsLookupResult(
                        hostname = trimmed,
                        answers = perType.map { (type, result) ->
                            DnsRecordAnswer(
                                type = type,
                                values = (result as? RecordQueryResult.Success)?.values ?: emptyList(),
                            )
                        },
                        serverLabel = serverLabel,
                        elapsedMillis = elapsedMillis,
                    ),
                )
            }
        }
    }

    private fun queryRecord(hostname: String, type: DnsRecordType): RecordQueryResult {
        return try {
            val encodedHost = URLEncoder.encode(hostname, Charsets.UTF_8.name())
            val url = URL("$baseUrl?name=$encodedHost&type=${type.name}")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MILLIS
                readTimeout = READ_TIMEOUT_MILLIS
                requestMethod = "GET"
                setRequestProperty("accept", "application/dns-json")
            }
            val code = connection.responseCode
            val bodyBytes = try {
                if (code in 200..299) {
                    connection.inputStream.use { it.readBytes() }
                } else {
                    connection.errorStream?.use { it.readBytes() }
                }
            } finally {
                connection.disconnect()
            }
            val body = bodyBytes?.toString(Charsets.UTF_8)

            if (code !in 200..299 || body.isNullOrBlank()) {
                return RecordQueryResult.QueryFailure("HTTP $code")
            }

            val parsed = json.decodeFromString<DohResponseWire>(body)
            when (parsed.status) {
                DOH_STATUS_NOERROR -> RecordQueryResult.Success(
                    parsed.answer
                        .filter { it.type == type.wireTypeCode }
                        .map { it.data },
                )
                DOH_STATUS_NXDOMAIN -> RecordQueryResult.NxDomain
                else -> RecordQueryResult.QueryFailure("resposta DNS com status=${parsed.status}")
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Exception) {
            RecordQueryResult.QueryFailure(t.message ?: t::class.simpleName.orEmpty())
        }
    }

    private companion object {
        const val DOH_STATUS_NOERROR = 0
        const val DOH_STATUS_NXDOMAIN = 3
    }
}

/** Exposto para a UI mostrar qual servidor foi consultado sem acoplar em [CloudflareDohDnsLookupClient] especificamente. */
interface DnsLookupServerAware {
    val serverLabel: String
}

private sealed interface RecordQueryResult {
    data class Success(val values: List<String>) : RecordQueryResult
    data object NxDomain : RecordQueryResult
    data class QueryFailure(val reason: String) : RecordQueryResult
}

@Serializable
private data class DohResponseWire(
    @SerialName("Status") val status: Int = -1,
    @SerialName("Answer") val answer: List<DohAnswerWire> = emptyList(),
)

@Serializable
private data class DohAnswerWire(
    @SerialName("type") val type: Int = 0,
    @SerialName("data") val data: String = "",
)
