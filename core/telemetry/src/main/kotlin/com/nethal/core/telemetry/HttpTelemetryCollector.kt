package com.nethal.core.telemetry

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

private const val CONNECT_TIMEOUT_MILLIS = 5_000
private const val READ_TIMEOUT_MILLIS = 5_000

/**
 * Implementação real de [TelemetryCollector] — HTTP via `HttpURLConnection` (JVM puro, mesma
 * ferramenta já usada por `DefaultHttpTransport` em `core:protocol`, sem dependência nova).
 *
 * Deliberadamente **não** reusa [com.nethal.core.protocol.http.DefaultHttpTransport]/
 * `HttpTransportIpGuard`: aquele guard restringe destino a LAN/loopback (RFC 1918) por desenho —
 * protege contra SSRF ao sondar equipamento local não confiável. O destino aqui é o oposto: um
 * endpoint público (`signallq-admin-worker`), então a restrição de LAN seria incorreta, não redundante.
 *
 * Gate de consentimento (`ConsentScope.TELEMETRY_BETA`, checado via [consentProvider] injetado pelo
 * chamador) é a primeira linha de cada método — default seguro é o próprio chamador nunca injetar
 * `{ true }` sem checagem real. Fire-and-forget: qualquer falha (rede, serialização, HTTP não-2xx) é
 * capturada e só repassada a [onDeliveryFailure], nunca lançada.
 */
class HttpTelemetryCollector(
    private val endpoint: TelemetryEndpointConfig,
    private val deviceIdRepository: TelemetryDeviceIdRepository,
    private val consentProvider: () -> Boolean,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val onDeliveryFailure: (path: String, message: String) -> Unit = { _, _ -> },
) : TelemetryCollector {

    private val json = Json { encodeDefaults = true }

    override suspend fun sendDiagnosticSession(session: DiagnosticSessionEvent) {
        if (!consentProvider()) return
        send(path = SESSION_PATH) {
            val payload = SessionWirePayload(
                id = UUID.randomUUID().toString(),
                createdAt = clock(),
                matchedProfileId = session.matchedProfileId,
                vendor = session.vendor,
                model = session.model,
                confidence = session.confidence,
                driverFamilyId = session.driverFamilyId,
                manifestVersion = session.manifestVersion,
                authResult = session.authResult.name,
                durationMs = session.durationMs,
                appVersion = session.appVersion,
                environment = session.environment,
                buildType = session.buildType,
                deviceId = deviceIdRepository.getOrCreateDeviceId(),
            )
            json.encodeToString(payload)
        }
    }

    override suspend fun sendCapabilityResult(result: CapabilityResultEvent) {
        if (!consentProvider()) return
        send(path = CAPABILITY_PATH) {
            val payload = CapabilityWirePayload(
                id = UUID.randomUUID().toString(),
                sessionId = result.sessionId,
                capabilityId = result.capabilityId.name,
                state = result.state.name,
                outcome = result.outcome.name,
                reasonCode = result.reasonCode?.name,
                durationMs = result.durationMs,
                createdAt = clock(),
                deviceId = deviceIdRepository.getOrCreateDeviceId(),
            )
            json.encodeToString(payload)
        }
    }

    private suspend fun send(path: String, buildBody: suspend () -> String) {
        if (!endpoint.isConfigured) {
            onDeliveryFailure(path, "endpoint não configurado (aguardando linka-android#886)")
            return
        }
        runCatching {
            val body = buildBody()
            withContext(Dispatchers.IO) {
                postJson(path, body)
            }
        }.onFailure { t ->
            onDeliveryFailure(path, t.message ?: t::class.simpleName.orEmpty())
        }
    }

    private fun postJson(path: String, body: String) {
        val url = URL(endpoint.baseUrl.trimEnd('/') + path)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer ${endpoint.ingestKey}")
        }
        try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IOException("HTTP $code em $path")
            }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val SESSION_PATH = "/ingest/nethal/session"
        private const val CAPABILITY_PATH = "/ingest/nethal/capability"
    }
}

@Serializable
private data class SessionWirePayload(
    val id: String,
    val createdAt: Long,
    val matchedProfileId: String?,
    val vendor: String?,
    val model: String?,
    val confidence: Double?,
    val driverFamilyId: String?,
    val manifestVersion: String?,
    val authResult: String,
    val durationMs: Long?,
    val appVersion: String,
    val environment: String,
    val buildType: String,
    val deviceId: String,
)

@Serializable
private data class CapabilityWirePayload(
    val id: String,
    val sessionId: String,
    val capabilityId: String,
    val state: String,
    val outcome: String,
    val reasonCode: String?,
    val durationMs: Long?,
    val createdAt: Long,
    val deviceId: String,
)
