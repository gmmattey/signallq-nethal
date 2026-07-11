package com.nethal.core.driver.tplink

/**
 * Fake determinístico de [TplinkHttpTransport] — não há hardware real disponível neste ambiente,
 * então os testes do cliente/driver TP-Link validam o protocolo (handshake, marcadores de
 * sucesso/erro) contra respostas fabricadas, não contra a unidade física. Isso fica como próximo
 * passo do Luiz (ver `tplinkManualCheck`).
 */
internal class FakeTplinkHttpTransport(
    private val getParmResponse: TplinkHttpResponse,
    private val loginResponses: MutableList<TplinkHttpResponse>,
    private val authenticatedPages: Map<String, String> = emptyMap(),
) : TplinkHttpTransport {

    var getCallCount = 0
        private set
    var postCallCount = 0
        private set

    override fun get(url: String, extraHeaders: Map<String, String>): TplinkHttpResponse {
        getCallCount++
        for ((path, body) in authenticatedPages) {
            if (url.endsWith(path)) return TplinkHttpResponse(200, body, emptyMap(), emptyMap())
        }
        return TplinkHttpResponse(404, "", emptyMap(), emptyMap())
    }

    override fun post(url: String, body: String, cookies: Map<String, String>): TplinkHttpResponse {
        postCallCount++
        if (url.endsWith("/cgi/getParm")) return getParmResponse
        check(loginResponses.isNotEmpty()) { "FakeTplinkHttpTransport: nenhuma resposta de login configurada para esta chamada" }
        return loginResponses.removeAt(0)
    }
}

/** Módulo/expoente RSA de teste (1024 bits, valores fixos — não é chave real de produção). */
internal fun sampleGetParmResponse(sequence: Long = 123456789L): TplinkHttpResponse {
    val keyPair = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
    val publicKey = keyPair.public as java.security.interfaces.RSAPublicKey
    val modulusHex = publicKey.modulus.toString(16)
    val exponentHex = publicKey.publicExponent.toString(16)
    return TplinkHttpResponse(
        statusCode = 200,
        body = "var ee=\"$exponentHex\";\nvar nn=\"$modulusHex\";\nvar seq=\"$sequence\";\n",
        headers = emptyMap(),
        cookies = emptyMap(),
    )
}

internal fun successfulTplinkLoginResponse(sessionId: String = "sess-abc123"): TplinkHttpResponse = TplinkHttpResponse(
    statusCode = 200,
    body = "[error]0\r\n\$.ret=0\r\n",
    headers = emptyMap(),
    cookies = mapOf("JSESSIONID" to sessionId),
)

internal fun invalidCredentialsTplinkResponse(): TplinkHttpResponse = TplinkHttpResponse(
    statusCode = 401,
    body = "[error]-3\r\n",
    headers = emptyMap(),
    cookies = emptyMap(),
)

internal fun sessionInUseTplinkResponse(): TplinkHttpResponse = TplinkHttpResponse(
    statusCode = 200,
    body = "session is busy, try again later\r\n",
    headers = emptyMap(),
    cookies = emptyMap(),
)
