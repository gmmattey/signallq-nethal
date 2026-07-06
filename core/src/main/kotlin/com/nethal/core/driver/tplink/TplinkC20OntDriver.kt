package com.nethal.core.driver.tplink

import com.nethal.core.discovery.PrivateIpRanges
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.SocketTimeoutException

/** Motivo de falha do driver após esgotar as tentativas — vocabulário para a UI decidir a mensagem. */
internal enum class TplinkC20DriverFailureReason {
    DEVICE_UNREACHABLE,
    TIMEOUT,
    UNEXPECTED_RESPONSE,
    INVALID_CREDENTIALS,
    COMMUNICATION_ERROR,
}

internal sealed interface TplinkC20DriverResult {
    data class Success(val snapshot: TplinkC20DriverSnapshot) : TplinkC20DriverResult
    data class Failure(val reason: TplinkC20DriverFailureReason, val message: String) : TplinkC20DriverResult
}

/**
 * Driver de leitura do TP-Link Archer C20 (profile `tplink_archer_c20_v1`). Orquestra login +
 * endpoints somente-leitura.
 *
 * Este driver existe separado de `TplinkOntDriver` (Archer C6) porque os dois modelos, embora do
 * mesmo fabricante, pertencem a gerações de firmware/hardware diferentes e não são
 * intercambiáveis: o C6 (AC1200, linha mais recente) usa o handshake "web encrypted password"
 * RSA+AES via `/cgi_gdpr`; o C20 (AC750, linha mais antiga/básica) não tem essa rota — foi
 * justamente a tentativa de usar o driver do C6 contra um C20 físico que expôs o erro "expoente
 * RSA (ee) não encontrado na resposta de getParm" (equipamento do Luiz informado por engano como
 * C6, na verdade é um C20). Ver `TplinkC20AuthenticationClient` para o mecanismo assumido
 * (especulativo) para este modelo.
 *
 * Retry conservador (no máximo 2 tentativas), mesma razão do C6: a WebUI TP-Link tende a aceitar
 * só uma sessão simultânea, então retentativas agressivas arriscam colidir com sessão anterior
 * ainda não expirada.
 *
 * A credencial passada a [readSnapshot] nunca é retida por esta classe além da chamada de login.
 */
internal class TplinkC20OntDriver(
    private val host: String,
    private val transport: TplinkHttpTransport = DefaultTplinkHttpTransport(),
    private val maxAttempts: Int = 2,
    private val backoffMillis: (attempt: Int) -> Long = { attempt -> 1_000L * attempt },
) {
    /**
     * Guarda de SSRF obrigatória, mesma classe de risco documentada em `TplinkOntDriver`/
     * `NokiaOntDriver`: falha rápido, sem tentar login, quando o host não é RFC 1918.
     */
    init {
        require(PrivateIpRanges.isPrivate(host)) {
            "TplinkC20OntDriver só opera contra IP privado (RFC 1918) da LAN; host recebido: $host"
        }
    }

    suspend fun readSnapshot(username: String, password: String): TplinkC20DriverResult = withContext(Dispatchers.IO) {
        var lastError: Throwable? = null

        repeat(maxAttempts) { attemptIndex ->
            if (attemptIndex > 0) delay(backoffMillis(attemptIndex))
            try {
                val client = TplinkC20AuthenticationClient(host, transport)
                client.login(username, password)

                val deviceInfoJson = client.fetchAuthenticated("/cgi/getDeviceInfo")
                val wanJson = client.fetchAuthenticated("/cgi/getWanStatus")
                val wifiJson = client.fetchAuthenticated("/cgi/getWifiStatus")
                val clientsJson = client.fetchAuthenticated("/cgi/getConnectedClients")

                return@withContext TplinkC20DriverResult.Success(
                    TplinkC20DriverSnapshot(
                        deviceInfo = TplinkC20ResponseParser.parseDeviceInfo(deviceInfoJson),
                        wan = TplinkC20ResponseParser.parseWanStatus(wanJson),
                        wifi = TplinkC20ResponseParser.parseWifiStatus(wifiJson),
                        connectedClients = TplinkC20ResponseParser.parseConnectedClients(clientsJson),
                    ),
                )
            } catch (e: TplinkC20LoginException) {
                when (e.reason) {
                    TplinkC20LoginFailureReason.INVALID_CREDENTIALS ->
                        return@withContext TplinkC20DriverResult.Failure(TplinkC20DriverFailureReason.INVALID_CREDENTIALS, e.message.orEmpty())
                    TplinkC20LoginFailureReason.UNEXPECTED_RESPONSE, TplinkC20LoginFailureReason.UNKNOWN ->
                        lastError = e
                }
            } catch (t: Throwable) {
                lastError = t
            }
        }

        val error = lastError ?: return@withContext TplinkC20DriverResult.Failure(
            TplinkC20DriverFailureReason.COMMUNICATION_ERROR,
            "falha desconhecida apos $maxAttempts tentativas",
        )
        TplinkC20DriverResult.Failure(classifyFailure(error), error.message ?: error.toString())
    }

    private fun classifyFailure(error: Throwable): TplinkC20DriverFailureReason = when {
        error is ConnectException -> TplinkC20DriverFailureReason.DEVICE_UNREACHABLE
        error is SocketTimeoutException -> TplinkC20DriverFailureReason.TIMEOUT
        error.message?.contains("timed out", ignoreCase = true) == true -> TplinkC20DriverFailureReason.TIMEOUT
        error.message?.contains("refused", ignoreCase = true) == true -> TplinkC20DriverFailureReason.DEVICE_UNREACHABLE
        else -> TplinkC20DriverFailureReason.COMMUNICATION_ERROR
    }
}
