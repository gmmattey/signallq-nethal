package com.nethal.core.driver.family.tplink.stokluci

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
import com.nethal.core.model.Capability
import com.nethal.core.model.CapabilityId
import com.nethal.core.model.CapabilityPayload
import com.nethal.core.model.CapabilityState
import com.nethal.core.model.ConnectedClient
import com.nethal.core.model.ConnectedClientList
import com.nethal.core.model.LanStatus
import com.nethal.core.model.WanStatus
import com.nethal.core.model.WifiBand
import com.nethal.core.model.WifiRadio
import com.nethal.core.model.WifiStatus
import com.nethal.core.protocol.http.HttpTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** Motivo de falha da Driver Family após esgotar as tentativas — mesmo vocabulário genérico de `TpLinkLegacyCgiFailureReason`. */
internal enum class TpLinkStokLuciFailureReason {
    DEVICE_UNREACHABLE,
    TIMEOUT,
    UNEXPECTED_RESPONSE,
    INVALID_CREDENTIALS,
    COMMUNICATION_ERROR,
}

internal sealed interface TpLinkStokLuciLoginOutcome {
    data class Success(val session: TpLinkStokLuciSession) : TpLinkStokLuciLoginOutcome
    data class Failure(val reason: TpLinkStokLuciFailureReason, val message: String) : TpLinkStokLuciLoginOutcome
}

internal sealed interface TpLinkStokLuciStatusOutcome {
    data class Success(val rawBody: String) : TpLinkStokLuciStatusOutcome
    data class Failure(val reason: TpLinkStokLuciFailureReason, val message: String) : TpLinkStokLuciStatusOutcome
}

internal sealed interface TpLinkStokLuciSnapshotOutcome {
    data class Success(val snapshot: TpLinkStokLuciSnapshot) : TpLinkStokLuciSnapshotOutcome
    data class Failure(val reason: TpLinkStokLuciFailureReason, val message: String) : TpLinkStokLuciSnapshotOutcome
}

/**
 * Driver Family da plataforma `tplink-stok-luci` (`platformId`/`driverFamilyId` do catálogo —
 * profile `tplink_archer_c6_stok_v1`, ver `docs/architecture/hal-layering-model.md` §9.1).
 *
 * Implementa o login (envelope `sign`/`data`, ver [TpLinkStokLuciAuthenticationClient] para o
 * protocolo real confirmado por evidência ao vivo), uma leitura autenticada bruta de status geral
 * ([readStatusRaw]) e, desde esta rodada, o parsing estruturado desse payload para o vocabulário de
 * capabilities do NetHAL ([readSnapshot], via [TpLinkStokLuciStatusParser]) — cobre
 * `READ_WIFI_STATUS`, `READ_LAN_STATUS`, `READ_WAN_STATUS` e `READ_CONNECTED_CLIENTS`
 * ([SUPPORTED_CAPABILITIES]). `READ_DEVICE_INFO`/`READ_FIRMWARE` continuam fora de escopo:
 * revisão dedicada em 2026-07-07 (mapeamento de capabilities restantes) checou o corpo de resposta
 * de toda chamada já capturada ao vivo deste fluxo (`form=keys`, `form=auth`, `form=login`,
 * `admin/status?form=all`) e nenhuma delas carrega campo de vendor/modelo/versão de firmware — ver
 * `docs/drivers/compatibility-catalog.md` (changelog) para o detalhe. Guest network
 * (`guest_2g_ssid`/`guest_5g_ssid`) permanece modelada como rádio adicional dentro de
 * `READ_WIFI_STATUS` — não existe capability própria para rede de convidados no vocabulário oficial
 * (`CapabilityId`), decisão revisada e mantida na mesma rodada.
 *
 * O ciclo de correções desta Driver Family passou por várias rodadas de `INVALID_CREDENTIALS`
 * (HTTP 403) até convergir com a captura real do hardware do Luiz (Archer C6 v2.0, firmware
 * `1.1.10 Build 20230830 rel.69433(5553)`). O estado atual já foi validado ao vivo para:
 * login bem-sucedido + leitura autenticada bruta de `admin/status?form=all`. Ver `ManualCheckRunner`
 * para o comando de teste manual e `docs/drivers/live-evidence/tplink-archer-c6-stok-v1.json` para a
 * evidência de hardware.
 *
 * **issue #16 (Capability Engine com gerenciamento de sessão real)**: [readCapability] agora é uma
 * implementação real — primeira `DriverFamily` do NetHAL a sair do estado honestamente indisponível.
 * A sessão vive em [authenticatedClient], preenchida por [authenticate] (implementação de
 * [DriverFamily.authenticate]) e reaproveitada por [readCapability] em quantas chamadas forem
 * feitas depois, sem novo login a cada leitura — ver `core/capability/CapabilityEngine.kt` para quem
 * decide quando chamar [authenticate] (lazy, na primeira leitura) e quando renová-la (quando
 * [readCapability] devolve [CapabilityReadResult.SessionExpired]).
 *
 * [login]/[readStatusRaw]/[readSnapshot] continuam com o desenho anterior de propósito (login novo a
 * cada chamada, sem reaproveitar [authenticatedClient]) — são os métodos com evidência ao vivo direta
 * (`ManualCheckRunner`) e os testes existentes dependem desse comportamento; não foram tocados para
 * não arriscar o único caminho já validado contra hardware físico. [authenticate]/[readCapability]
 * são um caminho adicional, não uma substituição.
 *
 * Guarda de SSRF obrigatória (RFC 1918), mesma classe de risco de toda Driver Family do NetHAL —
 * falha rápido, sem tentar login, quando o host não é IP privado.
 *
 * Retry conservador (no máximo 2 tentativas), mesmo raciocínio do `tplink-legacy-cgi`: sem
 * handshake de sessão persistente confiável para colidir entre tentativas, mas retentativa
 * agressiva não ajuda contra falha persistente de credencial/rede.
 */
