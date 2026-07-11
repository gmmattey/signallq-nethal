package com.nethal.core.driver.family.tplink.gdprcgi

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
import com.nethal.core.model.WifiBand
import com.nethal.core.model.WifiRadio
import com.nethal.core.model.WifiStatus
import com.nethal.core.protocol.http.HttpTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class TpLinkGdprCgiFailureReason {
    DEVICE_UNREACHABLE,
    TIMEOUT,
    UNEXPECTED_RESPONSE,
    INVALID_CREDENTIALS,
    COMMUNICATION_ERROR,
}

internal sealed interface TpLinkGdprCgiLoginOutcome {
    data class Success(val session: TpLinkGdprCgiSession) : TpLinkGdprCgiLoginOutcome
    data class Failure(val reason: TpLinkGdprCgiFailureReason, val message: String) : TpLinkGdprCgiLoginOutcome
}

internal sealed interface TpLinkGdprCgiReadOutcome {
    data class Success(val rawBody: String) : TpLinkGdprCgiReadOutcome
    data class Failure(val reason: TpLinkGdprCgiFailureReason, val message: String) : TpLinkGdprCgiReadOutcome
}

/**
 * Driver Family da plataforma `tplink-gdpr-cgi` (`platformId`/`driverFamilyId` do catálogo — ramo
 * `/cgi_gdpr`, handshake RSA+AES em variantes CBC/GCM, ver
 * `docs/drivers/tplink-mercusys-families-2026-07-07.md`).
 *
 * **Sem hardware físico validado para esta família** (issue #20/plano de fundação HAL, item 1.4,
 * decisão do Luiz 2026-07-10) — diferente de `TpLinkStokLuciDriverFamily`, todo o parsing de
 * capability aqui é inferência disclosed a partir de documentação de protocolo (login) e do formato
 * de dispatcher clássico compartilhado com `tplink-legacy-cgi` (leitura), nunca captura real contra
 * este ramo. Por isso [readCapability] nunca devolve `CapabilityState.AVAILABLE` — no máximo
 * `EXPERIMENTAL`, mesmo quando o parser "funciona" contra um fixture de teste sintético (ver
 * [capabilityResultFor]). Promoção de estágio do profile correspondente é bloqueada até evidência de
 * device real, conforme `/ciclo-vida-driver`.
 *
 * [authenticate]/[readCapability] seguem o mesmo desenho de sessão cacheada de
 * `TpLinkStokLuciDriverFamily` (issue #16): a sessão vive em [authenticatedClient], preenchida por
 * [authenticate] e reaproveitada por [readCapability] sem novo login a cada leitura.
 * [login]/[readRaw] continuam com o desenho anterior (login novo a cada chamada) de propósito — são
 * os métodos com cobertura de teste já existente (`TpLinkGdprCgiDriverFamilyTest`), não tocados para
 * não arriscar o único caminho já validado (contra fake de transporte).
 */
