package com.nethal.core.driver.family.tplink.stokluci.tooling

import com.nethal.core.protocol.http.HttpTransport
import com.nethal.core.protocol.http.HttpTransportResponse

/**
 * Decorator de [HttpTransport] usado **só** por [TpLinkStokLuciManualCheck] — nunca injetado no
 * driver de produção ([com.nethal.core.driver.family.tplink.stokluci.TpLinkStokLuciDriverFamily]/
 * [com.nethal.core.driver.family.tplink.stokluci.TpLinkStokLuciAuthenticationClient], que continuam
 * recebendo o `HttpTransport` real direto de quem os constrói — ver
 * `TpLinkStokLuciDriverFamilyFactory.create`). Não existe flag para ligar isto em produção de
 * propósito: é ferramenta de diagnóstico manual, não comportamento do SDK.
 *
 * Issue #125 (2026-07-11, reaberta pós-#141): a correção do contador `seq` não resolveu o 403 em
 * toda leitura autenticada pós-login bem-sucedido. Em vez de tentar mais uma correção às cegas a
 * partir da lib de referência, este decorator loga, para CADA request/response HTTP feito pelo
 * `tplinkC6StokManualCheck` (login E leituras seguintes — é o mesmo [HttpTransport] passado a
 * [com.nethal.core.catalog.DriverFamilyRegistry.resolve] e reusado por toda a sessão):
 *
 * - URL completa (com query string, incluindo `;stok=<token>`)
 * - Método HTTP, headers enviados (`Cookie` reconstruído a partir do parâmetro `cookies`, mais
 *   qualquer `extraHeaders` — `Content-Type`/`User-Agent`/`Accept`/`Referer`/`X-Requested-With` já
 *   são fixados pelo próprio `HttpURLConnection` em [com.nethal.core.protocol.http.DefaultHttpTransport]
 *   e não voltam a este decorator, mas o corpo formatado do request já deixa `sign=`/`data=` visíveis)
 * - Corpo do POST **inteiro** — é seguro logar por completo: `sign=` é a assinatura RSA (hex) do
 *   envelope `k=/i=/h=/s=` e `data=` é o payload AES já cifrado (base64); nenhum dos dois carrega
 *   usuário/senha em texto plano em NENHUMA chamada desta plataforma (login cifra a senha em RSA
 *   antes de entrar no AES; leituras nunca carregam credencial no corpo — ver KDoc de
 *   [com.nethal.core.driver.family.tplink.stokluci.TpLinkStokLuciAuthenticationClient])
 * - Status code da resposta + todos os headers de resposta, com destaque para `Set-Cookie` (se
 *   ausente, loga explicitamente "ausente" — é exatamente o tipo de achado que explicaria o 403
 *   sozinho, ver KDoc de `DefaultHttpTransport.parseCookies`)
 * - Cookies já parseados por [com.nethal.core.protocol.http.DefaultHttpTransport.parseCookies]
 * - Primeiros 200 caracteres do corpo de resposta (mensagem de erro do firmware, se houver, costuma
 *   vir aí mesmo quando o corpo inteiro é maior)
 *
 * Nada aqui grava usuário/senha em claro — só estrutura de request/response e valores já
 * cifrados/derivados (mesma régua de sanitização do resto do `ManualCheck`).
 */
internal class TpLinkStokLuciDiagnosticHttpTransport(
    private val delegate: HttpTransport,
) : HttpTransport {

    private var requestCounter = 0

    override fun get(url: String, extraHeaders: Map<String, String>): HttpTransportResponse {
        val n = ++requestCounter
        logRequest(n, "GET", url, body = null, cookies = emptyMap(), extraHeaders = extraHeaders)
        val response = delegate.get(url, extraHeaders)
        logResponse(n, response)
        return response
    }

    override fun post(
        url: String,
        body: String,
        cookies: Map<String, String>,
        extraHeaders: Map<String, String>,
    ): HttpTransportResponse {
        val n = ++requestCounter
        logRequest(n, "POST", url, body = body, cookies = cookies, extraHeaders = extraHeaders)
        val response = delegate.post(url, body, cookies, extraHeaders)
        logResponse(n, response)
        return response
    }

    private fun logRequest(
        n: Int,
        method: String,
        url: String,
        body: String?,
        cookies: Map<String, String>,
        extraHeaders: Map<String, String>,
    ) {
        println()
        println("[DIAG #$n] --> $method $url")
        if (cookies.isNotEmpty()) {
            println("[DIAG #$n]     Cookie: ${cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }}")
        } else {
            println("[DIAG #$n]     Cookie: (nenhum enviado)")
        }
        extraHeaders.forEach { (k, v) -> println("[DIAG #$n]     $k: $v") }
        if (body != null) {
            println("[DIAG #$n]     body (${body.length} chars - sign=/data= ja cifrados/assinados, nunca credencial em claro):")
            println("[DIAG #$n]       $body")
        }
    }

    private fun logResponse(n: Int, response: HttpTransportResponse) {
        println("[DIAG #$n] <-- status=${response.statusCode}")
        val setCookieHeader = response.headers.entries.firstOrNull { it.key.equals("set-cookie", ignoreCase = true) }
        println("[DIAG #$n]     Set-Cookie (header cru): ${setCookieHeader?.value ?: "(ausente)"}")
        response.headers
            .filterKeys { !it.equals("set-cookie", ignoreCase = true) }
            .forEach { (k, v) -> println("[DIAG #$n]     header $k: $v") }
        println("[DIAG #$n]     cookies parseados (DefaultHttpTransport.parseCookies): ${response.cookies}")
        val preview = response.body.take(200)
        val suffix = if (response.body.length > 200) "... (${response.body.length} chars no total)" else ""
        println("[DIAG #$n]     body, primeiros 200 chars: $preview$suffix")
        println()
    }
}
