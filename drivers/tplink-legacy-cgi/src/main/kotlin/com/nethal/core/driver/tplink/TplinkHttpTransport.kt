package com.nethal.core.driver.tplink

import com.nethal.core.protocol.http.DefaultHttpTransport
import com.nethal.core.protocol.http.HttpTransportConfig
import com.nethal.core.protocol.http.HttpTransportResponse
import java.io.IOException
import java.net.URL

internal typealias TplinkHttpResponse = HttpTransportResponse

/**
 * Transporte HTTP do driver TP-Link, isolado atrás de interface para permitir testes
 * determinísticos com fakes — não há hardware real disponível neste ambiente (ver testes em
 * `TplinkAuthenticationClientTest`). `DefaultTplinkHttpTransport` é a implementação real.
 *
 * Delega para o [com.nethal.core.protocol.http.HttpTransport] compartilhado
 * (`core/protocol/http/HttpTransport.kt`) — este tipo continua existindo só para preservar a
 * assinatura já consumida por `TplinkAuthenticationClient`/`TplinkC20AuthenticationClient` e pelos
 * fakes de teste.
 */
internal interface TplinkHttpTransport {
    @Throws(IOException::class)
    fun get(url: String, extraHeaders: Map<String, String> = emptyMap()): TplinkHttpResponse

    @Throws(IOException::class)
    fun post(url: String, body: String, cookies: Map<String, String> = emptyMap()): TplinkHttpResponse
}

/**
 * Timeouts conservadores para WebUI local de roteador doméstico: connect 10s, read 20s. Sem
 * evidência de campo própria ainda (diferente do Nokia) — valores de partida, a confirmar/ajustar
 * no teste real contra a unidade do Luiz.
 */
internal class DefaultTplinkHttpTransport(
    connectTimeoutMillis: Int = 10_000,
    readTimeoutMillis: Int = 20_000,
) : TplinkHttpTransport {

    private val delegate = DefaultHttpTransport(
        HttpTransportConfig(
            connectTimeoutMillis = connectTimeoutMillis,
            getReadTimeoutMillis = readTimeoutMillis,
            postReadTimeoutMillis = readTimeoutMillis,
            getAcceptHeader = "application/json, text/html,*/*;q=0.9",
            postAcceptHeader = "application/json, text/plain, */*",
            postContentType = "text/plain",
            postRefererProvider = { url ->
                val base = URL(url)
                "${base.protocol}://${base.host}${if (base.port !in listOf(-1, 80, 443)) ":${base.port}" else ""}/"
            },
            followRedirectsManually = false,
        ),
    )

    override fun get(url: String, extraHeaders: Map<String, String>): TplinkHttpResponse =
        delegate.get(url, extraHeaders)

    override fun post(url: String, body: String, cookies: Map<String, String>): TplinkHttpResponse =
        delegate.post(url, body, cookies = cookies)
}
