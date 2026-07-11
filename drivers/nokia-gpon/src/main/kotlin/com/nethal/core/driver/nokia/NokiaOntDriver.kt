package com.nethal.core.driver.nokia

import com.nethal.core.protocol.PrivateIpRanges
import com.nethal.core.driver.NetworkFailureReason
import com.nethal.core.driver.RetryOutcome
import com.nethal.core.driver.classifyNetworkFailure
import com.nethal.core.driver.executeWithRetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Motivo de falha do driver após esgotar as tentativas de retry — vocabulário para a UI decidir a mensagem. */
internal enum class NokiaDriverFailureReason {
    DEVICE_UNREACHABLE,
    TIMEOUT,
    UNEXPECTED_RESPONSE,
    INVALID_CREDENTIALS,
    SESSION_IN_USE,
    COMMUNICATION_ERROR,
}

internal sealed interface NokiaDriverResult {
    data class Success(val snapshot: NokiaDriverSnapshot) : NokiaDriverResult
    data class Failure(val reason: NokiaDriverFailureReason, val message: String) : NokiaDriverResult
}

/**
 * Driver de leitura do Nokia G-1425G-B (profile `nokia_g1425gb_v1`). Orquestra login + os 4
 * endpoints somente-leitura, com o mesmo retry/backoff do driver de produção do SignallQ (3
 * tentativas, backoff 1s/2s). Sem nenhuma ação de escrita — mesma regra do produto irmão e do
 * princípio "read-only primeiro" do NetHAL.
 *
 * A credencial passada a [readSnapshot] nunca é retida por esta classe além da chamada de login:
 * o [NokiaAuthenticationClient] guarda só o `sid` resultante, em memória, pelo tempo de vida da
 * instância — nunca persistida em disco, nunca enviada à nuvem, nunca logada.
 */
internal class NokiaOntDriver(
    private val host: String,
    private val transport: NokiaHttpTransport = DefaultNokiaHttpTransport(),
    private val maxAttempts: Int = 3,
    private val backoffMillis: (attempt: Int) -> Long = { attempt -> 1_000L * attempt },
) {
    /**
     * Guarda de SSRF/credencial (mesma classe de risco que a Marisa já apontou em
     * `UpnpIgdProbe` e `HttpFingerprintProbe`, mas aqui o risco é maior: este driver envia a
     * credencial real do usuário, cifrada com a chave pública que o *próprio host* devolve. Se
     * `host` não for validado, um host malicioso/público poderia devolver sua própria chave RSA
     * e receber a credencial do usuário cifrada para si mesmo — phishing de credencial, não só
     * requisição indevida. Falha rápido, sem tentar login, quando o host não é RFC 1918.
     */
    init {
        require(PrivateIpRanges.isPrivate(host)) {
            "NokiaOntDriver só opera contra IP privado (RFC 1918) da LAN; host recebido: $host"
        }
    }

    suspend fun readSnapshot(username: String, password: String): NokiaDriverResult = withContext(Dispatchers.IO) {
        val outcome = executeWithRetry(
            maxAttempts = maxAttempts,
            backoffMillis = backoffMillis,
            loginExceptionType = NokiaLoginException::class.java,
            onLoginFailure = { e ->
                // Falha de credencial ou sessão em uso não se resolve por retry — falha rápido,
                // sem gastar as 3 tentativas à toa (token expirado é a única exceção que se
                // beneficia de retry, porque a próxima tentativa recaptura nonce/csrf do zero).
                when (e.reason) {
                    NokiaLoginFailureReason.INVALID_CREDENTIALS -> NokiaDriverFailureReason.INVALID_CREDENTIALS
                    NokiaLoginFailureReason.SESSION_IN_USE -> NokiaDriverFailureReason.SESSION_IN_USE
                    NokiaLoginFailureReason.TOKEN_EXPIRED, NokiaLoginFailureReason.UNKNOWN -> null
                }
            },
            classifyFinalFailure = ::classifyFailure,
        ) {
            val client = NokiaAuthenticationClient(host, transport)
            client.login(username, password)

            val gponHtml = client.fetchAuthenticated("/wan_status.cgi?gpon")
            val wanHtml = client.fetchAuthenticated("/show_wan_status.cgi?ipv4")
            val pppJson = client.fetchAuthenticated("/index.cgi?getppp")
            val deviceJson = client.fetchAuthenticated("/device_status.cgi")
            val homeNetworkingHtml = client.fetchAuthenticated("/lan_status.cgi?wlan")

            NokiaDriverSnapshot(
                gpon = NokiaResponseParser.parseGponStatus(gponHtml),
                wan = NokiaResponseParser.parseWanStatus(wanHtml),
                ppp = NokiaResponseParser.parsePppStatus(pppJson),
                deviceInfo = NokiaResponseParser.parseDeviceInfo(deviceJson),
                connectedClients = NokiaResponseParser.parseConnectedClients(homeNetworkingHtml),
                loginPageEvidence = client.loginPageEvidence,
            )
        }

        when (outcome) {
            is RetryOutcome.Success -> NokiaDriverResult.Success(outcome.value)
            is RetryOutcome.Failure -> NokiaDriverResult.Failure(outcome.reason, outcome.error.message ?: outcome.error.toString())
        }
    }

    /**
     * Classifica o erro final: primeiro checagens específicas do handshake RSA+AES deste driver
     * (pubkey/nonce/csrf ausentes na página de login), depois a classificação de rede genérica
     * compartilhada.
     */
    private fun classifyFailure(error: Throwable): NokiaDriverFailureReason = when {
        error.message?.contains("pubkey") == true ||
            error.message?.contains("nonce") == true ||
            error.message?.contains("csrf") == true -> NokiaDriverFailureReason.UNEXPECTED_RESPONSE
        else -> when (classifyNetworkFailure(error)) {
            NetworkFailureReason.DEVICE_UNREACHABLE -> NokiaDriverFailureReason.DEVICE_UNREACHABLE
            NetworkFailureReason.TIMEOUT -> NokiaDriverFailureReason.TIMEOUT
            NetworkFailureReason.UNEXPECTED_RESPONSE -> NokiaDriverFailureReason.UNEXPECTED_RESPONSE
            NetworkFailureReason.COMMUNICATION_ERROR -> NokiaDriverFailureReason.COMMUNICATION_ERROR
        }
    }
}
