package com.nethal.core.protocol.http

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Testes de regressão de [DefaultHttpTransport] contra um servidor HTTP local real (JDK
 * `com.sun.net.httpserver.HttpServer`, sem dependência nova), não contra hardware físico — não
 * temos como martelar a unidade Nokia real do Luiz para investigar isto (ver
 * `docs/drivers/compatibility-catalog.md`, changelog 2026-07-08, "terceira execução real do
 * `nokiaManualCheck`"). O servidor local reproduz exatamente o padrão observado num probe real
 * contra o Nokia G-1425G-B: `/device_status.cgi` sem sessão respondeu
 * `Set-Cookie: sid=deleted; lsid=deleted; expires=...; path=/;` — um único header `Set-Cookie` com
 * vários cookies dentro, em vez de um header por cookie (RFC 6265 recomenda o segundo formato, mas
 * não proíbe o primeiro).
 */
class DefaultHttpTransportTest {

    private lateinit var server: HttpServer

    private val transport = DefaultHttpTransport(
        HttpTransportConfig(
            connectTimeoutMillis = 5_000,
            getReadTimeoutMillis = 5_000,
            getAcceptHeader = "*/*",
            postAcceptHeader = "*/*",
            postContentType = "application/x-www-form-urlencoded",
        ),
    )

    @Before
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
    }

    @After
    fun stopServer() {
        server.stop(0)
    }

    private fun baseUrl(): String = "http://127.0.0.1:${server.address.port}"

    @Test
    fun `parseCookies captures every cookie packed into a single Set-Cookie header - reproduz o firmware Nokia`() {
        server.createContext("/login") { exchange ->
            exchange.responseHeaders.add(
                "Set-Cookie",
                "sid=abc123sessionid; lsid=legacy-abc123; lang=eng; path=/",
            )
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.close()
        }

        val response = transport.post("${baseUrl()}/login", body = "")

        assertEquals("abc123sessionid", response.cookies["sid"])
        assertEquals("legacy-abc123", response.cookies["lsid"])
        assertEquals("eng", response.cookies["lang"])
        assertNull("atributo de cookie 'path' nunca deve virar uma entrada do mapa de cookies", response.cookies["path"])
    }

    @Test
    fun `parseCookies still works when the server sends one Set-Cookie header per cookie - caso padrao RFC 6265`() {
        server.createContext("/login") { exchange ->
            exchange.responseHeaders.add("Set-Cookie", "sid=abc123sessionid; Path=/; HttpOnly")
            exchange.responseHeaders.add("Set-Cookie", "lsid=legacy-abc123; Path=/")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.close()
        }

        val response = transport.post("${baseUrl()}/login", body = "")

        assertEquals("abc123sessionid", response.cookies["sid"])
        assertEquals("legacy-abc123", response.cookies["lsid"])
    }

    @Test
    fun `parseCookies matches the exact 'deleted' pattern observed live against the real Nokia G-1425G-B`() {
        // Probe passivo real (sem sessão) contra /device_status.cgi devolveu literalmente este
        // header — ver docs/drivers/compatibility-catalog.md, knownFirmwareBugs de
        // nokia_g1425gb_v1 (manifesto catalog-2026.07.22.json).
        server.createContext("/device_status.cgi") { exchange ->
            exchange.responseHeaders.add(
                "Set-Cookie",
                "sid=deleted; lsid=deleted; expires=Thu,  01-Jan-1970 00:00:01 GMT; path=/;",
            )
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.close()
        }

        val response = transport.get("${baseUrl()}/device_status.cgi")

        assertEquals("deleted", response.cookies["sid"])
        assertEquals("deleted", response.cookies["lsid"])
        assertNull(response.cookies["expires"])
        assertNull(response.cookies["path"])
    }

    @Test
    fun `post() correctly reports statusCode 299 and its Set-Cookie, not just 200-range success`() {
        // NOKIA_GPON_FIELD_MAP.md (levantamento irmão no SignallQ, reaproveitado como referência,
        // não copiado literalmente) documenta que /login.cgi responde com o código HTTP não-padrão
        // 299 (não 200) em caso de sucesso, junto de X-SID e Set-Cookie sid/lsid. HttpURLConnection
        // decide entre inputStream/errorStream por faixa de código (>= 400 aqui, ver readResponse),
        // não por uma lista fechada de códigos "conhecidos" — este teste confirma que 299 realmente
        // chega inteiro em `HttpTransportResponse.statusCode`, com headers/cookies intactos, e não
        // é silenciosamente convertido/perdido em algum lugar do transporte.
        server.createContext("/login") { exchange ->
            exchange.responseHeaders.add("X-SID", "sess-299")
            exchange.responseHeaders.add(
                "Set-Cookie",
                "sid=sess-299; lsid=legacy-sess-299; lang=eng; path=/",
            )
            exchange.sendResponseHeaders(299, 0)
            exchange.responseBody.close()
        }

        val response = transport.post("${baseUrl()}/login", body = "encrypted=1&ct=x&ck=y")

        assertEquals(299, response.statusCode)
        assertEquals("sess-299", response.headers["x-sid"])
        assertEquals("sess-299", response.cookies["sid"])
        assertEquals("legacy-sess-299", response.cookies["lsid"])
    }

    @Test
    fun `get() sends an explicit Cookie header from extraHeaders to the server`() {
        val receivedCookieHeader = CompletableFuture<String?>()
        server.createContext("/device_status.cgi") { exchange ->
            receivedCookieHeader.complete(exchange.requestHeaders.getFirst("Cookie"))
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.close()
        }

        transport.get(
            "${baseUrl()}/device_status.cgi",
            extraHeaders = mapOf("Cookie" to "sid=abc123sessionid; lsid=legacy-abc123; lang=eng"),
        )

        assertEquals(
            "sid=abc123sessionid; lsid=legacy-abc123; lang=eng",
            receivedCookieHeader.get(5, TimeUnit.SECONDS),
        )
    }

    @Test
    fun `get() rejects a public ip before opening any connection - guard obrigatorio da issue 55`() {
        try {
            transport.get("http://8.8.8.8/device_status.cgi")
            fail("esperava IOException do guard de IP privado")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("8.8.8.8"))
        }
    }

    @Test
    fun `post() rejects a public ip before opening any connection - guard obrigatorio da issue 55`() {
        try {
            transport.post("http://201.17.45.90/login", body = "")
            fail("esperava IOException do guard de IP privado")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("201.17.45.90"))
        }
    }

    @Test
    fun `get() rejects a malformed url instead of leaking a raw parse exception`() {
        try {
            transport.get("not a url")
            fail("esperava IOException do guard de IP privado")
        } catch (e: IOException) {
            // ok - URL malformada deve virar IOException, não uma exceção não tratada
        }
    }

    @Test
    fun `loopback used by the local test harness still goes through - guard nao regride os testes existentes`() {
        server.createContext("/ok") { exchange ->
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.close()
        }

        val response = transport.get("${baseUrl()}/ok")

        assertEquals(200, response.statusCode)
    }
}