internal class TpLinkGdprCgiDriverFamily(
    private val host: String,
    private val config: TpLinkGdprCgiDriverConfig,
    private val transport: HttpTransport,
    private val maxAttempts: Int = 2,
    private val backoffMillis: (attempt: Int) -> Long = { attempt -> 1_000L * attempt },
) : DriverFamily {

    init {
        require(PrivateIpRanges.isPrivate(host)) {
            "TpLinkGdprCgiDriverFamily só opera contra IP privado (RFC 1918) da LAN; host recebido: $host"
        }
    }

    /** Sessão autenticada corrente, preenchida por [authenticate] — `null` até o primeiro login bem-sucedido. */
    private var authenticatedClient: TpLinkGdprCgiAuthenticationClient? = null

    suspend fun login(username: String, password: String): TpLinkGdprCgiLoginOutcome = withContext(Dispatchers.IO) {
        val outcome = executeWithRetry(
            maxAttempts = maxAttempts,
            backoffMillis = backoffMillis,
            loginExceptionType = TpLinkGdprCgiLoginException::class.java,
            onLoginFailure = { e ->
                when (e.reason) {
                    TpLinkGdprCgiLoginFailureReason.INVALID_CREDENTIALS -> TpLinkGdprCgiFailureReason.INVALID_CREDENTIALS
                    TpLinkGdprCgiLoginFailureReason.AUTH_ENDPOINT_UNAVAILABLE,
                    TpLinkGdprCgiLoginFailureReason.UNEXPECTED_RESPONSE,
                    -> null
                }
            },
            classifyFinalFailure = ::classifyFailure,
        ) {
            TpLinkGdprCgiAuthenticationClient(host, config, transport).login(username, password)
        }
        when (outcome) {
            is RetryOutcome.Success -> TpLinkGdprCgiLoginOutcome.Success(outcome.value)
            is RetryOutcome.Failure -> TpLinkGdprCgiLoginOutcome.Failure(
                outcome.reason,
                outcome.error.message ?: outcome.error.toString(),
            )
        }
    }

    suspend fun readRaw(username: String, password: String): TpLinkGdprCgiReadOutcome = withContext(Dispatchers.IO) {
        val outcome = executeWithRetry(
            maxAttempts = maxAttempts,
            backoffMillis = backoffMillis,
            loginExceptionType = TpLinkGdprCgiLoginException::class.java,
            onLoginFailure = { e ->
                when (e.reason) {
                    TpLinkGdprCgiLoginFailureReason.INVALID_CREDENTIALS -> TpLinkGdprCgiFailureReason.INVALID_CREDENTIALS
                    TpLinkGdprCgiLoginFailureReason.AUTH_ENDPOINT_UNAVAILABLE,
                    TpLinkGdprCgiLoginFailureReason.UNEXPECTED_RESPONSE,
                    -> null
                }
            },
            classifyFinalFailure = ::classifyFailure,
        ) {
            val client = TpLinkGdprCgiAuthenticationClient(host, config, transport)
            client.login(username, password)
            client.fetchAuthenticatedRaw(config.authenticatedReadPath, config.authenticatedReadPlaintext)
        }
        when (outcome) {
            is RetryOutcome.Success -> TpLinkGdprCgiReadOutcome.Success(outcome.value)
            is RetryOutcome.Failure -> TpLinkGdprCgiReadOutcome.Failure(
                outcome.reason,
                outcome.error.message ?: outcome.error.toString(),
            )
        }
    }

    /**
     * Implementação de [DriverFamily.authenticate] — mesmo handshake de [login], mas guarda o
     * [TpLinkGdprCgiAuthenticationClient] resultante em [authenticatedClient] em vez de descartá-lo,
     * para [readCapability] reaproveitar sessão/contexto de criptografia entre chamadas.
     */
    override suspend fun authenticate(username: String, password: String): DriverFamilyAuthResult = withContext(Dispatchers.IO) {
        val outcome = executeWithRetry(
            maxAttempts = maxAttempts,
            backoffMillis = backoffMillis,
            loginExceptionType = TpLinkGdprCgiLoginException::class.java,
            onLoginFailure = { e ->
                when (e.reason) {
                    TpLinkGdprCgiLoginFailureReason.INVALID_CREDENTIALS -> TpLinkGdprCgiFailureReason.INVALID_CREDENTIALS
                    TpLinkGdprCgiLoginFailureReason.AUTH_ENDPOINT_UNAVAILABLE,
                    TpLinkGdprCgiLoginFailureReason.UNEXPECTED_RESPONSE,
                    -> null
                }
            },
            classifyFinalFailure = ::classifyFailure,
        ) {
            val client = TpLinkGdprCgiAuthenticationClient(host, config, transport)
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
                if (outcome.reason == TpLinkGdprCgiFailureReason.INVALID_CREDENTIALS) {
                    DriverFamilyAuthResult.InvalidCredentials(message)
                } else {
                    DriverFamilyAuthResult.Failure(message)
                }
            }
        }
    }

    /**
     * Implementação de [DriverFamily.readCapability] — usa a sessão de [authenticatedClient] para
     * pedir a seção configurada em `config.capabilitySections` para [id] (se houver) e parsear o
     * corpo decifrado ([TpLinkGdprCgiResponseParser.parseStackFields]).
     *
     * Escopo restrito a [TpLinkGdprCgiLoginStyle.C50_GDPR_BODY_LOGIN]: é o único estilo com gramática
     * de leitura documentada/inferível (ver KDoc de [TpLinkGdprCgiCapabilitySection]) — `MR_QUERY_LOGIN`
     * e `EX_JSON_GDPR_BODY_LOGIN` usam envelopes de leitura diferentes (query string / JSON), sem
     * nenhuma evidência de formato de resposta em `docs/drivers/tplink-mercusys-families-2026-07-07.md`;
     * implementar parsing para eles agora seria inventar formato sem base, proibido pela regra do
     * projeto. Sem sessão ativa, resposta honesta é [CapabilityReadResult.Unavailable] — o caminho
     * normal para autenticar é via `CapabilityEngine`, não chamando este método direto.
     */
    override suspend fun readCapability(id: CapabilityId): CapabilityReadResult {
        if (id !in SUPPORTED_CAPABILITIES) {
            return CapabilityReadResult.Unavailable(
                reason = "TpLinkGdprCgiDriverFamily não implementa parsing para $id nesta rodada.",
            )
        }
        if (config.loginStyle != TpLinkGdprCgiLoginStyle.C50_GDPR_BODY_LOGIN) {
            return CapabilityReadResult.Unavailable(
                reason = "Parser experimental de $id só está implementado para o estilo de login " +
                    "C50_GDPR_BODY_LOGIN (dispatcher clássico /cgi cifrado); este profile usa " +
                    "${config.loginStyle}, sem formato de leitura documentado/inferível ainda.",
            )
        }
        val section = config.capabilitySections.find { it.capabilityId == id.name }
            ?: return CapabilityReadResult.Unavailable(
                reason = "Nenhuma seção de leitura configurada no catálogo (driverConfig.capabilitySections) " +
                    "para $id nesta rodada.",
            )
        val client = authenticatedClient
            ?: return CapabilityReadResult.Unavailable(
                reason = "Leitura de $id exige sessão autenticada — chame authenticate(username, password) " +
                    "(via CapabilityEngine) antes de ler capabilities.",
            )

        return withContext(Dispatchers.IO) {
            val rawBody = try {
                client.fetchAuthenticatedRaw(config.authenticatedReadPath, buildStackReadPlaintext(section))
            } catch (e: TpLinkGdprCgiLoginException) {
                return@withContext CapabilityReadResult.Failure(reason = e.message ?: "falha inesperada ao ler $id", cause = e)
            } catch (e: java.io.IOException) {
                return@withContext CapabilityReadResult.Failure(reason = e.message ?: "falha de rede ao ler $id", cause = e)
            }
            capabilityResultFor(id, TpLinkGdprCgiResponseParser.parseStackFields(rawBody))
        }
    }

    /** Corpo de leitura para uma [TpLinkGdprCgiCapabilitySection] — mesma gramática já usada em `authenticatedReadPlaintext` de teste (`"1\r\n[oid#stack]0,qtd\r\ncampo\r\n..."`), generalizada por `oid`/`fields` em vez de hardcoded. */
    private fun buildStackReadPlaintext(section: TpLinkGdprCgiCapabilitySection): String = buildString {
        append("1\r\n[")
        append(section.oid)
        append("#0,0,0,0,0,0#0,0,0,0,0,0]0,")
        append(section.fields.size)
        append("\r\n")
        section.fields.forEach { field -> append(field).append("\r\n") }
    }

    /**
     * Traduz os campos brutos já extraídos ([TpLinkGdprCgiResponseParser.parseStackFields]) para o
     * vocabulário de capabilities do NetHAL. **Nunca `AVAILABLE`** — `EXPERIMENTAL` mesmo quando o
     * parsing encontra os campos esperados, porque nem a gramática de leitura nem os nomes de campo
     * (herdados de `tplink-legacy-cgi` por analogia, ver KDoc de [TpLinkGdprCgiCapabilitySection])
     * foram confirmados contra hardware real desta família — ver `reason` explícito em
     * [EXPERIMENTAL_REASON]. Campo ausente vira [CapabilityReadResult.Unavailable] (nunca inventa
     * dado).
     */
    private fun capabilityResultFor(id: CapabilityId, fields: Map<String, String>): CapabilityReadResult = when (id) {
        CapabilityId.READ_WIFI_STATUS -> {
            val ssid = fields["SSID"]
            if (ssid.isNullOrBlank()) {
                CapabilityReadResult.Unavailable(reason = "Campo SSID ausente na seção lida — sem dado interpretável.")
            } else {
                CapabilityReadResult.Success(
                    capability = Capability(id = id, state = CapabilityState.EXPERIMENTAL, confidence = 0.3, reason = EXPERIMENTAL_REASON),
                    payload = CapabilityPayload.Wifi(
                        WifiStatus(
                            radios = listOf(
                                WifiRadio(id = fields["name"] ?: "wlan0", band = WifiBand.UNKNOWN, ssid = ssid, channel = null),
                            ),
                        ),
                    ),
                )
            }
        }
        CapabilityId.READ_CONNECTED_CLIENTS -> {
            val ipAddress = fields["IPAddress"]
            if (ipAddress.isNullOrBlank()) {
                CapabilityReadResult.Unavailable(reason = "Campo IPAddress ausente na seção lida — sem dado interpretável.")
            } else {
                CapabilityReadResult.Success(
                    capability = Capability(id = id, state = CapabilityState.EXPERIMENTAL, confidence = 0.3, reason = EXPERIMENTAL_REASON),
                    payload = CapabilityPayload.ConnectedClients(
                        ConnectedClientList(
                            clients = listOf(
                                ConnectedClient(hostname = fields["hostName"], ipAddress = ipAddress, macAddress = fields["MACAddress"]),
                            ),
                        ),
                    ),
                )
            }
        }
        else -> CapabilityReadResult.Unavailable(reason = "TpLinkGdprCgiDriverFamily não implementa parsing para $id nesta rodada.")
    }

    private fun classifyFailure(error: Throwable): TpLinkGdprCgiFailureReason = when (classifyNetworkFailure(error)) {
        NetworkFailureReason.DEVICE_UNREACHABLE -> TpLinkGdprCgiFailureReason.DEVICE_UNREACHABLE
        NetworkFailureReason.TIMEOUT -> TpLinkGdprCgiFailureReason.TIMEOUT
        NetworkFailureReason.UNEXPECTED_RESPONSE -> TpLinkGdprCgiFailureReason.UNEXPECTED_RESPONSE
        NetworkFailureReason.COMMUNICATION_ERROR -> TpLinkGdprCgiFailureReason.COMMUNICATION_ERROR
    }

    companion object {
        /** Nunca `AVAILABLE` para esta família (ver KDoc da classe) — motivo padrão citado em todo `Capability.reason` != null. */
        private const val EXPERIMENTAL_REASON = "Parser implementado a partir de documentação de protocolo " +
            "(dialeto de dispatcher clássico /cgi compartilhado com tplink-legacy-cgi), sem validação contra " +
            "hardware real desta família — não usar como fonte de verdade até confirmação física."

        /** Capabilities com seção configurável em `driverConfig.capabilitySections` — ver [capabilityResultFor]. */
        val SUPPORTED_CAPABILITIES: Set<CapabilityId> = setOf(
            CapabilityId.READ_WIFI_STATUS,
            CapabilityId.READ_CONNECTED_CLIENTS,
        )
    }
}

class TpLinkGdprCgiDriverFamilyFactory : DriverFamilyFactory {
    override val familyId: String = "tplink-gdpr-cgi-driver"

    override fun create(profile: CompatibilityProfile, host: String, transport: HttpTransport): DriverFamily {
        val config = TpLinkGdprCgiDriverConfig.fromJsonElement(profile.driverConfig)
        return TpLinkGdprCgiDriverFamily(host = host, config = config, transport = transport)
    }
}
