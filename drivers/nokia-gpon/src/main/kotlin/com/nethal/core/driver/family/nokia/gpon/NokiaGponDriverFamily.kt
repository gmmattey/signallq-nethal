package com.nethal.core.driver.family.nokia.gpon

import com.nethal.core.catalog.CapabilityReadResult
import com.nethal.core.catalog.CompatibilityProfile
import com.nethal.core.catalog.DriverFamily
import com.nethal.core.catalog.DriverFamilyAuthResult
import com.nethal.core.catalog.DriverFamilyFactory
import com.nethal.core.protocol.PrivateIpRanges
import com.nethal.core.driver.NetworkFailureReason
import com.nethal.core.driver.RetryOutcome
import com.nethal.core.driver.classifyNetworkFailure
import com.nethal.core.driver.executeWithRetry
import com.nethal.core.driver.nokia.NokiaAuthenticationClient
import com.nethal.core.driver.nokia.NokiaDriverFailureReason
import com.nethal.core.driver.nokia.NokiaHttpTransport
import com.nethal.core.driver.nokia.NokiaLoginException
import com.nethal.core.driver.nokia.NokiaLoginFailureReason
import com.nethal.core.driver.nokia.NokiaResponseParser
import com.nethal.core.model.Capability
import com.nethal.core.model.CapabilityId
import com.nethal.core.model.CapabilityPayload
import com.nethal.core.model.CapabilityState
import com.nethal.core.model.ConnectedClient
import com.nethal.core.model.ConnectedClientList
import com.nethal.core.model.DeviceInfo
import com.nethal.core.model.DeviceType
import com.nethal.core.model.SignalStatus
import com.nethal.core.model.WanStatus
import com.nethal.core.protocol.http.HttpTransport
import com.nethal.core.util.PiiHashing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Marcador textual do corpo de resposta que o firmware devolve quando a sessão (`sid`/`lsid`) é
 * rejeitada numa leitura autenticada, em vez de um HTTP 401/403 — evidência ao vivo documentada em
 * `docs/drivers/compatibility-catalog.md` (changelog 2026-07-08, correção de
 * `DefaultHttpTransport.parseCookies`): `<script>var Errorinfo ="Bad request for invalid parameter
 * in the coookie.";window.location.replace('/');</script>`, sempre com HTTP 200. Sem esse
 * marcador, [NokiaGponDriverFamily] não teria como distinguir "sessão rejeitada" de "campo
 * genuinamente ausente na resposta" — o parser tolerante (`NokiaResponseParser`) devolveria `null`
 * para os dois casos.
 */
private fun isSessionRejectedResponse(rawBody: String): Boolean =
    rawBody.contains("Errorinfo") && rawBody.contains("invalid parameter", ignoreCase = true)

/**
 * Driver Family da plataforma `nokia-gpon-rsa-aes` (`platformId`/`driverFamilyId` do catálogo —
 * profile `nokia_g1425gb_v1`, ver `docs/architecture/hal-layering-model.md` §9.1).
 *
 * Migração de `com.nethal.core.driver.nokia.NokiaOntDriver` para o contrato [DriverFamily]
 * (issue #18), no mesmo molde de `TpLinkStokLuciDriverFamily` (issue #16): [authenticate] guarda o
 * [NokiaAuthenticationClient] resultante do login em [authenticatedClient], e [readCapability]
 * reaproveita essa sessão entre chamadas em vez de logar de novo a cada leitura.
 *
 * `NokiaOntDriver.readSnapshot` (pacote `driver.nokia`) **não foi tocado** por esta migração —
 * continua fazendo login novo a cada chamada, é quem `NokiaOntDriverTest` e a evidência ao vivo já
 * validada cobrem, e não há motivo para arriscar o único caminho confirmado contra hardware real.
 * Esta classe é um caminho adicional (sessão persistente + granularidade por capability), não uma
 * substituição — reaproveita o mesmo [NokiaAuthenticationClient]/[NokiaResponseParser], só numa
 * orquestração diferente.
 *
 * Endpoints vêm de `profile.driverConfig` ([NokiaGponDriverConfig]), nunca hardcoded — os mesmos 5
 * caminhos que `NokiaOntDriver.readSnapshot` já usa, só movidos para o catálogo.
 *
 * Nokia G-1425G-B é uma ONT (ponto de entrada GPON da operadora) — não expõe Wi-Fi/LAN própria
 * neste payload de leitura. `READ_WIFI_STATUS`/`READ_LAN_STATUS` devolvem
 * [CapabilityReadResult.Unavailable] honesto e explícito para esse motivo, independente de sessão
 * (é um fato estrutural do equipamento, não uma leitura pendente).
 *
 * Guarda de SSRF obrigatória (RFC 1918) — mesma classe de risco já documentada em `NokiaOntDriver`:
 * a credencial do usuário é cifrada com a chave pública que o próprio host devolve, então um host
 * não-privado poderia phishar a credencial cifrando para a própria chave.
 *
 * Retry conservador (3 tentativas, backoff 1s/2s) — mesmo valor de `NokiaOntDriver`, evidência real
 * documentada no profile (`session_behavior`, `fingerprintEvidence[]`).
 */
