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
 * Driver de leitura do TP-Link Archer C20 (profile `tplink_archer_c20_v1`), usando o protocolo
 * real confirmado por captura via DevTools contra unidade física do Luiz (2026-07-06, ver
 * SIG-337/SIG-338) — substitui a versão anterior baseada na hipótese MD5+POST/JSON (REFUTED por
 * HTTP 500 em teste real).
 *
 * Protocolo real: dispatcher único `POST /cgi?1&1&1&8`, corpo `text/plain` com blocos de seção
 * (`TplinkC20ResponseParser`), autenticado via cookie `Authorization: Basic <base64>` em toda
 * chamada — sem endpoint de login dedicado. Capabilities confirmadas nesta rodada:
 * READ_DEVICE_INFO (IGD_DEV_INFO+ETH_SWITCH+SYS_MODE+/cgi/info — bundle literal comprovado, ver
 * [TplinkC20AuthenticationClient.LOGIN_VALIDATION_SECTIONS]), READ_WIFI_STATUS (LAN_WLAN, parcial:
 * name/SSID, sem bloco de sessão — não presente na captura real desta seção), READ_CONNECTED_CLIENTS
 * (LAN_HOST_ENTRY, idem). READ_WAN_STATUS e READ_FIRMWARE permanecem UNKNOWN — seção real não
 * capturada ainda, não implementadas por hipótese.
 *
 * Continua separado de `TplinkOntDriver` (Archer C6): mesmo fabricante, protocolos totalmente
 * diferentes (o C6 usa handshake RSA+AES "web encrypted password" via `/cgi_gdpr`; o C20 usa
 * dispatcher único com Basic Auth via cookie).
 *
 * Retry conservador (no máximo 2 tentativas): sem handshake de sessão para colidir, mas mantido
 * pela mesma razão de qualquer WebUI doméstica local — retentativas agressivas não ajudam contra
 * falha persistente de credencial/rede.
 *
 * A credencial passada a [readSnapshot] nunca é retida por esta classe além da chamada: apenas o
 * cookie Base64 fica em memória em [TplinkC20AuthenticationClient], nunca persistido, nunca
 * enviado à nuvem, nunca logado.
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

                // Mesmo bundle exato usado por login() (TplinkC20AuthenticationClient.LOGIN_VALIDATION_SECTIONS)
                // — evita divergir do único bundle com prova real de sucesso.
                val deviceInfoBody = client.fetchAuthenticated(
                    TplinkC20ResponseParser.buildRequestBody(TplinkC20AuthenticationClient.LOGIN_VALIDATION_SECTIONS),
                )
                val wifiBody = client.fetchAuthenticated(
                    TplinkC20ResponseParser.buildRequestBody(listOf("LAN_WLAN" to listOf("name", "SSID"))),
                )
                val clientsBody = client.fetchAuthenticated(
                    TplinkC20ResponseParser.buildRequestBody(
                        listOf("LAN_HOST_ENTRY" to listOf("leaseTimeRemaining", "MACAddress", "hostName", "IPAddress")),
                    ),
                )

                return@withContext TplinkC20DriverResult.Success(
                    TplinkC20DriverSnapshot(
                        deviceInfo = TplinkC20ResponseParser.parseDeviceInfo(
                            deviceInfoBody,
                            deviceInfoIndex = 0,
                            ethSwitchIndex = 1,
                            sysModeIndex = 2,
                        ),
                        wifi = TplinkC20ResponseParser.parseWifiStatus(wifiBody, lanWlanIndex = 0),
                        connectedClients = TplinkC20ResponseParser.parseConnectedClients(clientsBody, lanHostEntryIndex = 0),
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
