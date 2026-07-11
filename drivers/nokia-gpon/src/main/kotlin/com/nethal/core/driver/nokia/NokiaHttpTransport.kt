package com.nethal.core.driver.nokia

import com.nethal.core.protocol.http.DefaultHttpTransport
import com.nethal.core.protocol.http.HttpTransportConfig
import com.nethal.core.protocol.http.HttpTransportResponse
import java.io.IOException

internal typealias NokiaHttpResponse = HttpTransportResponse

/**
 * Transporte HTTP do driver Nokia, isolado atrás de interface para permitir testes determinísticos
 * com fakes (o `core` é JVM puro, sem hardware real disponível neste ambiente — ver testes em
 * `NokiaAuthenticationClientTest`). `DefaultNokiaHttpTransport` é a implementação real, equivalente
 * em timeouts/retries/redirects ao driver de produção do SignallQ.
 *
 * Delega para o [com.nethal.core.protocol.http.HttpTransport] compartilhado
 * (`core/protocol/http/HttpTransport.kt`) — este tipo continua existindo só para preservar a
 * assinatura já consumida por `NokiaAuthenticationClient` e pelo fake de teste.
 */
internal interface NokiaHttpTransport {
    @Throws(IOException::class)
    fun get(url: String, extraHeaders: Map<String, String> = emptyMap()): NokiaHttpResponse

    @Throws(IOException::class)
    fun post(url: String, body: String, initCookies: Map<String, String> = emptyMap()): NokiaHttpResponse
}

/**
 * Timeouts equivalentes ao driver de produção do SignallQ (evidência real de campo, ver
 * fingerprintEvidence do profile `nokia_g1425gb_v1`): connect 15s, read 30s em GET / 60s em POST.
 */
internal class DefaultNokiaHttpTransport(
    connectTimeoutMillis: Int = 15_000,
    getReadTimeoutMillis: Int = 30_000,
    postReadTimeoutMillis: Int = 60_000,
) : NokiaHttpTransport {

    private val delegate = DefaultHttpTransport(
        HttpTransportConfig(
            connectTimeoutMillis = connectTimeoutMillis,
            getReadTimeoutMillis = getReadTimeoutMillis,
            postReadTimeoutMillis = postReadTimeoutMillis,
            getAcceptHeader = "text/html,application/xhtml+xml,*/*;q=0.9",
            postAcceptHeader = "*/*",
            postContentType = "application/x-www-form-urlencoded; charset=UTF-8",
            extraPostHeaders = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Connection" to "close",
            ),
            followRedirectsManually = true,
            maxRedirectHops = 5,
        ),
    )

    override fun get(url: String, extraHeaders: Map<String, String>): NokiaHttpResponse =
        delegate.get(url, extraHeaders)

    override fun post(url: String, body: String, initCookies: Map<String, String>): NokiaHttpResponse =
        delegate.post(url, body, cookies = initCookies)
}