internal class NokiaGponDriverFamily(
    private val host: String,
    private val config: NokiaGponDriverConfig,
    private val transport: NokiaHttpTransport,
    private val maxAttempts: Int = 3,
    private val backoffMillis: (attempt: Int) -> Long = { attempt -> 1_000L * attempt },
) : DriverFamily {

    init {
        require(PrivateIpRanges.isPrivate(host)) {
            "NokiaGponDriverFamily só opera contra IP privado (RFC 1918) da LAN; host recebido: $host"
        }
    }

    /**
     * Client autenticado da sessão atual, preenchido por [authenticate] — `null` até a primeira
     * autenticação bem-sucedida (via `CapabilityEngine`) ou depois de uma renovação que falhou.
     * Reaproveitado por [readCapability] entre chamadas, mesmo desenho de
     * `TpLinkStokLuciDriverFamily.authenticatedClient`.
     */
    private var authenticatedClient: NokiaAuthenticationClient? = null

    /**
     * Implementação de [DriverFamily.authenticate] — faz o handshake RSA+AES completo de
     * [NokiaAuthenticationClient.login] e guarda o client resultante em [authenticatedClient] para
     * [readCapability] reaproveitar. Chamar de novo (renovação) sempre substitui a sessão anterior,
     * mesmo em caso de falha (nunca deixa [authenticatedClient] apontando para sessão que pode já
     * ter sido invalidada pelo equipamento).
     */
    override suspend fun authenticate(username: String, password: String): DriverFamilyAuthResult = withContext(Dispatchers.IO) {
        val outcome = executeWithRetry(
            maxAttempts = maxAttempts,
            backoffMillis = backoffMillis,
            loginExceptionType = NokiaLoginException::class.java,
            onLoginFailure = { e ->
                // Mesmo raciocínio de NokiaOntDriver.readSnapshot: credencial errada ou sessão em
                // uso não se resolvem por retry; token expirado se beneficia (recaptura nonce/csrf).
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
            client
        }

        when (outcome) {
            is RetryOutcome.Success -> {
                authenticatedClient = outcome.value
                DriverFamilyAuthResult.Success
            }
            is RetryOutcome.Failure -> {
                authenticatedClient = null
                val message = outcome.error.message ?: outcome.error.toString()
                if (outcome.reason == NokiaDriverFailureReason.INVALID_CREDENTIALS) {
                    DriverFamilyAuthResult.InvalidCredentials(message)
                } else {
                    DriverFamilyAuthResult.Failure(message)
                }
            }
        }
    }

    /**
     * Implementação de [DriverFamily.readCapability] — usa a sessão de [authenticatedClient]
     * (preenchida por [authenticate]) em vez de logar de novo a cada chamada.
     *
     * `READ_WIFI_STATUS`/`READ_LAN_STATUS` são resolvidos antes de checar sessão: Nokia é ONT, a
     * ausência dessas capabilities é um fato do equipamento, não algo que autenticar resolveria.
     * Para as demais, sem sessão ativa a resposta honesta é [CapabilityReadResult.Unavailable] — o
     * caminho normal para abrir/renovar sessão é via `CapabilityEngine.readCapability`, não
     * chamando este método direto sem antes autenticar.
     *
     * Corpo de resposta com sessão rejeitada pelo equipamento (ver [isSessionRejectedResponse])
     * vira [CapabilityReadResult.SessionExpired] — o Capability Engine decide dali se/como renovar.
     */
    override suspend fun readCapability(id: CapabilityId): CapabilityReadResult {
        if (id == CapabilityId.READ_WIFI_STATUS || id == CapabilityId.READ_LAN_STATUS) {
            return CapabilityReadResult.Unavailable(
                reason = "Nokia G-1425G-B é uma ONT (GPON) — não expõe Wi-Fi/LAN própria neste " +
                    "payload; capability não aplicável a este equipamento.",
            )
        }
        if (id !in SUPPORTED_CAPABILITIES) {
            return CapabilityReadResult.Unavailable(
                reason = "NokiaGponDriverFamily não implementa parsing para $id nesta rodada.",
            )
        }
        val client = authenticatedClient
            ?: return CapabilityReadResult.Unavailable(
                reason = "Leitura de $id exige sessão autenticada — chame authenticate(username, password) " +
                    "(via CapabilityEngine) antes de ler capabilities.",
            )

        return withContext(Dispatchers.IO) {
            val rawBody = try {
                client.fetchAuthenticated(pathFor(id))
            } catch (e: IOException) {
                return@withContext CapabilityReadResult.Failure(reason = e.message ?: "falha de rede ao ler $id", cause = e)
            }

            if (isSessionRejectedResponse(rawBody)) {
                return@withContext CapabilityReadResult.SessionExpired(
                    reason = "Sessão Nokia rejeitada pelo equipamento (cookie sid/lsid inválido) ao ler $id.",
                )
            }

            capabilityResultFor(id, rawBody)
        }
    }

    private fun pathFor(id: CapabilityId): String = when (id) {
        CapabilityId.READ_SIGNAL -> config.gponStatusPath
        CapabilityId.READ_WAN_STATUS -> config.wanStatusPath
        CapabilityId.READ_DEVICE_INFO -> config.deviceInfoPath
        CapabilityId.READ_CONNECTED_CLIENTS -> config.connectedClientsPath
        // Mesmo endpoint de READ_SIGNAL (issue #29) — os contadores de erro vivem no objeto
        // `stats` da mesma tela de Optics Module Status, não numa tela própria.
        CapabilityId.READ_GPON_ERROR_COUNTERS -> config.gponStatusPath
        CapabilityId.READ_LAN_PORT_STATUS -> config.lanStatusPath
        else -> error("pathFor chamado para capability não suportada por NokiaGponDriverFamily: $id")
    }

    /**
     * Traduz o corpo bruto de um único endpoint (já resolvido por [pathFor]) para o vocabulário
     * público de capabilities ([CapabilityPayload]/`/modelo-capacidades`), via
     * [NokiaResponseParser]. Confidence `1.0` para toda leitura bem-sucedida: é dado lido
     * diretamente do equipamento na sessão atual, não uma inferência de fingerprint.
     */
    private fun capabilityResultFor(id: CapabilityId, rawBody: String): CapabilityReadResult = when (id) {
        CapabilityId.READ_SIGNAL -> {
            val gpon = NokiaResponseParser.parseGponStatus(rawBody)
            if (gpon == null) {
                CapabilityReadResult.Unavailable(reason = "Não foi possível interpretar o status óptico GPON na resposta do equipamento.")
            } else {
                CapabilityReadResult.Success(
                    capability = Capability(id = id, state = CapabilityState.AVAILABLE, confidence = 1.0),
                    payload = CapabilityPayload.Signal(
                        SignalStatus(
                            rxPowerDbm = gpon.rxPowerDbm,
                            txPowerDbm = gpon.txPowerDbm,
                            transceiverTemperatureCelsius = gpon.transceiverTemperatureCelsius,
                            supplyVoltageVolts = gpon.supplyVoltageVolts,
                            laserCurrentMilliAmps = gpon.laserCurrentMilliAmps,
                            rxPowerLowerThresholdDbm = gpon.rxPowerLowerThresholdDbm,
                            rxPowerUpperThresholdDbm = gpon.rxPowerUpperThresholdDbm,
                            rxPowerMarginToLowerThresholdDb = gpon.rxPowerMarginToLowerThresholdDb,
                        ),
                    ),
                )
            }
        }
        CapabilityId.READ_GPON_ERROR_COUNTERS -> {
            val counters = NokiaResponseParser.parseGponErrorCounters(rawBody)
            if (counters == null) {
                CapabilityReadResult.Unavailable(reason = "Contadores de erro GPON (FEC/HEC/DropPackets) ausentes na resposta do equipamento.")
            } else {
                CapabilityReadResult.Success(
                    capability = Capability(id = id, state = CapabilityState.AVAILABLE, confidence = 1.0),
                    payload = CapabilityPayload.GponErrorCounters(
                        com.nethal.core.model.GponErrorCounters(
                            fecErrorCount = counters.fecErrorCount,
                            hecErrorCount = counters.hecErrorCount,
                            dropPacketsCount = counters.dropPacketsCount,
                        ),
                    ),
                )
            }
        }
        CapabilityId.READ_LAN_PORT_STATUS -> {
            val ports = NokiaResponseParser.parseLanPortStatus(rawBody)
            CapabilityReadResult.Success(
                capability = Capability(id = id, state = CapabilityState.AVAILABLE, confidence = 1.0),
                payload = CapabilityPayload.LanPorts(
                    com.nethal.core.model.LanPortStatusList(
                        ports = ports.map { port ->
                            com.nethal.core.model.LanPort(
                                portNumber = port.portNumber,
                                isUp = port.isUp,
                                linkSpeedMbps = port.maxBitRateMbps,
                                errorsSent = port.errorsSent,
                                errorsReceived = port.errorsReceived,
                            )
                        },
                    ),
                ),
            )
        }
        CapabilityId.READ_WAN_STATUS -> {
            val wan = NokiaResponseParser.parseWanStatus(rawBody)
            if (wan == null) {
                CapabilityReadResult.Unavailable(reason = "Campo de status de WAN ausente na resposta do equipamento.")
            } else {
                CapabilityReadResult.Success(
                    capability = Capability(id = id, state = CapabilityState.AVAILABLE, confidence = 1.0),
                    payload = CapabilityPayload.Wan(WanStatus(ipv4Address = wan.externalIp)),
                )
            }
        }
        CapabilityId.READ_DEVICE_INFO -> {
            val info = NokiaResponseParser.parseDeviceInfo(rawBody)
            if (info == null) {
                CapabilityReadResult.Unavailable(reason = "Campo de identificação do equipamento ausente na resposta.")
            } else {
                CapabilityReadResult.Success(
                    capability = Capability(id = id, state = CapabilityState.AVAILABLE, confidence = 1.0),
                    payload = CapabilityPayload.DeviceInfo(
                        DeviceInfo(
                            vendor = info.manufacturer,
                            model = info.model,
                            firmware = info.softwareVersion,
                            hardwareVersion = info.hardwareVersion,
                            serialNumberHash = PiiHashing.sha256Hex(info.serialNumber),
                            uptimeSeconds = info.uptimeSeconds,
                            deviceType = DeviceType.ONT,
                        ),
                    ),
                )
            }
        }
        CapabilityId.READ_CONNECTED_CLIENTS -> CapabilityReadResult.Success(
            capability = Capability(id = id, state = CapabilityState.AVAILABLE, confidence = 1.0),
            payload = CapabilityPayload.ConnectedClients(
                ConnectedClientList(
                    clients = NokiaResponseParser.parseConnectedClients(rawBody).map { client ->
                        ConnectedClient(
                            hostname = client.deviceName,
                            ipAddress = client.ipAddress,
                            macAddress = client.macAddressMasked,
                        )
                    },
                ),
            ),
        )
        else -> CapabilityReadResult.Unavailable(reason = "NokiaGponDriverFamily não implementa parsing para $id nesta rodada.")
    }

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

    companion object {
        /**
         * Capabilities com parser estruturado real a partir dos 4 endpoints correspondentes
         * (`READ_WIFI_STATUS`/`READ_LAN_STATUS` são tratadas à parte em [readCapability], como
         * "não aplicável", não "não implementada").
         */
        val SUPPORTED_CAPABILITIES: Set<CapabilityId> = setOf(
            CapabilityId.READ_WAN_STATUS,
            CapabilityId.READ_DEVICE_INFO,
            CapabilityId.READ_CONNECTED_CLIENTS,
            CapabilityId.READ_SIGNAL,
            CapabilityId.READ_GPON_ERROR_COUNTERS,
            CapabilityId.READ_LAN_PORT_STATUS,
        )
    }
}

/**
 * Adapta o [HttpTransport] compartilhado para a interface [NokiaHttpTransport] ainda consumida por
 * [NokiaAuthenticationClient] — mesmo padrão de `HttpTransportToTplinkAdapter`
 * (`TpLinkLegacyCgiDriverFamily.kt`). Garante que o transporte de fato usado para falar com o
 * equipamento é sempre o recebido via [DriverFamilyFactory.create], nunca um transporte construído
 * à parte.
 */
private class HttpTransportToNokiaAdapter(private val delegate: HttpTransport) : NokiaHttpTransport {
    override fun get(url: String, extraHeaders: Map<String, String>) = delegate.get(url, extraHeaders)
    override fun post(url: String, body: String, initCookies: Map<String, String>) =
        delegate.post(url, body, cookies = initCookies)
}

/**
 * Fábrica de [NokiaGponDriverFamily], registrada no
 * [com.nethal.core.catalog.DriverFamilyRegistry] sob a chave `"nokia-ont-gpon-driver"` (mesmo valor
 * de `profile.driverFamilyId` para o profile `nokia_g1425gb_v1` no catálogo) — fecha o descompasso
 * catálogo↔registro apontado na auditoria original (issue #18).
 */
class NokiaGponDriverFamilyFactory : DriverFamilyFactory {
    override val familyId: String = "nokia-ont-gpon-driver"

    override fun create(profile: CompatibilityProfile, host: String, transport: HttpTransport): DriverFamily {
        val config = NokiaGponDriverConfig.fromJsonElement(profile.driverConfig)
        return NokiaGponDriverFamily(host = host, config = config, transport = HttpTransportToNokiaAdapter(transport))
    }
}
