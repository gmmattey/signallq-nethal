package com.nethal.core.driver.tplink

import com.nethal.core.protocol.PrivateIpRanges
import com.nethal.core.driver.NetworkFailureReason
import com.nethal.core.driver.RetryOutcome
import com.nethal.core.driver.classifyNetworkFailure
import com.nethal.core.driver.executeWithRetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Motivo de falha do driver após esgotar as tentativas — vocabulário para a UI decidir a mensagem. */
internal enum class TplinkDriverFailureReason {
    DEVICE_UNREACHABLE,
    TIMEOUT,
    UNEXPECTED_RESPONSE,
    INVALID_CREDENTIALS,
    SESSION_IN_USE,
    COMMUNICATION_ERROR,
}

internal sealed interface TplinkDriverResult {
    data class Success(val snapshot: TplinkDriverSnapshot) : TplinkDriverResult
    data class Failure(val reason: TplinkDriverFailureReason, val message: String) : TplinkDriverResult
}

/**
 * Driver de leitura do TP-Link Archer C6 (profile `tplink_archer_c6_v1`). Orquestra login +
 * endpoints somente-leitura. Retry deliberadamente mais conservador que o Nokia (no máximo 2
 * tentativas, não 3): a WebUI TP-Link aceita só uma sessão simultânea (ver `session_behavior` no
 * catálogo), então retentativas agressivas tendem a colidir com a própria sessão anterior ainda
 * não expirada, piorando a situação em vez de resolver.
 *
 * A credencial passada a [readSnapshot] nunca é retida por esta classe além da chamada de login:
 * [TplinkAuthenticationClient] guarda só os cookies de sessão resultantes, em memória, pelo tempo
 * de vida da instância — nunca persistidos em disco, nunca enviados à nuvem, nunca logados.
 */
internal class TplinkOntDriver(
    private val host: String,
    private val transport: TplinkHttpTransport = DefaultTplinkHttpTransport(),
    private val cipherVariant: TplinkCipherVariant = TplinkCipherVariant.AES_CBC,
    private val maxAttempts: Int = 2,
    private val backoffMillis: (attempt: Int) -> Long = { attempt -> 1_000L * attempt },
) {
    /**
     * Guarda de SSRF/credencial obrigatória, mesma classe de risco documentada em
     * `NokiaOntDriver`: o handshake "web encrypted password" cifra a credencial do usuário com a
     * chave pública que o próprio host devolve. Sem esta checagem, um host malicioso/público
     * poderia devolver sua própria chave RSA e coletar a credencial do usuário cifrada para si —
     * phishing de credencial, não só requisição indevida. Falha rápido, sem tentar login, quando
     * o host não é RFC 1918.
     */
    init {
        require(PrivateIpRanges.isPrivate(host)) {
            "TplinkOntDriver só opera contra IP privado (RFC 1918) da LAN; host recebido: $host"
        }
    }

    suspend fun readSnapshot(username: String, password: String): TplinkDriverResult = withContext(Dispatchers.IO) {
        val outcome = executeWithRetry(
            maxAttempts = maxAttempts,
            backoffMillis = backoffMillis,
            loginExceptionType = TplinkLoginException::class.java,
            onLoginFailure = { e ->
                // Falha de credencial ou sessão em uso não se resolve por retry — falha rápido,
                // preservando a única retentativa disponível para erro de comunicação transitório.
                when (e.reason) {
                    TplinkLoginFailureReason.INVALID_CREDENTIALS -> TplinkDriverFailureReason.INVALID_CREDENTIALS
                    TplinkLoginFailureReason.SESSION_IN_USE -> TplinkDriverFailureReason.SESSION_IN_USE
                    TplinkLoginFailureReason.UNEXPECTED_RESPONSE, TplinkLoginFailureReason.UNKNOWN -> null
                }
            },
            classifyFinalFailure = ::classifyFailure,
        ) {
            val client = TplinkAuthenticationClient(host, transport, cipherVariant)
            client.login(username, password)

            val deviceInfoJson = client.fetchAuthenticated("/cgi/getDeviceInfo")
            val wanJson = client.fetchAuthenticated("/cgi/getWanStatus")
            val wifiJson = client.fetchAuthenticated("/cgi/getWifiStatus")
            val clientsJson = client.fetchAuthenticated("/cgi/getConnectedClients")

            TplinkDriverSnapshot(
                deviceInfo = TplinkResponseParser.parseDeviceInfo(deviceInfoJson),
                wan = TplinkResponseParser.parseWanStatus(wanJson),
                wifi = TplinkResponseParser.parseWifiStatus(wifiJson),
                connectedClients = TplinkResponseParser.parseConnectedClients(clientsJson),
            )
        }

        when (outcome) {
            is RetryOutcome.Success -> TplinkDriverResult.Success(outcome.value)
            is RetryOutcome.Failure -> TplinkDriverResult.Failure(outcome.reason, outcome.error.message ?: outcome.error.toString())
        }
    }

    /**
     * Classifica o erro final (após esgotar tentativas, ou `UNEXPECTED_RESPONSE`/`UNKNOWN` de
     * login que não se beneficia de fast-fail): primeiro checagens específicas do handshake
     * RSA+AES deste driver, depois a classificação de rede genérica compartilhada.
     */
    private fun classifyFailure(error: Throwable): TplinkDriverFailureReason = when {
        error.message?.contains("getParm") == true ||
            error.message?.contains("RSA") == true -> TplinkDriverFailureReason.UNEXPECTED_RESPONSE
        else -> when (classifyNetworkFailure(error)) {
            NetworkFailureReason.DEVICE_UNREACHABLE -> TplinkDriverFailureReason.DEVICE_UNREACHABLE
            NetworkFailureReason.TIMEOUT -> TplinkDriverFailureReason.TIMEOUT
            NetworkFailureReason.UNEXPECTED_RESPONSE -> TplinkDriverFailureReason.UNEXPECTED_RESPONSE
            NetworkFailureReason.COMMUNICATION_ERROR -> TplinkDriverFailureReason.COMMUNICATION_ERROR
        }
    }
}
