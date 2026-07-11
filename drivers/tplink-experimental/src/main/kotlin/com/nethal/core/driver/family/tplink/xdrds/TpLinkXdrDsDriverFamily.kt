package com.nethal.core.driver.family.tplink.xdrds

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
import com.nethal.core.model.CapabilityId
import com.nethal.core.protocol.http.HttpTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class TpLinkXdrDsFailureReason {
    DEVICE_UNREACHABLE,
    TIMEOUT,
    UNEXPECTED_RESPONSE,
    INVALID_CREDENTIALS,
    COMMUNICATION_ERROR,
}

internal sealed interface TpLinkXdrDsLoginOutcome {
    data class Success(val session: TpLinkXdrDsSession) : TpLinkXdrDsLoginOutcome
    data class Failure(val reason: TpLinkXdrDsFailureReason, val message: String) : TpLinkXdrDsLoginOutcome
}

internal sealed interface TpLinkXdrDsReadOutcome {
    data class Success(val rawBody: String) : TpLinkXdrDsReadOutcome
    data class Failure(val reason: TpLinkXdrDsFailureReason, val message: String) : TpLinkXdrDsReadOutcome
}

/**
 * Driver Family da plataforma `tplink-xdr-ds` (`platformId`/`driverFamilyId` do catálogo — ramo
 * XDR/R baseado em `/stok={stok}/ds`, ver `docs/drivers/tplink-mercusys-families-2026-07-07.md`).
 *
 * **Sem hardware físico validado para esta família** (issue #20/plano de fundação HAL, item 1.4,
 * decisão do Luiz 2026-07-10). [authenticate] segue o mesmo desenho de sessão cacheada de
 * `TpLinkStokLuciDriverFamily`/`TpLinkGdprCgiDriverFamily` (issue #16).
 *
 * [readCapability], diferente do `tplink-gdpr-cgi`, **não** mapeia nenhum campo de capability para
 * `AVAILABLE`/`EXPERIMENTAL` nesta rodada: o único campo confirmado em todo o envelope JSON de
 * `/ds` é `error_code` (usado no probe `get_encrypt_info` do login) — nenhum nome de campo de
 * Wi-Fi/LAN/WAN/clientes foi documentado nem tem analogia confiável com outra família já
 * implementada (diferente do `tplink-gdpr-cgi`, que compartilha gramática confirmada com
 * `tplink-legacy-cgi`; a superfície JSON de `/ds` é uma API própria, sem parentesco de protocolo
 * conhecido com nenhuma outra família do NetHAL). Inventar nome de campo aqui violaria a regra do
 * projeto ("não prometa mais do que a evidência sustenta") — ver
 * `docs/drivers/tplink-mercusys-families-2026-07-07.md`, seção "Próximos passos de validação
 * física", item 4 ("capturar payload bruto de leitura... e só então promover parsers"). Por isso
 * [readCapability] executa a leitura autenticada real (prova que sessão/transporte funcionam) e
 * inspeciona `error_code`, mas devolve [CapabilityReadResult.Unavailable] para toda capability,
 * com motivo distinguindo "leitura funcionou, campo desconhecido" de "leitura falhou".
 */
