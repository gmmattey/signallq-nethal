package com.nethal.core.driver.family.tplink.legacycgi

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
import com.nethal.core.driver.tplink.DefaultTplinkHttpTransport
import com.nethal.core.driver.tplink.TplinkHttpTransport
import com.nethal.core.model.Capability
import com.nethal.core.model.CapabilityId
import com.nethal.core.model.CapabilityPayload
import com.nethal.core.model.CapabilityState
import com.nethal.core.model.ConnectedClient
import com.nethal.core.model.ConnectedClientList
import com.nethal.core.model.DeviceInfo
import com.nethal.core.model.WifiBand
import com.nethal.core.model.WifiRadio
import com.nethal.core.model.WifiStatus
import com.nethal.core.protocol.http.HttpTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** Motivo de falha da Driver Family após esgotar as tentativas — vocabulário para a UI decidir a mensagem. */
internal enum class TpLinkLegacyCgiFailureReason {
    DEVICE_UNREACHABLE,
    TIMEOUT,
    UNEXPECTED_RESPONSE,
    INVALID_CREDENTIALS,
    COMMUNICATION_ERROR,
}

internal sealed interface TpLinkLegacyCgiReadOutcome {
    data class Success(val snapshot: TpLinkLegacyCgiSnapshot) : TpLinkLegacyCgiReadOutcome
    data class Failure(val reason: TpLinkLegacyCgiFailureReason, val message: String) : TpLinkLegacyCgiReadOutcome
}

/**
 * Driver Family da plataforma `tplink-legacy-cgi` (`platformId`/`driverFamilyId` do catálogo —
 * ver `docs/architecture/hal-layering-model.md` §5.5), usando o protocolo real confirmado por
 * captura via DevTools contra unidade física do Luiz (2026-07-06, ver SIG-337/SIG-338).
 *
 * Movido de `driver/tplink/TplinkC20OntDriver.kt` no passo 4 do plano de refatoração HAL (§10) —
 * o caso de validação da arquitetura: primeira Driver Family real, provando que a cadeia inteira
 * (Profile → `DriverFamilyRegistry.resolve` → instância → leitura) fecha antes de qualquer
 * expansão de cobertura. Mesmo comportamento observável do driver anterior, com duas mudanças
 * estruturais (não de protocolo):
 *
 * 1. Implementa [DriverFamily] em vez de expor só um método `readSnapshot()` solto — permite
 *    resolução via `DriverFamilyRegistry` a partir de `profile.driverFamilyId`.
 * 2. Os literais de seção/campo antes hardcoded (`listOf("LAN_WLAN" to listOf("name", "SSID"))`,
 *    etc.) agora vêm de `profile.driverConfig`, desserializado como [TpLinkLegacyCgiDriverConfig]
 *    — esta classe nunca hardcoda nome de seção/campo, só orquestra protocolo/retry/parsing.
 *
 * Serve hoje o profile `tplink_archer_c20_v1`; serviria qualquer outro modelo TP-Link com o mesmo
 * dispatcher único `/cgi` + Basic Auth via cookie (ex.: Archer C50 V2) só com um Profile novo
 * apontando `driverFamilyId: "tplink-legacy-cgi-driver"` e seu próprio `driverConfig` — zero
 * Kotlin novo, conforme a regra de evolução de `hal-layering-model.md` §9.
 *
 * Continua separado da plataforma `tplink-encrypted-web` (Archer C6): mesmo fabricante,
 * protocolos totalmente diferentes (o C6 usa handshake RSA+AES "web encrypted password" via
 * `/cgi_gdpr`; esta plataforma usa dispatcher único com Basic Auth via cookie).
 *
 * Retry conservador (no máximo 2 tentativas): sem handshake de sessão para colidir, mas mantido
 * pela mesma razão de qualquer WebUI doméstica local — retentativas agressivas não ajudam contra
 * falha persistente de credencial/rede.
 *
 * A credencial passada a [readSnapshot] nunca é retida por esta classe além da chamada: apenas o
 * cookie Base64 fica em memória em [TpLinkLegacyCgiAuthenticationClient], nunca persistido, nunca
 * enviado à nuvem, nunca logado.
 */
