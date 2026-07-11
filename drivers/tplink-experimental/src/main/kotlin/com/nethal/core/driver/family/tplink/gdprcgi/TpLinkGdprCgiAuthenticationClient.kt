package com.nethal.core.driver.family.tplink.gdprcgi

import com.nethal.core.auth.AuthenticationStrategy
import com.nethal.core.protocol.http.HttpTransport
import java.io.IOException
import java.net.URLEncoder

internal enum class TpLinkGdprCgiLoginFailureReason {
    AUTH_ENDPOINT_UNAVAILABLE,
    INVALID_CREDENTIALS,
    UNEXPECTED_RESPONSE,
}

internal class TpLinkGdprCgiLoginException(
    val reason: TpLinkGdprCgiLoginFailureReason,
    message: String,
) : IOException(message)

internal class TpLinkGdprCgiAuthenticationClient(
    private val host: String,
    private val config: TpLinkGdprCgiDriverConfig,
    private val transport: HttpTransport,
) : AuthenticationStrategy<TpLinkGdprCgiSession> {

    private val baseUrl = "http://$host"

    private var session: TpLinkGdprCgiSession? = null
    private var encryptionContext: TpLinkGdprCgiEncryptionContext? = null

    override fun login(username: String, password: String): TpLinkGdprCgiSession {
        val rsaResponse = transport.post(resolveUrl(config.rsaKeyPath), body = "")
        if (rsaResponse.statusCode != 200) {
            throw TpLinkGdprCgiLoginException(
                TpLinkGdprCgiLoginFailureReason.AUTH_ENDPOINT_UNAVAILABLE,
                "busca de chave RSA falhou: status=${rsaResponse.statusCode}",
            )
        }
        val rsaParams = TpLinkGdprCgiResponseParser.parseRsaParams(rsaResponse.body)
            ?: throw TpLinkGdprCgiLoginException(
                TpLinkGdprCgiLoginFailureReason.UNEXPECTED_RESPONSE,
                "resposta de chave RSA sem nn/ee/seq reconheciveis",
            )

        val keyAscii = TpLinkGdprCgiCrypto.randomAsciiDigits(16)
        val ivOrNonceAscii = TpLinkGdprCgiCrypto.randomAsciiDigits(
            when (config.cryptoMode) {
                TpLinkGdprCgiCryptoMode.AES_CBC -> 16
                TpLinkGdprCgiCryptoMode.AES_GCM -> 12
            },
        )
        val credentialHash = TpLinkGdprCgiCrypto.md5Hex(username + password)
        val encryption = TpLinkGdprCgiEncryptionContext(
            keyAscii = keyAscii,
            ivOrNonceAscii = ivOrNonceAscii,
            credentialHash = credentialHash,
            rsaParams = rsaParams,
        )

        val loginResponse = when (config.loginStyle) {
            TpLinkGdprCgiLoginStyle.MR_QUERY_LOGIN -> performMrQueryLogin(username, password, encryption)
            TpLinkGdprCgiLoginStyle.C50_GDPR_BODY_LOGIN -> performEncryptedBodyLogin(
                buildC50LoginPlaintext(username, password),
                encryption,
            )
            TpLinkGdprCgiLoginStyle.EX_JSON_GDPR_BODY_LOGIN -> performEncryptedBodyLogin(
                buildExLoginPlaintext(username, password),
                encryption,
            )
        }

        val tokenResponse = transport.get(resolveUrl(config.tokenPath))
        val tokenId = TpLinkGdprCgiResponseParser.parseTokenId(tokenResponse.body)
            ?: throw TpLinkGdprCgiLoginException(
                TpLinkGdprCgiLoginFailureReason.UNEXPECTED_RESPONSE,
                "token da sessao ausente na pagina raiz",
            )

        val newSession = TpLinkGdprCgiSession(tokenId = tokenId, cookies = loginResponse.cookies)
        session = newSession
        encryptionContext = encryption
        return newSession
    }

    override fun authenticatedHeaders(session: TpLinkGdprCgiSession): Map<String, String> =
        mapOf("TokenID" to session.tokenId)

    fun fetchAuthenticatedRaw(path: String, plaintextBody: String): String {
        val currentSession = checkNotNull(session) { "fetchAuthenticatedRaw chamado antes de login()" }
        val currentContext = checkNotNull(encryptionContext) { "contexto criptografico ausente" }
        val requestBody = buildEncryptedRequestBody(
            plaintextBody = plaintextBody,
            encryption = currentContext,
            isLogin = false,
        )
        val response = transport.post(
            resolveUrl(path),
            body = requestBody,
            cookies = currentSession.cookies,
            extraHeaders = authenticatedHeaders(currentSession),
        )
        if (response.statusCode != 200) {
            throw TpLinkGdprCgiLoginException(
                TpLinkGdprCgiLoginFailureReason.UNEXPECTED_RESPONSE,
                "leitura autenticada falhou: status=${response.statusCode}",
            )
        }
        return decryptResponse(response.body, currentContext)
    }

    private fun performMrQueryLogin(
        username: String,
        password: String,
        encryption: TpLinkGdprCgiEncryptionContext,
    ) = transport.post(
        url = buildMrLoginUrl(username, password, encryption),
        body = "",
    ).also { response ->
        val retCode = TpLinkGdprCgiResponseParser.parseReturnCode(response.body)
        if (response.statusCode != 200 || retCode == null) {
            throw TpLinkGdprCgiLoginException(
                TpLinkGdprCgiLoginFailureReason.UNEXPECTED_RESPONSE,
                "login MR falhou: status=${response.statusCode}",
            )
        }
        if (retCode != 0) {
            throw TpLinkGdprCgiLoginException(
                TpLinkGdprCgiLoginFailureReason.INVALID_CREDENTIALS,
                "login MR falhou: ret=$retCode",
            )
        }
    }

    private fun performEncryptedBodyLogin(
        loginPlaintext: String,
        encryption: TpLinkGdprCgiEncryptionContext,
    ) = transport.post(
        url = resolveUrl(config.loginPath),
        body = buildEncryptedRequestBody(loginPlaintext, encryption, isLogin = true),
    ).also { response ->
        if (response.statusCode != 200) {
            throw TpLinkGdprCgiLoginException(
                TpLinkGdprCgiLoginFailureReason.UNEXPECTED_RESPONSE,
                "login GDPR falhou: status=${response.statusCode}",
            )
        }
        val decrypted = decryptResponse(response.body, encryption)
        val retCode = TpLinkGdprCgiResponseParser.parseReturnCode(decrypted)
            ?: throw TpLinkGdprCgiLoginException(
                TpLinkGdprCgiLoginFailureReason.UNEXPECTED_RESPONSE,
                "resposta GDPR sem $.ret reconhecivel",
            )
        if (retCode != 0) {
            throw TpLinkGdprCgiLoginException(
                TpLinkGdprCgiLoginFailureReason.INVALID_CREDENTIALS,
                "login GDPR falhou: ret=$retCode",
            )
        }
    }

    private fun buildMrLoginUrl(
        username: String,
        password: String,
        encryption: TpLinkGdprCgiEncryptionContext,
    ): String {
        val loginPlaintext = "$username\n$password"
        val requestBody = buildEncryptedPayload(loginPlaintext, encryption, isLogin = true)
        val ciphertextParam = URLEncoder.encode(requestBody.data, Charsets.UTF_8)
        return resolveUrl(
            "${config.loginPath}?data=$ciphertextParam&sign=${requestBody.sign}&Action=1&LoginStatus=0&isMobile=0",
        )
    }

    private fun buildEncryptedRequestBody(
        plaintextBody: String,
        encryption: TpLinkGdprCgiEncryptionContext,
        isLogin: Boolean,
    ): String {
        val payload = buildEncryptedPayload(plaintextBody, encryption, isLogin)
        return buildString {
            append("sign=")
            append(payload.sign)
            append("\r\ndata=")
            append(payload.data)
            append("\r\n")
            payload.tag?.let {
                append("tag=")
                append(it)
                append("\r\n")
            }
        }
    }

    private data class EncryptedPayload(
        val sign: String,
        val data: String,
        val tag: String?,
    )

    private fun buildEncryptedPayload(
        plaintextBody: String,
        encryption: TpLinkGdprCgiEncryptionContext,
        isLogin: Boolean,
    ): EncryptedPayload {
        val (data, tag) = when (config.cryptoMode) {
            TpLinkGdprCgiCryptoMode.AES_CBC -> TpLinkGdprCgiCrypto.aesCbcEncrypt(
                encryption.keyAscii,
                encryption.ivOrNonceAscii,
                plaintextBody,
            ) to null
            TpLinkGdprCgiCryptoMode.AES_GCM -> TpLinkGdprCgiCrypto.aesGcmEncrypt(
                encryption.keyAscii,
                encryption.ivOrNonceAscii,
                plaintextBody,
            )
        }
        val signPlaintext = buildSignPlaintext(
            encryption = encryption,
            encryptedDataLength = data.length,
            isLogin = isLogin,
        )
        val sign = TpLinkGdprCgiCrypto.rsaEncryptChunkedToHex(
            modulusHex = encryption.rsaParams.modulusHex,
            exponentHex = encryption.rsaParams.exponentHex,
            plaintext = signPlaintext,
            paddingMode = config.rsaPaddingMode,
        )
        return EncryptedPayload(sign = sign, data = data, tag = tag)
    }

    private fun buildSignPlaintext(
        encryption: TpLinkGdprCgiEncryptionContext,
        encryptedDataLength: Int,
        isLogin: Boolean,
    ): String {
        val sequence = encryption.rsaParams.sequence + encryptedDataLength
        if (!isLogin) return "h=${encryption.credentialHash}&s=$sequence"
        return when (config.cryptoMode) {
            TpLinkGdprCgiCryptoMode.AES_CBC ->
                "key=${encryption.keyAscii}&iv=${encryption.ivOrNonceAscii}&h=${encryption.credentialHash}&s=$sequence"
            TpLinkGdprCgiCryptoMode.AES_GCM -> {
                val keyBase64 = TpLinkGdprCgiCrypto.base64Encode(encryption.keyAscii.toByteArray(Charsets.UTF_8))
                val ivBase64 = TpLinkGdprCgiCrypto.base64Encode(encryption.ivOrNonceAscii.toByteArray(Charsets.UTF_8))
                "key=$keyBase64&iv=$ivBase64&h=${encryption.credentialHash}&s=$sequence"
            }
        }
    }

    private fun decryptResponse(body: String, encryption: TpLinkGdprCgiEncryptionContext): String =
        when (config.cryptoMode) {
            TpLinkGdprCgiCryptoMode.AES_CBC -> TpLinkGdprCgiCrypto.aesCbcDecryptToString(
                encryption.keyAscii,
                encryption.ivOrNonceAscii,
                body,
            )
            TpLinkGdprCgiCryptoMode.AES_GCM -> TpLinkGdprCgiCrypto.aesGcmDecryptCombinedToString(
                encryption.keyAscii,
                encryption.ivOrNonceAscii,
                body,
            )
        }

    private fun buildC50LoginPlaintext(username: String, password: String): String =
        "8\r\n[/cgi/login#0,0,0,0,0,0#0,0,0,0,0,0]0,2\r\nusername=$username\r\npassword=$password\r\n"

    private fun buildExLoginPlaintext(username: String, password: String): String {
        val userBase64 = TpLinkGdprCgiCrypto.base64Encode(username.toByteArray(Charsets.UTF_8))
        val passwordBase64 = TpLinkGdprCgiCrypto.base64Encode(password.toByteArray(Charsets.UTF_8))
        return """{"data":{"UserName":"$userBase64","Passwd":"$passwordBase64","Action":"1","stack":"0,0,0,0,0,0","pstack":"0,0,0,0,0,0"},"operation":"cgi","oid":"/cgi/login"}"""
    }

    private fun resolveUrl(path: String): String =
        if (path.startsWith("http://") || path.startsWith("https://")) path
        else "$baseUrl/${path.trimStart('/')}"
}
