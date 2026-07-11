package com.nethal.core.driver.family.tplink.xdrds

import com.nethal.core.auth.AuthenticationStrategy
import com.nethal.core.driver.family.tplink.gdprcgi.TpLinkGdprCgiCrypto
import com.nethal.core.protocol.http.HttpTransport
import java.io.IOException

internal enum class TpLinkXdrDsLoginFailureReason {
    AUTH_ENDPOINT_UNAVAILABLE,
    INVALID_CREDENTIALS,
    UNEXPECTED_RESPONSE,
}

internal class TpLinkXdrDsLoginException(
    val reason: TpLinkXdrDsLoginFailureReason,
    message: String,
) : IOException(message)

internal class TpLinkXdrDsAuthenticationClient(
    private val host: String,
    private val config: TpLinkXdrDsDriverConfig,
    private val transport: HttpTransport,
) : AuthenticationStrategy<TpLinkXdrDsSession> {

    private val baseUrl = "http://$host"
    private var session: TpLinkXdrDsSession? = null

    override fun login(username: String, password: String): TpLinkXdrDsSession {
        val encryptInfoPayload = """{"method":"do","user_management":{"get_encrypt_info":null}}"""
        val probeResponse = transport.post(
            url = resolveUrl(config.encryptInfoPath),
            body = encryptInfoPayload,
            extraHeaders = jsonHeaders(),
        )
        val (useNonceVariant, nonce) = TpLinkXdrDsResponseParser.parseNonceLoginVariant(probeResponse.body)
        val loginPayload = if (useNonceVariant && nonce != null) {
            """{"method":"do","login":{"password":"${TpLinkGdprCgiCrypto.md5Hex("$password:$nonce")}","encrypt_type":"3"}}"""
        } else {
            """{"method":"do","login":{"password":"${securityEncode(password)}"}}"""
        }
        val loginResponse = transport.post(
            url = resolveUrl(config.loginPath),
            body = loginPayload,
            extraHeaders = jsonHeaders(),
        )
        if (loginResponse.statusCode == 401 || loginResponse.statusCode == 403) {
            throw TpLinkXdrDsLoginException(
                TpLinkXdrDsLoginFailureReason.INVALID_CREDENTIALS,
                "login XDR /ds falhou: status=${loginResponse.statusCode}",
            )
        }
        if (loginResponse.statusCode != 200) {
            throw TpLinkXdrDsLoginException(
                TpLinkXdrDsLoginFailureReason.UNEXPECTED_RESPONSE,
                "login XDR /ds falhou: status=${loginResponse.statusCode}",
            )
        }
        val stok = TpLinkXdrDsResponseParser.parseStok(loginResponse.body)
            ?: throw TpLinkXdrDsLoginException(
                TpLinkXdrDsLoginFailureReason.UNEXPECTED_RESPONSE,
                "resposta XDR /ds sem stok reconhecivel",
            )
        return TpLinkXdrDsSession(stok).also { session = it }
    }

    override fun authenticatedHeaders(session: TpLinkXdrDsSession): Map<String, String> = jsonHeaders()

    fun fetchAuthenticatedRaw(payloadJson: String): String {
        val currentSession = checkNotNull(session) { "fetchAuthenticatedRaw chamado antes de login()" }
        val response = transport.post(
            url = resolveUrl(config.authenticatedPathTemplate.replace("{stok}", currentSession.stok)),
            body = payloadJson,
            extraHeaders = authenticatedHeaders(currentSession),
        )
        if (response.statusCode != 200) {
            throw TpLinkXdrDsLoginException(
                TpLinkXdrDsLoginFailureReason.UNEXPECTED_RESPONSE,
                "chamada autenticada XDR /ds falhou: status=${response.statusCode}",
            )
        }
        return response.body
    }

    private fun jsonHeaders(): Map<String, String> = mapOf("Content-Type" to "application/json")

    private fun resolveUrl(path: String): String =
        if (path.startsWith("http://") || path.startsWith("https://")) path
        else "$baseUrl/${path.trimStart('/')}"

    private fun securityEncode(password: String): String {
        val data2 = "RDpbLfCPsJZ7fiv"
        val dict = "yLwVl0zKqws7LgKPRQ84Mdt708T1qQ3Ha7xv3H7NyU84p21BriUWBU43odz3iP4rBL3cD02KZciXTysVXiV8ngg6vL48rPJyAUw0HurW20xqxv9aYb4M9wK1Ae0wlro510qXeU07kV57fQMc8L6aLgMLwygtc0F10a0Dg70TOoouyFhdysuRMO51yY5ZlOZZLEal1h0t9YQW0Ko7oBwmCAHoic4HYbUyVeU3sfQ1xtXcPcf1aT303wAQhv66qzW"
        val builder = StringBuilder()
        val max = maxOf(password.length, data2.length)
        repeat(max) { index ->
            var a = 187
            var b = 187
            if (index >= password.length) {
                a = data2[index].code
            } else if (index >= data2.length) {
                b = password[index].code
            } else {
                a = data2[index].code
                b = password[index].code
            }
            builder.append(dict[(b xor a) % dict.length])
        }
        return builder.toString()
    }
}