internal class TpLinkLegacyCgiDriverFamily(
    private val host: String,
    private val config: TpLinkLegacyCgiDriverConfig,
    private val transport: TplinkHttpTransport = DefaultTplinkHttpTransport(),
    private val maxAttempts: Int = 2,
    private val backoffMillis: (attempt: Int) -> Long = { attempt -> 1_000L * attempt },
) : DriverFamily {

    /**
     * Guarda de SSRF obrigatória, mesma classe de risco documentada em `TplinkOntDriver`/
     * `NokiaOntDriver`: falha rápido, sem tentar login, quando o host não é RFC 1918.
     */
    init {
        require(PrivateIpRanges.isPrivate(host)) {
            "TpLinkLegacyCgiDriverFamily só opera contra IP privado (RFC 1918) da LAN; host recebido: $host"
        }
    }

    /**
     * Lê o snapshot completo (device info, Wi-Fi, clientes conectados) numa única sessão
     * autenticada — mesma orquestração de sempre, só com seções/campos vindos de [config] em vez
     * de hardcoded. Método interno preservado (não faz parte de [DriverFamily]) porque o payload
     * rico continua sendo o formato consumido por `ManualCheckRunnerC20` e pelos testes; a
     * granularidade por-capability exigida por [DriverFamily.readCapability] delega para este
     * mesmo snapshot.
     */
    suspend fun readSnapshot(username: String, password: String): TpLinkLegacyCgiReadOutcome = withContext(Dispatchers.IO) {
        val outcome = executeWithRetry(
            maxAttempts = maxAttempts,
            backoffMillis = backoffMillis,
            loginExceptionType = TpLinkLegacyCgiLoginException::class.java,
            onLoginFailure = { e ->
                when (e.reason) {
                    TpLinkLegacyCgiLoginFailureReason.INVALID_CREDENTIALS -> TpLinkLegacyCgiFailureReason.INVALID_CREDENTIALS
                    TpLinkLegacyCgiLoginFailureReason.UNEXPECTED_RESPONSE,
                    TpLinkLegacyCgiLoginFailureReason.UNKNOWN,
                    // SESSION_EXPIRED só é lançado por fetchAuthenticated (nunca por login() em si), mas o
                    // enum é compartilhado entre os dois — mesmo raciocínio de UNEXPECTED_RESPONSE: tenta
                    // login+leitura completos de novo.
                    TpLinkLegacyCgiLoginFailureReason.SESSION_EXPIRED,
                    -> null
                }
            },
            classifyFinalFailure = ::classifyFailure,
        ) {
            val client = TpLinkLegacyCgiAuthenticationClient(host, transport, config.loginValidationSections())
            client.login(username, password)

            // Mesmo bundle exato usado por login() (config.loginValidationSections()) — evita
            // divergir do único bundle com prova real de sucesso.
            val deviceInfoBody = client.fetchAuthenticated(
                TpLinkLegacyCgiResponseParser.buildRequestBody(config.loginValidationSections()),
            )
            val wifiBody = client.fetchAuthenticated(
                TpLinkLegacyCgiResponseParser.buildRequestBody(config.wifiStatusSections()),
            )
            val clientsBody = client.fetchAuthenticated(
                TpLinkLegacyCgiResponseParser.buildRequestBody(config.connectedClientsSections()),
            )

            TpLinkLegacyCgiSnapshot(
                deviceInfo = TpLinkLegacyCgiResponseParser.parseDeviceInfo(
                    deviceInfoBody,
                    deviceInfoIndex = config.deviceInfoIndex,
                    ethSwitchIndex = config.ethSwitchIndex,
                    sysModeIndex = config.sysModeIndex,
                ),
                wifi = TpLinkLegacyCgiResponseParser.parseWifiStatus(wifiBody, lanWlanIndex = config.wifiStatusIndex),
                connectedClients = TpLinkLegacyCgiResponseParser.parseConnectedClients(
                    clientsBody,
                    lanHostEntryIndex = config.connectedClientsIndex,
                ),
            )
        }

        when (outcome) {
            is RetryOutcome.Success -> TpLinkLegacyCgiReadOutcome.Success(outcome.value)
            is RetryOutcome.Failure -> TpLinkLegacyCgiReadOutcome.Failure(outcome.reason, outcome.error.message ?: outcome.error.toString())
        }
    }

    /**
     * Client autenticado da sessão atual, preenchido por [authenticate] — `null` até a primeira
     * autenticação bem-sucedida (via [com.nethal.core.capability.CapabilityEngine]) ou depois de uma
     * renovação que falhou. Mesmo desenho de `TpLinkStokLuciDriverFamily.authenticatedClient`: é aqui
     * que o único material de sessão deste protocolo (o cookie `Authorization` Basic Auth) vive.
     */
    private var authenticatedClient: TpLinkLegacyCgiAuthenticationClient? = null

    /**
     * Implementação de [DriverFamily.authenticate] — issue #19. Faz o mesmo handshake de
     * [readSnapshot] (aqui não existe endpoint de login dedicado; "autenticar" é validar a credencial
     * com a mesma primeira leitura real de sempre, ver KDoc de [TpLinkLegacyCgiAuthenticationClient]),
     * mas guarda o client resultante em [authenticatedClient] em vez de descartá-lo, para
     * [readCapability] reaproveitar a sessão entre chamadas. Chamar de novo (renovação) sempre
     * substitui o client anterior, mesmo em caso de falha.
     */
    override suspend fun authenticate(username: String, password: String): DriverFamilyAuthResult = withContext(Dispatchers.IO) {
        val outcome = executeWithRetry(
            maxAttempts = maxAttempts,
            backoffMillis = backoffMillis,
            loginExceptionType = TpLinkLegacyCgiLoginException::class.java,
            onLoginFailure = { e ->
                when (e.reason) {
                    TpLinkLegacyCgiLoginFailureReason.INVALID_CREDENTIALS -> TpLinkLegacyCgiFailureReason.INVALID_CREDENTIALS
                    TpLinkLegacyCgiLoginFailureReason.UNEXPECTED_RESPONSE,
                    TpLinkLegacyCgiLoginFailureReason.UNKNOWN,
                    TpLinkLegacyCgiLoginFailureReason.SESSION_EXPIRED,
                    -> null
                }
            },
            classifyFinalFailure = ::classifyFailure,
        ) {
            val client = TpLinkLegacyCgiAuthenticationClient(host, transport, config.loginValidationSections())
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
                if (outcome.reason == TpLinkLegacyCgiFailureReason.INVALID_CREDENTIALS) {
                    DriverFamilyAuthResult.InvalidCredentials(message)
                } else {
                    DriverFamilyAuthResult.Failure(message)
                }
            }
        }
    }

    /**
     * Implementação de [DriverFamily.readCapability] — issue #19. Usa a sessão de
     * [authenticatedClient] (preenchida por [authenticate]) em vez de sempre devolver
     * [CapabilityReadResult.Unavailable]. Diferente de `TpLinkStokLuciDriverFamily` (um único
     * endpoint devolve tudo), este protocolo tem um bundle `/cgi` dedicado por capability — cada
     * leitura busca só o bundle de que precisa, mesma separação já usada por [readSnapshot].
     *
     * HTTP 401/403 numa leitura autenticada vira [CapabilityReadResult.SessionExpired] (ver
     * [TpLinkLegacyCgiLoginFailureReason.SESSION_EXPIRED] em [TpLinkLegacyCgiAuthenticationClient.fetchAuthenticated]).
     * Qualquer outra falha de rede/protocolo vira [CapabilityReadResult.Failure].
     */
    override suspend fun readCapability(id: CapabilityId): CapabilityReadResult {
        if (id !in SUPPORTED_CAPABILITIES) {
            return CapabilityReadResult.Unavailable(
                reason = "TpLinkLegacyCgiDriverFamily não implementa leitura para $id nesta rodada.",
            )
        }
        val client = authenticatedClient
            ?: return CapabilityReadResult.Unavailable(
                reason = "Leitura de $id exige sessão autenticada — chame authenticate(username, password) " +
                    "(via CapabilityEngine) antes de ler capabilities.",
            )

        return withContext(Dispatchers.IO) {
            try {
                when (id) {
                    CapabilityId.READ_DEVICE_INFO -> {
                        val body = client.fetchAuthenticated(TpLinkLegacyCgiResponseParser.buildRequestBody(config.loginValidationSections()))
                        deviceInfoResultFor(
                            TpLinkLegacyCgiResponseParser.parseDeviceInfo(
                                body,
                                deviceInfoIndex = config.deviceInfoIndex,
                                ethSwitchIndex = config.ethSwitchIndex,
                                sysModeIndex = config.sysModeIndex,
                            ),
                        )
                    }
                    CapabilityId.READ_WIFI_STATUS -> {
                        val body = client.fetchAuthenticated(TpLinkLegacyCgiResponseParser.buildRequestBody(config.wifiStatusSections()))
                        wifiResultFor(TpLinkLegacyCgiResponseParser.parseWifiStatus(body, lanWlanIndex = config.wifiStatusIndex))
                    }
                    CapabilityId.READ_CONNECTED_CLIENTS -> {
                        val body = client.fetchAuthenticated(TpLinkLegacyCgiResponseParser.buildRequestBody(config.connectedClientsSections()))
                        connectedClientsResultFor(
                            TpLinkLegacyCgiResponseParser.parseConnectedClients(body, lanHostEntryIndex = config.connectedClientsIndex),
                        )
                    }
                    else -> CapabilityReadResult.Unavailable(
                        reason = "TpLinkLegacyCgiDriverFamily não implementa leitura para $id nesta rodada.",
                    )
                }
            } catch (e: TpLinkLegacyCgiLoginException) {
                if (e.reason == TpLinkLegacyCgiLoginFailureReason.SESSION_EXPIRED) {
                    CapabilityReadResult.SessionExpired(reason = e.message ?: "sessão expirada ao ler $id")
                } else {
                    CapabilityReadResult.Failure(reason = e.message ?: "falha inesperada ao ler $id", cause = e)
                }
            } catch (e: IOException) {
                CapabilityReadResult.Failure(reason = e.message ?: "falha de rede ao ler $id", cause = e)
            }
        }
    }

    /**
     * Traduz [TpLinkLegacyCgiDeviceInfo] (já parseado) para o vocabulário público de capabilities.
     * `vendor = "TP-Link"` é um fato conhecido desta plataforma (todo profile registrado sob
     * `tplink-legacy-cgi-driver` é um equipamento TP-Link), não uma inferência de dado nem um
     * `if (vendor == ...)` — o protocolo em si não expõe campo de fabricante. `firmware`/
     * `hardwareVersion`/`serialNumberHash`/`uptimeSeconds`/`deviceType` ficam `null`: nenhum desses
     * campos apareceu na captura real (SIG-337/SIG-338) do bundle IGD_DEV_INFO+ETH_SWITCH+SYS_MODE.
     */
    private fun deviceInfoResultFor(deviceInfo: TpLinkLegacyCgiDeviceInfo?): CapabilityReadResult {
        if (deviceInfo == null) {
            return CapabilityReadResult.Unavailable(reason = "Nenhuma seção de device info interpretada na resposta do equipamento.")
        }
        return CapabilityReadResult.Success(
            capability = Capability(id = CapabilityId.READ_DEVICE_INFO, state = CapabilityState.AVAILABLE, confidence = 1.0),
            payload = CapabilityPayload.DeviceInfo(
                DeviceInfo(vendor = "TP-Link", model = deviceInfo.modelName),
            ),
        )
    }

    /** `band` fica sempre [WifiBand.UNKNOWN]: a seção `LAN_WLAN` só expõe `name`/`SSID`, sem campo de banda. */
    private fun wifiResultFor(radios: List<TpLinkLegacyCgiWifiStatus>): CapabilityReadResult {
        if (radios.isEmpty()) {
            return CapabilityReadResult.Unavailable(reason = "Nenhum rádio Wi-Fi interpretado na resposta do equipamento.")
        }
        return CapabilityReadResult.Success(
            capability = Capability(id = CapabilityId.READ_WIFI_STATUS, state = CapabilityState.AVAILABLE, confidence = 1.0),
            payload = CapabilityPayload.Wifi(
                WifiStatus(
                    radios = radios.map { radio -> WifiRadio(id = radio.name, band = WifiBand.UNKNOWN, ssid = radio.ssid) },
                ),
            ),
        )
    }

    /** Lista vazia é dado real ("nenhum cliente conectado agora"), não ausência de dado — sempre Success, mesma regra de `TpLinkStokLuciDriverFamily`. */
    private fun connectedClientsResultFor(clients: List<TpLinkLegacyCgiConnectedClient>): CapabilityReadResult =
        CapabilityReadResult.Success(
            capability = Capability(id = CapabilityId.READ_CONNECTED_CLIENTS, state = CapabilityState.AVAILABLE, confidence = 1.0),
            payload = CapabilityPayload.ConnectedClients(
                ConnectedClientList(
                    clients = clients.map { client ->
                        ConnectedClient(hostname = client.hostname, ipAddress = client.ipAddress, macAddress = client.macAddress)
                    },
                ),
            ),
        )

    private fun classifyFailure(error: Throwable): TpLinkLegacyCgiFailureReason = when (classifyNetworkFailure(error)) {
        NetworkFailureReason.DEVICE_UNREACHABLE -> TpLinkLegacyCgiFailureReason.DEVICE_UNREACHABLE
        NetworkFailureReason.TIMEOUT -> TpLinkLegacyCgiFailureReason.TIMEOUT
        NetworkFailureReason.UNEXPECTED_RESPONSE -> TpLinkLegacyCgiFailureReason.UNEXPECTED_RESPONSE
        NetworkFailureReason.COMMUNICATION_ERROR -> TpLinkLegacyCgiFailureReason.COMMUNICATION_ERROR
    }

    companion object {
        val SUPPORTED_CAPABILITIES: Set<CapabilityId> = setOf(
            CapabilityId.READ_DEVICE_INFO,
            CapabilityId.READ_WIFI_STATUS,
            CapabilityId.READ_CONNECTED_CLIENTS,
        )
    }
}

