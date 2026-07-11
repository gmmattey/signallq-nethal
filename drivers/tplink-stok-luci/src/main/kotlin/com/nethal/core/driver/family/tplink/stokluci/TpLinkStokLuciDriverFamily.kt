package com.nethal.core.driver.family.tplink.stokluci

import com.nethal.core.catalog.CapabilityActionResult
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
import com.nethal.core.model.DosProtectionThreshold
import com.nethal.core.model.DosProtectionThresholds
import com.nethal.core.model.LanStatus
import com.nethal.core.model.MeshTopology
import com.nethal.core.model.MeshTopologyNode
import com.nethal.core.model.NativeDiagnosticPingRequest
import com.nethal.core.model.NativeDiagnosticPingResult
import com.nethal.core.model.WanStatus
import com.nethal.core.model.WifiBand
import com.nethal.core.model.WifiRadio
import com.nethal.core.model.WifiStatus
import com.nethal.core.model.WifiTxPower
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
 * **issues #31-#34 (topologia mesh, canal/potência real por rádio, thresholds DoS)**:
 * [readCapability] passa a rotear entre três endpoints desta plataforma, não só o de status —
 * `READ_MESH_TOPOLOGY` ([TpLinkStokLuciMeshTopologyParser], `admin/onemesh_network?form=mesh_topology`)
 * e `READ_DOS_PROTECTION_THRESHOLDS` ([TpLinkStokLuciDosThresholdsParser],
 * `admin/security_settings?form=dos_setting`) usam endpoints próprios
 * ([TpLinkStokLuciDriverConfig.meshTopologyPath]/[TpLinkStokLuciDriverConfig.dosSettingPath]);
 * `READ_WIFI_RADIOS` reaproveita o mesmo endpoint/payload de `READ_WIFI_STATUS` (mesma leitura,
 * capability distinta — ver KDoc de `CapabilityId.READ_WIFI_RADIOS` no core). **Bug real
 * encontrado/corrigido em 2026-07-11 (issue #125)**: uma primeira tentativa de validação ao vivo
 * destas três leituras (mais o ping abaixo) foi bloqueada porque toda chamada autenticada da unidade
 * passou a falhar com HTTP 403, incluindo o endpoint de status já confirmado em 2026-07-07. Causa
 * raiz encontrada por leitura de código (não nova captura ao vivo): `seq` do envelope `sign` era
 * reusado sem avançar entre o login e cada leitura autenticada seguinte — ver KDoc de
 * [TpLinkStokLuciAuthenticationClient] (`SessionEncryptorContext`/`fetchAuthenticatedRaw`) para o
 * detalhe completo da correção. **Nota honesta sobre "já confirmado em 2026-07-07" acima**: essa
 * afirmação vinha do `docs/drivers/live-evidence/tplink-archer-c6-stok-v1.json`, cuja evidência é uma
 * captura de tráfego do NAVEGADOR (Playwright) confirmando a FORMA do protocolo — não uma execução
 * bem-sucedida do `tplinkC6StokManualCheck` (este driver Kotlin) contra o hardware real. Não há
 * registro no changelog de `docs/drivers/compatibility-catalog.md` de uma corrida real do driver com
 * leitura autenticada assinada (`sign=`/`data=`, adicionada só em `d5b2181`) tendo sido validada
 * contra o equipamento antes da rodada desta issue — plausível que este bug de `seq` sempre tenha
 * existido desde então e nunca tivesse sido exercitado de verdade contra hardware físico até agora.
 * Ainda sem confirmação por evidência ao vivo da correção — próximo passo é rodar
 * `tplinkC6StokManualCheck` de novo contra o Archer C6 do Luiz.
 *
 * **issue #26 (RUN_NATIVE_DIAGNOSTIC_PING, TP-Link Archer C6 apenas)**: [runNativeDiagnosticPing]
 * dispara um teste de ping real a partir do próprio equipamento (`admin/diag?form=diag`) —
 * capability de AÇÃO, não leitura, classificada assim na Task #24. Restrita a este driver por
 * decisão de produto do Rafael; a versão Nokia (issue #25) fica pausada em backlog até revisão de
 * segurança separada liberar. Sem confirmação por evidência ao vivo do formato do resultado nem do
 * fluxo write/read completo — mesmo bug de sessão da issue #125 acima bloqueou a validação real
 * desta rodada (corrigido nesta mesma rodada, ainda sem reteste ao vivo).
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
 * **issues #95/#103 (`REBOOT_DEVICE`, TP-Link Archer C6 apenas)**: [executeAction] implementa a
 * primeira capability de AÇÃO/escrita "genérica" do produto (fora do fluxo de autenticação) —
 * reinicia o equipamento via `config.rebootPath`/`config.rebootQuery` (`admin/system?form=reboot`),
 * reaproveitando [authenticatedClient] (mesma sessão de [readCapability], não login novo por
 * chamada). Restrita a este driver por decisão de produto do Rafael/Luiz — nunca no Archer C20 nem
 * no Nokia, mesmo que o desenho de `DriverFamily.executeAction` permitisse tecnicamente nos dois
 * (nenhuma outra Driver Family deste repositório sobrescreve este método). Confirmação explícita do
 * usuário é responsabilidade da UI (`:feature:tools-reboot-wan`) antes de sequer chamar
 * `CapabilityEngine.executeAction` — este método nunca pergunta, só executa. Sem confirmação por
 * evidência ao vivo (mesma regressão de sessão HTTP 403 de #125 acima bloqueia qualquer teste real);
 * nenhum reboot real foi disparado contra o hardware do Luiz durante esta implementação.
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
            when (id) {
                CapabilityId.READ_MESH_TOPOLOGY ->
                    when (val outcome = fetchRawOrError(client, config.meshTopologyPath, config.meshTopologyQuery, id)) {
                        is RawFetchOutcome.Error -> outcome.result
                        is RawFetchOutcome.Success -> meshTopologyResultFor(TpLinkStokLuciMeshTopologyParser.parse(outcome.rawBody))
                    }
                CapabilityId.READ_DOS_PROTECTION_THRESHOLDS ->
                    when (val outcome = fetchRawOrError(client, config.dosSettingPath, config.dosSettingQuery, id)) {
                        is RawFetchOutcome.Error -> outcome.result
                        is RawFetchOutcome.Success -> dosThresholdsResultFor(TpLinkStokLuciDosThresholdsParser.parse(outcome.rawBody))
                    }
                else ->
                    when (val outcome = fetchRawOrError(client, config.statusReadPath, config.statusReadQuery, id)) {
                        is RawFetchOutcome.Error -> outcome.result
                        is RawFetchOutcome.Success -> capabilityResultFor(id, TpLinkStokLuciStatusParser.parseSnapshot(outcome.rawBody))
                    }
            }
        }
    }

    /** Resultado interno de uma leitura autenticada crua — usado por [readCapability] para rotear entre os endpoints desta plataforma sem duplicar o mesmo try/catch três vezes. */
    private sealed interface RawFetchOutcome {
        data class Success(val rawBody: String) : RawFetchOutcome
        data class Error(val result: CapabilityReadResult) : RawFetchOutcome
    }

    private fun fetchRawOrError(client: TpLinkStokLuciAuthenticationClient, path: String, query: String, id: CapabilityId): RawFetchOutcome =
        try {
            RawFetchOutcome.Success(client.fetchAuthenticated(path, query))
        } catch (e: TpLinkStokLuciLoginException) {
            RawFetchOutcome.Error(
                if (e.reason == TpLinkStokLuciLoginFailureReason.SESSION_EXPIRED) {
                    CapabilityReadResult.SessionExpired(reason = e.message ?: "sessão expirada ao ler $id")
                } else {
                    CapabilityReadResult.Failure(reason = e.message ?: "falha inesperada ao ler $id", cause = e)
                },
            )
        } catch (e: IOException) {
            RawFetchOutcome.Error(CapabilityReadResult.Failure(reason = e.message ?: "falha de rede ao ler $id", cause = e))
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
        // READ_WIFI_RADIOS (issue #33) reaproveita a mesma leitura/payload de READ_WIFI_STATUS —
        // ver KDoc de CapabilityId.READ_WIFI_RADIOS para a decisão registrada de não criar uma
        // terceira capability para canal-em-uso/potência de transmissão.
        CapabilityId.READ_WIFI_STATUS, CapabilityId.READ_WIFI_RADIOS -> if (snapshot.wifi.isEmpty()) {
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
                                currentChannel = radio.currentChannel,
                                txPower = when (radio.txPower) {
                                    TpLinkStokLuciTxPower.HIGH -> WifiTxPower.HIGH
                                    TpLinkStokLuciTxPower.MIDDLE -> WifiTxPower.MIDDLE
                                    TpLinkStokLuciTxPower.LOW -> WifiTxPower.LOW
                                    TpLinkStokLuciTxPower.UNKNOWN -> WifiTxPower.UNKNOWN
                                    null -> null
                                },
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

    /** Traduz [TpLinkStokLuciMeshTopologyParser] para `CapabilityPayload.MeshTopology` — cobre `READ_MESH_TOPOLOGY` (issue #32). Lista de clientes vazia é dado real (nenhum nó mesh reportado agora), não ausência de dado — mesmo raciocínio de `READ_CONNECTED_CLIENTS`. */
    private fun meshTopologyResultFor(topology: TpLinkStokLuciMeshTopology): CapabilityReadResult = CapabilityReadResult.Success(
        capability = Capability(id = CapabilityId.READ_MESH_TOPOLOGY, state = CapabilityState.AVAILABLE, confidence = 1.0),
        payload = CapabilityPayload.MeshTopology(
            MeshTopology(
                routerModel = topology.routerModel,
                routerName = topology.routerName,
                routerMacAddress = topology.routerMacAddress,
                clients = topology.clients.map { node ->
                    MeshTopologyNode(
                        macAddress = node.macAddress,
                        hostname = node.hostname,
                        ipAddress = node.ipAddress,
                        wireType = node.wireType,
                        guestNetwork = node.guestNetwork,
                        accessTimeEpochSeconds = node.accessTimeEpochSeconds,
                    )
                },
                satelliteNodeCount = topology.satelliteNodeCount,
            ),
        ),
    )

    /** Traduz [TpLinkStokLuciDosThresholdsParser] para `CapabilityPayload.DosProtectionThresholds` — cobre `READ_DOS_PROTECTION_THRESHOLDS` (issue #34), leitura pura de configuração já existente no equipamento. */
    private fun dosThresholdsResultFor(thresholds: TpLinkStokLuciDosThresholds): CapabilityReadResult = CapabilityReadResult.Success(
        capability = Capability(id = CapabilityId.READ_DOS_PROTECTION_THRESHOLDS, state = CapabilityState.AVAILABLE, confidence = 1.0),
        payload = CapabilityPayload.DosProtectionThresholds(
            DosProtectionThresholds(
                icmp = DosProtectionThreshold(thresholds.icmp.low, thresholds.icmp.middle, thresholds.icmp.high),
                syn = DosProtectionThreshold(thresholds.syn.low, thresholds.syn.middle, thresholds.syn.high),
                udp = DosProtectionThreshold(thresholds.udp.low, thresholds.udp.middle, thresholds.udp.high),
            ),
        ),
    )

    /**
     * Diagnóstico nativo de ping (issue #26, capability de AÇÃO `RUN_NATIVE_DIAGNOSTIC_PING`) —
     * dispara um teste real no equipamento, não é leitura passiva. Por isso **não** passa pelo
     * `readCapability(id)`/`CapabilityEngine` genérico (sem shape de request ali) — login novo a
     * cada chamada, mesmo desenho de [readStatusRaw]/[readSnapshot] (métodos anteriores ao
     * Capability Engine), escolhido de propósito aqui: esta é a primeira execução real desta
     * capability contra hardware físico, e reaproveitar a sessão de [authenticatedClient] traria
     * risco adicional sem necessidade (nenhuma outra capability desta rodada depende de ping
     * encadeado numa mesma sessão).
     *
     * Protocolo assumido (sem confirmação por evidência ao vivo até a primeira execução real via
     * `ManualCheckRunner`): (1) `operation=write` em `config.diagPath?form=diag` (mais parâmetros
     * anexados via [config.diagQuery]) dispara o teste; (2) uma segunda chamada `operation=read` no
     * mesmo endpoint lê o campo `result` já preenchido. Os dois passos usam a MESMA sessão
     * (`TpLinkStokLuciAuthenticationClient` local desta chamada) — falha em qualquer um deles aborta
     * sem tentar o outro.
     */
    suspend fun runNativeDiagnosticPing(
        username: String,
        password: String,
        request: NativeDiagnosticPingRequest,
    ): TpLinkStokLuciPingOutcome = withContext(Dispatchers.IO) {
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

            val writeBody = buildString {
                append("operation=write")
                append("&type=0") // 0 = ping, único tipo coberto nesta rodada (traceroute fora de escopo)
                append("&ipaddr=").append(java.net.URLEncoder.encode(request.targetHost, "UTF-8"))
                append("&count=").append(request.packetCount)
                request.packetSizeBytes?.let { append("&pktsize=").append(it) }
                request.timeoutMillis?.let { append("&timeout=").append(it) }
                request.ttl?.let { append("&ttl=").append(it) }
            }
            client.fetchAuthenticatedRaw(config.diagPath, config.diagQuery, writeBody)
            val readBody = client.fetchAuthenticatedRaw(config.diagPath, config.diagQuery, "operation=read")
            TpLinkStokLuciDiagPingParser.parse(readBody)
        }

        when (outcome) {
            is RetryOutcome.Success -> TpLinkStokLuciPingOutcome.Success(
                NativeDiagnosticPingResult(
                    packetsSent = outcome.value.packetsSent,
                    packetsReceived = outcome.value.packetsReceived,
                    packetLossPercent = outcome.value.packetLossPercent,
                    roundTripTimesMillis = outcome.value.roundTripTimesMillis,
                    averageRoundTripMillis = outcome.value.averageRoundTripMillis,
                    timedOut = outcome.value.timedOut,
                    rawResultText = outcome.value.rawResultText,
                ),
            )
            is RetryOutcome.Failure -> TpLinkStokLuciPingOutcome.Failure(outcome.reason, outcome.error.message ?: outcome.error.toString())
        }
    }

    /**
     * Implementação de [DriverFamily.executeAction] — hoje reconhece só `REBOOT_DEVICE` (issues
     * #95/#103), a primeira capability de AÇÃO/escrita "genérica" do produto (fora do fluxo de
     * autenticação) com execução real. Qualquer outro `id` cai no default honesto `Unavailable`
     * (mesmo texto do `DriverFamily.executeAction` base, mais específico por driver) — é assim,
     * estruturalmente, que esta capability fica restrita **só** a este driver (decisão de produto do
     * Rafael): nenhuma outra `DriverFamily` deste repositório sobrescreve este método, então a
     * implementação default (`Unavailable`) é tudo o que Archer C20/Nokia G-1425G-B expõem para
     * `REBOOT_DEVICE` — nunca um `if (vendor == ...)` em código compartilhado.
     *
     * Usa [authenticatedClient] (sessão já aberta via [authenticate]/[com.nethal.core.capability.CapabilityEngine]),
     * mesmo padrão de [readCapability] — diferente de [runNativeDiagnosticPing] (que faz login novo a
     * cada chamada): reboot é chamado pela UI através do `CapabilityEngine` já autenticado pela tela
     * de sessão (mesmo fluxo de `readCapability`), não tem motivo para duplicar handshake.
     *
     * **Sem retry automático de propósito** (diferente de [readCapability]/[runNativeDiagnosticPing],
     * que usam [executeWithRetry]): reenviar automaticamente uma ação que MUDA O ESTADO do
     * equipamento em caso de falha de rede arrisca disparar dois reboots reais por uma falha só de
     * leitura da resposta — pior que devolver `Failure` e deixar o usuário decidir se tenta de novo
     * (o mesmo raciocínio conservador de todo o NetHAL, aplicado aqui com mais peso por ser uma ação
     * disruptiva de verdade).
     *
     * Protocolo assumido, **sem confirmação por evidência ao vivo** (`config.rebootPath`/
     * `config.rebootQuery`, ver KDoc de [TpLinkStokLuciDriverConfig.rebootPath]): um único
     * `operation=write` no endpoint de reboot. Não há passo de leitura de confirmação (diferente do
     * diagnóstico de ping, que lê `result` depois de escrever) — nenhum campo de status de reboot foi
     * mapeado para este firmware; a única confirmação possível hoje é a resposta HTTP 200 do próprio
     * `write`. A regressão de sessão HTTP 403 documentada em `fingerprintEvidence[]`/issue #125
     * impede qualquer validação ao vivo real desta implementação no momento — capability entra
     * `EXPERIMENTAL` no catálogo por esse motivo, não por dúvida sobre a restrição de driver.
     */
    override suspend fun executeAction(id: CapabilityId): CapabilityActionResult {
        if (id != CapabilityId.REBOOT_DEVICE) {
            return CapabilityActionResult.Unavailable(
                reason = "TpLinkStokLuciDriverFamily não implementa a ação $id nesta rodada.",
            )
        }
        val client = authenticatedClient
            ?: return CapabilityActionResult.Unavailable(
                reason = "Reiniciar o equipamento ($id) exige sessão autenticada — chame authenticate(username, password) " +
                    "(via CapabilityEngine) antes de executar ações.",
            )

        return withContext(Dispatchers.IO) {
            try {
                client.fetchAuthenticatedRaw(config.rebootPath, config.rebootQuery, "operation=write")
                CapabilityActionResult.Success(
                    capability = Capability(id = CapabilityId.REBOOT_DEVICE, state = CapabilityState.AVAILABLE, confidence = 1.0),
                )
            } catch (e: TpLinkStokLuciLoginException) {
                if (e.reason == TpLinkStokLuciLoginFailureReason.SESSION_EXPIRED) {
                    CapabilityActionResult.SessionExpired(reason = e.message ?: "sessão expirada ao executar $id")
                } else {
                    CapabilityActionResult.Failure(reason = e.message ?: "falha inesperada ao executar $id", cause = e)
                }
            } catch (e: IOException) {
                CapabilityActionResult.Failure(reason = e.message ?: "falha de rede ao executar $id", cause = e)
            }
        }
    }

    private fun classifyFailure(error: Throwable): TpLinkStokLuciFailureReason = when (classifyNetworkFailure(error)) {
        NetworkFailureReason.DEVICE_UNREACHABLE -> TpLinkStokLuciFailureReason.DEVICE_UNREACHABLE
        NetworkFailureReason.TIMEOUT -> TpLinkStokLuciFailureReason.TIMEOUT
        NetworkFailureReason.UNEXPECTED_RESPONSE -> TpLinkStokLuciFailureReason.UNEXPECTED_RESPONSE
        NetworkFailureReason.COMMUNICATION_ERROR -> TpLinkStokLuciFailureReason.COMMUNICATION_ERROR
    }

    companion object {
        /**
         * Capabilities com parser estruturado real a partir de leituras autenticadas desta
         * plataforma. `READ_DEVICE_INFO`/`READ_FIRMWARE` ficam de fora porque nenhum campo de
         * modelo/firmware foi confirmado em nenhum endpoint até aqui. `RUN_NATIVE_DIAGNOSTIC_PING`
         * (issue #26) fica de fora de propósito — é capability de AÇÃO, não flui por
         * `readCapability(id)` (ver [runNativeDiagnosticPing]).
         */
        val SUPPORTED_CAPABILITIES: Set<CapabilityId> = setOf(
            CapabilityId.READ_WIFI_STATUS,
            CapabilityId.READ_WIFI_RADIOS,
            CapabilityId.READ_LAN_STATUS,
            CapabilityId.READ_WAN_STATUS,
            CapabilityId.READ_CONNECTED_CLIENTS,
            CapabilityId.READ_MESH_TOPOLOGY,
            CapabilityId.READ_DOS_PROTECTION_THRESHOLDS,
        )
    }
}

/** Resultado de [TpLinkStokLuciDriverFamily.runNativeDiagnosticPing] (issue #26). */
internal sealed interface TpLinkStokLuciPingOutcome {
    data class Success(val result: NativeDiagnosticPingResult) : TpLinkStokLuciPingOutcome
    data class Failure(val reason: TpLinkStokLuciFailureReason, val message: String) : TpLinkStokLuciPingOutcome
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
