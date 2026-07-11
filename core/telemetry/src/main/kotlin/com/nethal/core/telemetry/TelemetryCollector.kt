package com.nethal.core.telemetry

import com.nethal.core.model.CapabilityId
import com.nethal.core.model.CapabilityState

/**
 * Resultado de [com.nethal.core.catalog.DriverFamilyAuthResult], sem senha — mesmo vocabulário já
 * usado pelo core, adaptado ao formato fechado exigido por telemetria (spec §8.9: "resultado da
 * autenticação, sem senha").
 */
enum class TelemetryAuthResult {
    SUCCESS,
    INVALID_CREDENTIALS,
    FAILURE,
    NOT_ATTEMPTED,
}

/** Resultado de [com.nethal.core.catalog.CapabilityReadResult], achatado para exportação. */
enum class TelemetryCapabilityOutcome {
    SUCCESS,
    UNAVAILABLE,
    FAILURE,
    SESSION_EXPIRED,
}

/**
 * Payload de "Sessão de diagnóstico" (issue #97, Lane A) — uma por fluxo completo de identificação.
 * Contém só o permitido pela spec §8.9 (fabricante, modelo, driver, protocolo/perfil resolvido,
 * resultado de auth sem senha, tempo de resposta, contexto de app). Nenhum campo de rede do próprio
 * usuário (SSID/MAC/IP/hostname) — nem existe aqui, por desenho: este tipo é a fronteira de
 * exportação, ele não sabe ler `CapabilityPayload` nem tem onde colocar um campo desses.
 */
data class DiagnosticSessionEvent(
    val sessionId: String,
    val matchedProfileId: String?,
    val vendor: String?,
    val model: String?,
    val confidence: Double?,
    val driverFamilyId: String?,
    val manifestVersion: String?,
    val authResult: TelemetryAuthResult,
    val durationMs: Long?,
    val appVersion: String,
    val environment: String,
    val buildType: String,
)

/**
 * Payload de "Resultado por capability" (issue #97, Lane A) — N por sessão, FK por [sessionId].
 * [reasonCode] é sempre um [TelemetryReasonCode] fechado (nunca o texto bruto do driver) — ver
 * [TelemetryReasonCode.classify]. Nunca carrega [com.nethal.core.model.CapabilityPayload]: o dado
 * lido do equipamento (Wifi/Lan/Wan/ConnectedClients/DeviceInfo/Signal) nunca sai do device por este
 * caminho, só o resultado/estado da leitura.
 */
data class CapabilityResultEvent(
    val sessionId: String,
    val capabilityId: CapabilityId,
    val state: CapabilityState,
    val outcome: TelemetryCapabilityOutcome,
    val reasonCode: TelemetryReasonCode?,
    val durationMs: Long?,
)

/**
 * Coletor de telemetria do NetHAL Lab para o SignallQ Console (`signallq-admin-worker`), issue #97.
 *
 * Fire-and-forget: nunca bloqueia o fluxo do usuário, nunca lança exceção ao chamador — qualquer
 * falha de rede/serialização é logada e engolida internamente pela implementação (mesmo padrão do
 * `AdminIngestRepository` do SignallQ, `linka-android`).
 *
 * Gate de consentimento (`ConsentScope.TELEMETRY_BETA`) é responsabilidade da implementação, checado
 * como primeira linha de cada método — ver [HttpTelemetryCollector].
 *
 * Cobre só a Lane A da issue #97 (sessão + capability, ortogonal à tela). Eventos de produto
 * (`screen_view`/`session_start`/etc., Lane B) dependem da decisão de #66 sobre a taxonomia de tela
 * do redesenho e ficam fora deste contrato por ora.
 */
interface TelemetryCollector {
    suspend fun sendDiagnosticSession(session: DiagnosticSessionEvent)
    suspend fun sendCapabilityResult(result: CapabilityResultEvent)
}