internal class TpLinkStokLuciDriverFamily(
    private val host: String,
    private val config: TpLinkStokLuciDriverConfig,
    private val transport: HttpTransport,
    private val maxAttempts: Int = 2,
    private val backoffMillis: (attempt: Int) -> Long = { attempt -> 1_000L * attempt },
) : DriverFamily {

    init {
        require(PrivateIpRanges.isPrivate(host)) {
            "TpLinkStokLuciDriverFamily só opera contra IP privado (RFC 1918) da LAN; host recebido: $host"
        }
    }

    /**
     * Client autenticado da sessão atual, preenchido por [authenticate] — `null` até a primeira
     * autenticação bem-sucedida (via [CapabilityEngine][com.nethal.core.capability.CapabilityEngine])
     * ou depois de uma renovação que falhou. Reaproveitado por [readCapability] entre chamadas: é
     * aqui, dentro da instância de `DriverFamily`, que a sessão real deste protocolo (token `stok`,
     * cookie `sysauth`, chave/IV AES) vive — ver KDoc de `CapabilityEngine` para a decisão de
     * arquitetura completa de por que a sessão fica aqui e não num componente externo.
     */
    private var authenticatedClient: TpLinkStokLuciAuthenticationClient? = null

    /**
     * Executa só o login (passos 1-5 do handshake) e devolve a sessão resultante. Não persiste a
     * sessão entre chamadas — cada chamada a este método faz um login novo, mesmo desenho de
     * `readSnapshot` do `tplink-legacy-cgi` (sem Capability Engine gerenciando sessão ainda).
     */
    suspend fun login(username: String, password: String): TpLinkStokLuciLoginOutcome = withContext(Dispatchers.IO) {
        val outcome = executeWithRetry(
            maxAttempts = maxAttempts,
            backoffMillis = backoffMillis,
            loginExceptionType = TpLinkStokLuciLoginException::class.java,
            onLoginFailure = { e ->
                when (e.reason) {
                    TpLinkStokLuciLoginFailureReason.INVALID_CREDENTIALS -> TpLinkStokLuciFailureReason.INVALID_CREDENTIALS
                    TpLinkStokLuciLoginFailureReason.AUTH_ENDPOINT_UNAVAILABLE,
                    TpLinkStokLuciLoginFailureReason.UNEXPECTED_RESPONSE,
                    // SESSION_EXPIRED só é lançado por fetchAuthenticated (nunca por login() em si), mas o
                    // enum é compartilhado entre os dois — tratado como candidato a retry (mesmo raciocínio
                    // de UNEXPECTED_RESPONSE: tenta login+leitura completos de novo).
                    TpLinkStokLuciLoginFailureReason.SESSION_EXPIRED,
                    -> null
                }
            },
            classifyFinalFailure = ::classifyFailure,
        ) {
            val client = TpLinkStokLuciAuthenticationClient(host, transport)
            client.login(username, password)
        }

        when (outcome) {
            is RetryOutcome.Success -> TpLinkStokLuciLoginOutcome.Success(outcome.value)
            is RetryOutcome.Failure -> TpLinkStokLuciLoginOutcome.Failure(outcome.reason, outcome.error.message ?: outcome.error.toString())
        }
    }

    /**
     * Login seguido de uma única leitura autenticada real de `config.statusReadPath`, usando o
     * mesmo envelope AES + `sign` confirmado no hardware. Devolve o corpo bruto já decifrado
     * (JSON), sem parsing estruturado: a coleta ponta a ponta deste endpoint já foi validada contra
     * o equipamento real, mas ainda não existe mapeamento de campos para capabilities.
     */
    suspend fun readStatusRaw(username: String, password: String): TpLinkStokLuciStatusOutcome = withContext(Dispatchers.IO) {
        val outcome = executeWithRetry(
            maxAttempts = maxAttempts,
            backoffMillis = backoffMillis,
            loginExceptionType = TpLinkStokLuciLoginException::class.java,
            onLoginFailure = { e ->
                when (e.reason) {
                    TpLinkStokLuciLoginFailureReason.INVALID_CREDENTIALS -> TpLinkStokLuciFailureReason.INVALID_CREDENTIALS
                    TpLinkStokLuciLoginFailureReason.AUTH_ENDPOINT_UNAVAILABLE,
                    TpLinkStokLuciLoginFailureReason.UNEXPECTED_RESPONSE,
                    // SESSION_EXPIRED só é lançado por fetchAuthenticated (nunca por login() em si), mas o
                    // enum é compartilhado entre os dois — tratado como candidato a retry (mesmo raciocínio
                    // de UNEXPECTED_RESPONSE: tenta login+leitura completos de novo).
                    TpLinkStokLuciLoginFailureReason.SESSION_EXPIRED,
                    -> null
                }
            },
            classifyFinalFailure = ::classifyFailure,
        ) {
            val client = TpLinkStokLuciAuthenticationClient(host, transport)
            client.login(username, password)
            client.fetchAuthenticated(config.statusReadPath, config.statusReadQuery)
        }

        when (outcome) {
            is RetryOutcome.Success -> TpLinkStokLuciStatusOutcome.Success(outcome.value)
            is RetryOutcome.Failure -> TpLinkStokLuciStatusOutcome.Failure(outcome.reason, outcome.error.message ?: outcome.error.toString())
        }
    }

    /**
     * [readStatusRaw] seguido do parsing estruturado ([TpLinkStokLuciStatusParser.parseSnapshot])
     * do corpo bruto de `admin/status?form=all` para o vocabulário de capabilities do NetHAL —
     * cobre [SUPPORTED_CAPABILITIES]. Mesma orquestração (login novo a cada chamada, sem sessão
     * persistida) e mesmo motivo: sem Capability Engine gerenciando sessão ainda, este é o ponto de
     * entrada real usado por `ManualCheckRunner`/testes até essa peça existir.
     */
    suspend fun readSnapshot(username: String, password: String): TpLinkStokLuciSnapshotOutcome =
        when (val outcome = readStatusRaw(username, password)) {
            is TpLinkStokLuciStatusOutcome.Success ->
                TpLinkStokLuciSnapshotOutcome.Success(TpLinkStokLuciStatusParser.parseSnapshot(outcome.rawBody))
            is TpLinkStokLuciStatusOutcome.Failure ->
                TpLinkStokLuciSnapshotOutcome.Failure(outcome.reason, outcome.message)
        }

    /**
     * Implementação de [DriverFamily.authenticate] — faz o mesmo handshake de [login], mas guarda o
     * [TpLinkStokLuciAuthenticationClient] resultante em [authenticatedClient] em vez de descartá-lo,
     * para [readCapability] reaproveitar a sessão entre chamadas. Chamar de novo (renovação) descarta
     * o client anterior e substitui por um novo, mesmo em caso de falha (nunca deixa
     * [authenticatedClient] apontando para uma sessão que pode já ter sido invalidada pelo
     * equipamento).
     */
    override suspend fun authenticate(username: String, password: String): DriverFamilyAuthResult = withContext(Dispatchers.IO) {
        val outcome = executeWithRetry(
            maxAttempts = maxAttempts,
            backoffMillis = backoffMillis,
            loginExceptionType = TpLinkStokLuciLoginException::class.java,
            onLoginFailure = { e ->
                when (e.reason) {
                    TpLinkStokLuciLoginFailureReason.INVALID_CREDENTIALS -> TpLinkStokLuciFailureReason.INVALID_CREDENTIALS
                    TpLinkStokLuciLoginFailureReason.AUTH_ENDPOINT_UNAVAILABLE,
                    TpLinkStokLuciLoginFailureReason.UNEXPECTED_RESPONSE,
                    TpLinkStokLuciLoginFailureReason.SESSION_EXPIRED,
                    -> null
                }
            },
            classifyFinalFailure = ::classifyFailure,
        ) {
            val client = TpLinkStokLuciAuthenticationClient(host, transport)
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
                if (outcome.reason == TpLinkStokLuciFailureReason.INVALID_CREDENTIALS) {
                    DriverFamilyAuthResult.InvalidCredentials(message)
                } else {
                    DriverFamilyAuthResult.Failure(message)
                }
            }
        }
    }

    /**
     * Implementação de [DriverFamily.readCapability] — usa a sessão de [authenticatedClient]
     * (preenchida por [authenticate]) em vez de fazer login novo a cada chamada. Sem sessão ativa, a
     * resposta honesta continua sendo [CapabilityReadResult.Unavailable] (nunca lança, nunca inventa
     * dado) — o caminho normal para abrir/renovar a sessão automaticamente é via
     * `CapabilityEngine.readCapability`, não chamando este método direto sem antes autenticar.
     *
     * HTTP 401/403 numa leitura autenticada vira [CapabilityReadResult.SessionExpired] (ver
     * [TpLinkStokLuciLoginFailureReason.SESSION_EXPIRED]) — o Capability Engine decide dali se/como
     * renovar. Qualquer outra falha de rede/protocolo vira [CapabilityReadResult.Failure].
     */
    override suspend fun readCapability(id: CapabilityId): CapabilityReadResult {
        if (id !in SUPPORTED_CAPABILITIES) {
            return CapabilityReadResult.Unavailable(
                reason = "TpLinkStokLuciDriverFamily não implementa parsing para $id nesta rodada.",
            )
        }
        val client = authenticatedClient
            ?: return CapabilityReadResult.Unavailable(
                reason = "Leitura de $id exige sessão autenticada — chame authenticate(username, password) " +
                    "(via CapabilityEngine) antes de ler capabilities.",
            )

        return withContext(Dispatchers.IO) {
            val rawBody = try {
                client.fetchAuthenticated(config.statusReadPath, config.statusReadQuery)
            } catch (e: TpLinkStokLuciLoginException) {
                return@withContext if (e.reason == TpLinkStokLuciLoginFailureReason.SESSION_EXPIRED) {
                    CapabilityReadResult.SessionExpired(reason = e.message ?: "sessão expirada ao ler $id")
                } else {
                    CapabilityReadResult.Failure(reason = e.message ?: "falha inesperada ao ler $id", cause = e)
                }
            } catch (e: IOException) {
                return@withContext CapabilityReadResult.Failure(reason = e.message ?: "falha de rede ao ler $id", cause = e)
            }

            capabilityResultFor(id, TpLinkStokLuciStatusParser.parseSnapshot(rawBody))
        }
    }

    /**
     * Traduz o [TpLinkStokLuciSnapshot] já parseado ([TpLinkStokLuciStatusParser]) para o vocabulário
     * público de capabilities ([CapabilityPayload]/`/modelo-capacidades`). Confidence `1.0` para toda
     * leitura bem-sucedida: é dado lido diretamente do equipamento na sessão atual, não uma inferência
     * de fingerprint — não há graduação de confiança entre "leu com sucesso" e "leu com mais sucesso
     * ainda". Campo ausente no payload vira [CapabilityReadResult.Unavailable] com motivo explícito
     * (nunca `Success` com payload vazio fingindo que o dado existe), exceto para
     * `READ_CONNECTED_CLIENTS`: lista vazia é dado real ("nenhum cliente conectado agora"), não
     * ausência de dado.
     */
    private fun capabilityResultFor(id: CapabilityId, snapshot: TpLinkStokLuciSnapshot): CapabilityReadResult = when (id) {
        CapabilityId.READ_WIFI_STATUS -> if (snapshot.wifi.isEmpty()) {
            CapabilityReadResult.Unavailable(reason = "Nenhum rádio Wi-Fi interpretado na resposta do equipamento.")
        } else {
            CapabilityReadResult.Success(
                capability = Capability(id = id, state = CapabilityState.AVAILABLE, confidence = 1.0),
                payload = CapabilityPayload.Wifi(
                    WifiStatus(
                        radios = snapshot.wifi.map { radio ->
                            WifiRadio(
                                id = radio.id,
                                band = when (radio.band) {
                                    TpLinkStokLuciWifiBand.GHZ_2_4 -> WifiBand.GHZ_2_4
                                    TpLinkStokLuciWifiBand.GHZ_5 -> WifiBand.GHZ_5
                                    TpLinkStokLuciWifiBand.UNKNOWN -> WifiBand.UNKNOWN
                                },
                                ssid = radio.ssid,
                                channel = radio.channel,
                            )
                        },
                    ),
                ),
            )
        }
        CapabilityId.READ_LAN_STATUS -> {
            val lan = snapshot.lan
            if (lan == null) {
                CapabilityReadResult.Unavailable(reason = "Campo de status de LAN ausente na resposta do equipamento.")
            } else {
                CapabilityReadResult.Success(
                    capability = Capability(id = id, state = CapabilityState.AVAILABLE, confidence = 1.0),
                    payload = CapabilityPayload.Lan(LanStatus(macAddress = lan.macAddress, ipv4Address = lan.ipv4Address)),
                )
            }
        }
        CapabilityId.READ_WAN_STATUS -> {
            val wan = snapshot.wan
            if (wan == null) {
                CapabilityReadResult.Unavailable(reason = "Campo de status de WAN ausente na resposta do equipamento.")
            } else {
                CapabilityReadResult.Success(
                    capability = Capability(id = id, state = CapabilityState.AVAILABLE, confidence = 1.0),
                    payload = CapabilityPayload.Wan(WanStatus(ipv4Address = wan.ipv4Address)),
                )
            }
        }
        CapabilityId.READ_CONNECTED_CLIENTS -> CapabilityReadResult.Success(
            capability = Capability(id = id, state = CapabilityState.AVAILABLE, confidence = 1.0),
            payload = CapabilityPayload.ConnectedClients(
                ConnectedClientList(
                    clients = snapshot.connectedClients.map { client ->
                        ConnectedClient(hostname = client.hostname, ipAddress = client.ipAddress, macAddress = client.macAddress)
                    },
                ),
            ),
        )
        else -> CapabilityReadResult.Unavailable(reason = "TpLinkStokLuciDriverFamily não implementa parsing para $id nesta rodada.")
    }

    private fun classifyFailure(error: Throwable): TpLinkStokLuciFailureReason = when (classifyNetworkFailure(error)) {
        NetworkFailureReason.DEVICE_UNREACHABLE -> TpLinkStokLuciFailureReason.DEVICE_UNREACHABLE
        NetworkFailureReason.TIMEOUT -> TpLinkStokLuciFailureReason.TIMEOUT
        NetworkFailureReason.UNEXPECTED_RESPONSE -> TpLinkStokLuciFailureReason.UNEXPECTED_RESPONSE
        NetworkFailureReason.COMMUNICATION_ERROR -> TpLinkStokLuciFailureReason.COMMUNICATION_ERROR
    }

    companion object {
        /**
         * Capabilities com parser estruturado real a partir de `admin/status?form=all`
         * ([TpLinkStokLuciStatusParser]) — `READ_DEVICE_INFO`/`READ_FIRMWARE` ficam de fora porque
         * nenhum campo de modelo/firmware foi confirmado nesse payload até aqui.
         */
        val SUPPORTED_CAPABILITIES: Set<CapabilityId> = setOf(
            CapabilityId.READ_WIFI_STATUS,
            CapabilityId.READ_LAN_STATUS,
            CapabilityId.READ_WAN_STATUS,
            CapabilityId.READ_CONNECTED_CLIENTS,
        )
    }
}

/**
 * Fábrica de [TpLinkStokLuciDriverFamily], registrada no
 * [com.nethal.core.catalog.DriverFamilyRegistry] sob a chave `"tplink-stok-luci-driver"` (mesmo
 * valor de `profile.driverFamilyId` para o profile `tplink_archer_c6_stok_v1` no catálogo).
 *
 * Diferente de [com.nethal.core.driver.family.tplink.legacycgi.TpLinkLegacyCgiDriverFamilyFactory],
 * usa o [HttpTransport] compartilhado diretamente, sem adapter — não existe assinatura legada
 * (`TplinkHttpTransport`) para preservar aqui, já que esta é uma Driver Family nova.
 */
class TpLinkStokLuciDriverFamilyFactory : DriverFamilyFactory {
    override val familyId: String = "tplink-stok-luci-driver"

    override fun create(profile: CompatibilityProfile, host: String, transport: HttpTransport): DriverFamily {
        val config = TpLinkStokLuciDriverConfig.fromJsonElement(profile.driverConfig)
        return TpLinkStokLuciDriverFamily(host = host, config = config, transport = transport)
    }
}
