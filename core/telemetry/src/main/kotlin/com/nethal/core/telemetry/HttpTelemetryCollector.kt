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
import java.util.concurrent.atomic.AtomicReference

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

    /**
     * Id de "sessão de uso" (independente do [DiagnosticSessionEvent.sessionId]) — gerado a cada
     * [TelemetryProductEventName.SESSION_START], anexado a todo evento de produto subsequente
     * ([TelemetryProductEventName.SCREEN_VIEW]/[TelemetryProductEventName.FEATURE_CRASH]) até o
     * próximo [TelemetryProductEventName.SESSION_END], quando é limpo. Mesmo espírito do `session_id`
     * de `analytics_events` do SignallQ. `AtomicReference` porque `screen_view` (disparado pela
     * navegação Compose) e `session_start`/`session_end`/`feature_crash` (ciclo de vida do
     * `Application`) podem chamar este coletor de corrotinas diferentes.
     */
    private val analyticsSessionId = AtomicReference<String?>(null)

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

    override suspend fun sendProductEvent(event: ProductEvent) {
        if (!consentProvider()) return
        // session_start abre um novo id de sessão de uso antes de montar o payload — o próprio
        // evento de abertura já sai com o id novo. session_end lê o id vigente e só limpa depois de
        // montar o payload, para o evento de fechamento carregar o mesmo id que os screen_view da
        // sessão que está terminando.
        if (event.name == TelemetryProductEventName.SESSION_START) {
            analyticsSessionId.set(UUID.randomUUID().toString())
        }
        send(path = ANALYTICS_PATH) {
            val payload = ProductEventWirePayload(
                id = UUID.randomUUID().toString(),
                eventName = event.name.name.lowercase(),
                sessionId = analyticsSessionId.get(),
                screenName = event.screenName,
                errorType = event.errorType,
                createdAt = clock(),
                appVersion = event.appVersion,
                environment = event.environment,
                versionCode = event.versionCode,
                deviceId = deviceIdRepository.getOrCreateDeviceId(),
            )
            json.encodeToString(payload)
        }
        if (event.name == TelemetryProductEventName.SESSION_END) {
            analyticsSessionId.set(null)
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
        private const val ANALYTICS_PATH = "/ingest/nethal/analytics"
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

/** Formato de linha em `nethal_analytics_events` (issue #97, migration companion `linka-android#886`). */
@Serializable
private data class ProductEventWirePayload(
    val id: String,
    val eventName: String,
    val sessionId: String?,
    val screenName: String?,
    val errorType: String?,
    val createdAt: Long,
    val appVersion: String,
    val environment: String,
    val versionCode: Int,
    val deviceId: String,
)
