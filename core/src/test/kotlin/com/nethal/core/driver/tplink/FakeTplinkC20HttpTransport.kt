package com.nethal.core.driver.tplink

/**
 * Fake determinístico de [TplinkHttpTransport] para o Archer C20 — não há hardware real
 * disponível neste ambiente, então os testes validam só o protocolo especulativo assumido
 * (POST simples com hash MD5 de senha), não um comportamento confirmado contra a unidade física.
 * Isso fica como próximo passo do Luiz (ver `tplinkC20ManualCheck`).
 */
internal class FakeTplinkC20HttpTransport(
    private val loginResponses: MutableList<TplinkHttpResponse>,
    private val authenticatedPages: Map<String, String> = emptyMap(),
) : TplinkHttpTransport {

    var getCallCount = 0
        private set
    var postCallCount = 0
        private set
    var lastLoginRequestBody: String? = null
        private set

    override fun get(url: String, extraHeaders: Map<String, String>): TplinkHttpResponse {
        getCallCount++
        for ((path, body) in authenticatedPages) {
            if (url.endsWith(path)) return TplinkHttpResponse(200, body, emptyMap(), emptyMap())
        }
        return TplinkHttpResponse(404, "", emptyMap(), emptyMap())
    }

    override fun post(url: String, body: String, initCookies: Map<String, String>): TplinkHttpResponse {
        postCallCount++
        lastLoginRequestBody = body
        check(loginResponses.isNotEmpty()) { "FakeTplinkC20HttpTransport: nenhuma resposta de login configurada para esta chamada" }
        return loginResponses.removeAt(0)
    }
}

internal fun successfulTplinkC20LoginResponse(sessionId: String = "c20-sess-abc"): TplinkHttpResponse = TplinkHttpResponse(
    statusCode = 200,
    body = "ok",
    headers = emptyMap(),
    cookies = mapOf("SESS_ID" to sessionId),
)

internal fun invalidCredentialsTplinkC20Response(): TplinkHttpResponse = TplinkHttpResponse(
    statusCode = 401,
    body = "login error",
    headers = emptyMap(),
    cookies = emptyMap(),
)

internal fun unexpectedEmptyTplinkC20Response(): TplinkHttpResponse = TplinkHttpResponse(
    statusCode = 200,
    body = "",
    headers = emptyMap(),
    cookies = emptyMap(),
)