internal class TpLinkXdrDsDriverFamily(
    private val host: String,
    private val config: TpLinkXdrDsDriverConfig,
    private val transport: HttpTransport,
    private val maxAttempts: Int = 2,
    private val backoffMillis: (attempt: Int) -> Long = { attempt -> 1_000L * attempt },
) : DriverFamily {

    init {
        require(PrivateIpRanges.isPrivate(host)) {
            "TpLinkXdrDsDriverFamily só opera contra IP privado (RFC 1918) da LAN; host recebido: $host"
        }
    }

    /** Sessão autenticada corrente, preenchida por [authenticate] — `null` até o primeiro login bem-sucedido. */
    private var authenticatedClient: TpLinkXdrDsAuthenticationClient? = null

    suspend fun login(username: String, password: String): TpLinkXdrDsLoginOutcome = withContext(Dispatchers.IO) {
        val outcome = executeWithRetry(
            maxAttempts = maxAttempts,
            backoffMillis = backoffMillis,
            loginExceptionType = TpLinkXdrDsLoginException::class.java,
            onLoginFailure = { e ->
                when (e.reason) {
                    TpLinkXdrDsLoginFailureReason.INVALID_CREDENTIALS -> TpLinkXdrDsFailureReason.INVALID_CREDENTIALS
                    TpLinkXdrDsLoginFailureReason.AUTH_ENDPOINT_UNAVAILABLE,
                    TpLinkXdrDsLoginFailureReason.UNEXPECTED_RESPONSE,
                    -> null
                }
            },
            classifyFinalFailure = ::classifyFailure,
        ) {
            TpLinkXdrDsAuthenticationClient(host, config, transport).login(username, password)
        }
        when (outcome) {
            is RetryOutcome.Success -> TpLinkXdrDsLoginOutcome.Success(outcome.value)
            is RetryOutcome.Failure -> TpLinkXdrDsLoginOutcome.Failure(
                outcome.reason,
                outcome.error.message ?: outcome.error.toString(),
            )
        }
    }

    suspend fun readRaw(username: String, password: String): TpLinkXdrDsReadOutcome = withContext(Dispatchers.IO) {
        val outcome = executeWithRetry(
            maxAttempts = maxAttempts,
            backoffMillis = backoffMillis,
            loginExceptionType = TpLinkXdrDsLoginException::class.java,
            onLoginFailure = { e ->
                when (e.reason) {
                    TpLinkXdrDsLoginFailureReason.INVALID_CREDENTIALS -> TpLinkXdrDsFailureReason.INVALID_CREDENTIALS
                    TpLinkXdrDsLoginFailureReason.AUTH_ENDPOINT_UNAVAILABLE,
                    TpLinkXdrDsLoginFailureReason.UNEXPECTED_RESPONSE,
                    -> null
                }
            },
            classifyFinalFailure = ::classifyFailure,
        ) {
            val client = TpLinkXdrDsAuthenticationClient(host, config, transport)
            client.login(username, password)
            client.fetchAuthenticatedRaw(config.authenticatedReadPayloadJson)
        }
        when (outcome) {
            is RetryOutcome.Success -> TpLinkXdrDsReadOutcome.Success(outcome.value)
            is RetryOutcome.Failure -> TpLinkXdrDsReadOutcome.Failure(
                outcome.reason,
                outcome.error.message ?: outcome.error.toString(),
            )
        }
    }

    /**
     * Implementação de [DriverFamily.authenticate] — mesmo handshake de [login], mas guarda o
     * [TpLinkXdrDsAuthenticationClient] resultante em [authenticatedClient] para [readCapability]
     * reaproveitar o `stok` entre chamadas.
     */
    override suspend fun authenticate(username: String, password: String): DriverFamilyAuthResult = withContext(Dispatchers.IO) {
        val outcome = executeWithRetry(
            maxAttempts = maxAttempts,
            backoffMillis = backoffMillis,
            loginExceptionType = TpLinkXdrDsLoginException::class.java,
            onLoginFailure = { e ->
                when (e.reason) {
                    TpLinkXdrDsLoginFailureReason.INVALID_CREDENTIALS -> TpLinkXdrDsFailureReason.INVALID_CREDENTIALS
                    TpLinkXdrDsLoginFailureReason.AUTH_ENDPOINT_UNAVAILABLE,
                    TpLinkXdrDsLoginFailureReason.UNEXPECTED_RESPONSE,
                    -> null
                }
            },
            classifyFinalFailure = ::classifyFailure,
        ) {
            val client = TpLinkXdrDsAuthenticationClient(host, config, transport)
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
                if (outcome.reason == TpLinkXdrDsFailureReason.INVALID_CREDENTIALS) {
                    DriverFamilyAuthResult.InvalidCredentials(message)
                } else {
                    DriverFamilyAuthResult.Failure(message)
                }
            }
        }
    }

    /**
     * Implementação de [DriverFamily.readCapability] — ver KDoc da classe para o porquê de nunca
     * devolver `Success` nesta rodada: executa a leitura autenticada real (usando a sessão de
     * [authenticatedClient]) para provar que o transporte/sessão funcionam ponta a ponta, mas nenhum
     * campo de capability tem nome confirmado ou inferível no payload de `/ds` — devolve
     * [CapabilityReadResult.Unavailable] sempre, com motivo distinguindo se a leitura em si
     * funcionou (`error_code` presente) de uma falha de sessão/rede.
     */
    override suspend fun readCapability(id: CapabilityId): CapabilityReadResult {
        if (id !in SUPPORTED_CAPABILITIES) {
            return CapabilityReadResult.Unavailable(
                reason = "TpLinkXdrDsDriverFamily não implementa parsing para $id nesta rodada.",
            )
        }
        val client = authenticatedClient
            ?: return CapabilityReadResult.Unavailable(
                reason = "Leitura de $id exige sessão autenticada — chame authenticate(username, password) " +
                    "(via CapabilityEngine) antes de ler capabilities.",
            )

        return withContext(Dispatchers.IO) {
            val rawBody = try {
                client.fetchAuthenticatedRaw(config.authenticatedReadPayloadJson)
            } catch (e: TpLinkXdrDsLoginException) {
                return@withContext CapabilityReadResult.Failure(reason = e.message ?: "falha inesperada ao ler $id", cause = e)
            } catch (e: java.io.IOException) {
                return@withContext CapabilityReadResult.Failure(reason = e.message ?: "falha de rede ao ler $id", cause = e)
            }
            val errorCode = TpLinkXdrDsResponseParser.parseErrorCode(rawBody)
            CapabilityReadResult.Unavailable(
                reason = if (errorCode == 0) {
                    "Leitura autenticada de /ds concluída com sucesso (error_code=0), mas nenhum campo de " +
                        "$id tem formato confirmado neste payload — sem captura real de hardware ainda " +
                        "(ver docs/drivers/tplink-mercusys-families-2026-07-07.md, 'Próximos passos de " +
                        "validação física'). Não inventar nome de campo."
                } else {
                    "Leitura autenticada de /ds não retornou error_code=0 (valor: $errorCode) — sem dado " +
                        "de $id para interpretar."
                },
            )
        }
    }

    private fun classifyFailure(error: Throwable): TpLinkXdrDsFailureReason = when (classifyNetworkFailure(error)) {
        NetworkFailureReason.DEVICE_UNREACHABLE -> TpLinkXdrDsFailureReason.DEVICE_UNREACHABLE
        NetworkFailureReason.TIMEOUT -> TpLinkXdrDsFailureReason.TIMEOUT
        NetworkFailureReason.UNEXPECTED_RESPONSE -> TpLinkXdrDsFailureReason.UNEXPECTED_RESPONSE
        NetworkFailureReason.COMMUNICATION_ERROR -> TpLinkXdrDsFailureReason.COMMUNICATION_ERROR
    }

    companion object {
        /**
         * Capabilities "em escopo de investigação" para esta família — nenhuma tem parser real (ver
         * KDoc da classe), mas declarar o conjunto permite distinguir, no motivo de
         * [CapabilityReadResult.Unavailable], "ainda não implementado para $id" (fora deste
         * conjunto) de "leitura executada, campo sem formato confirmado" (dentro dele).
         */
        val SUPPORTED_CAPABILITIES: Set<CapabilityId> = setOf(
            CapabilityId.READ_WIFI_STATUS,
            CapabilityId.READ_LAN_STATUS,
            CapabilityId.READ_WAN_STATUS,
            CapabilityId.READ_CONNECTED_CLIENTS,
        )
    }
}

class TpLinkXdrDsDriverFamilyFactory : DriverFamilyFactory {
    override val familyId: String = "tplink-xdr-ds-driver"

    override fun create(profile: CompatibilityProfile, host: String, transport: HttpTransport): DriverFamily {
        val config = TpLinkXdrDsDriverConfig.fromJsonElement(profile.driverConfig)
        return TpLinkXdrDsDriverFamily(host = host, config = config, transport = transport)
    }
}