/**
 * Adapta o [HttpTransport] compartilhado (passo 1 do plano de refatoração) para a interface
 * [TplinkHttpTransport] ainda consumida por [TpLinkLegacyCgiAuthenticationClient] — permite que a
 * factory realmente use o transporte recebido via [DriverFamilyFactory.create] em vez de
 * silenciosamente construir um [DefaultTplinkHttpTransport] próprio, o que provaria a resolução da
 * cadeia (`hal-layering-model.md` §8) sem provar que o transporte resolvido é o que de fato é
 * usado para falar com o equipamento.
 */
private class HttpTransportToTplinkAdapter(private val delegate: HttpTransport) : TplinkHttpTransport {
    override fun get(url: String, extraHeaders: Map<String, String>) = delegate.get(url, extraHeaders)
    override fun post(url: String, body: String, cookies: Map<String, String>) =
        delegate.post(url, body, cookies = cookies)
}

/**
 * Fábrica de [TpLinkLegacyCgiDriverFamily], registrada no [com.nethal.core.catalog.DriverFamilyRegistry]
 * sob a chave `"tplink-legacy-cgi-driver"` (mesmo valor de `profile.driverFamilyId` no catálogo).
 *
 * Constrói a Driver Family a partir de [CompatibilityProfile.driverConfig], desserializado como
 * [TpLinkLegacyCgiDriverConfig] — se o profile resolvido para este `driverFamilyId` não tiver um
 * `driverConfig` no formato esperado, a construção falha alta e cedo (catálogo publicado
 * incorretamente), em vez de a Driver Family descobrir isso só na primeira leitura.
 *
 * O [HttpTransport] recebido via [create] (o compartilhado do passo 1) é adaptado para
 * [TplinkHttpTransport] via [HttpTransportToTplinkAdapter] — a Driver Family em si continua
 * programada contra [TplinkHttpTransport] (mesma assinatura usada pelos testes e por
 * `ManualCheckRunnerC20`), mas o transporte de fato usado é sempre o recebido pela factory, nunca
 * um `DefaultTplinkHttpTransport()` construído à parte. Migrar
 * `TpLinkLegacyCgiAuthenticationClient` para depender diretamente de [HttpTransport] (eliminando o
 * adapter) é um passo futuro, sem mudança de comportamento — fora de escopo deste passo 4.
 */
class TpLinkLegacyCgiDriverFamilyFactory : DriverFamilyFactory {
    override val familyId: String = "tplink-legacy-cgi-driver"

    override fun create(profile: CompatibilityProfile, host: String, transport: HttpTransport): DriverFamily {
        val config = TpLinkLegacyCgiDriverConfig.fromJsonElement(profile.driverConfig)
        return TpLinkLegacyCgiDriverFamily(host = host, config = config, transport = HttpTransportToTplinkAdapter(transport))
    }
}
