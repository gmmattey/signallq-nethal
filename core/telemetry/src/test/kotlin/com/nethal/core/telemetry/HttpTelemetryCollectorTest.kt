package com.nethal.core.telemetry

import com.nethal.core.model.CapabilityId
import com.nethal.core.model.CapabilityState
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Testes contra servidor HTTP local real (`com.sun.net.httpserver.HttpServer`, mesma técnica de
 * `DefaultHttpTransportTest` em `core:protocol`) — não contra o worker real (que nem existe ainda,
 * ver `linka-android#886`).
 */
class HttpTelemetryCollectorTest {

    private lateinit var server: HttpServer
    private val receivedRequests = mutableListOf<String>()
    private val requestCount = AtomicInteger(0)
    private val lastBody = AtomicReference<String?>(null)

    @Before
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requestCount.incrementAndGet()
            receivedRequests.add(exchange.requestURI.path)
            lastBody.set(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.close()
        }
        server.start()
    }

    @After
    fun stopServer() {
        server.stop(0)
    }

    private fun baseUrl(): String = "http://127.0.0.1:${server.address.port}"

    private fun fakeDeviceIdRepository(fixedId: String = "fixed-device-id") =
        object : TelemetryDeviceIdRepository {
            override suspend fun getOrCreateDeviceId(): String = fixedId
        }

    @Test
    fun `gate de consentimento bloqueia sendDiagnosticSession quando consentProvider retorna false`() = runBlocking {
        val collector = HttpTelemetryCollector(
            endpoint = TelemetryEndpointConfig(baseUrl = baseUrl(), ingestKey = "test-key"),
            deviceIdRepository = fakeDeviceIdRepository(),
            consentProvider = { false },
        )

        collector.sendDiagnosticSession(sampleSession())

        assertEquals("nenhuma requisição HTTP deveria sair sem consentimento", 0, requestCount.get())
    }

    @Test
    fun `gate de consentimento bloqueia sendCapabilityResult quando consentProvider retorna false`() = runBlocking {
        val collector = HttpTelemetryCollector(
            endpoint = TelemetryEndpointConfig(baseUrl = baseUrl(), ingestKey = "test-key"),
            deviceIdRepository = fakeDeviceIdRepository(),
            consentProvider = { false },
        )

        collector.sendCapabilityResult(sampleCapabilityResult())

        assertEquals("nenhuma requisição HTTP deveria sair sem consentimento", 0, requestCount.get())
    }

    @Test
    fun `default de consentProvider ausente e endpoint nao configurado - nunca envia`() = runBlocking {
        // simula app que ainda não plugou consentimento real: por composição, um provider default
        // seguro é sempre `{ false }` — nunca `{ true }` implícito.
        val collector = HttpTelemetryCollector(
            endpoint = TelemetryEndpointConfig(),
            deviceIdRepository = fakeDeviceIdRepository(),
            consentProvider = { false },
        )

        collector.sendDiagnosticSession(sampleSession())
        collector.sendCapabilityResult(sampleCapabilityResult())

        assertEquals(0, requestCount.get())
    }

    @Test
    fun `com consentimento e endpoint configurado, envia sessao para o path correto`() = runBlocking {
        val collector = HttpTelemetryCollector(
            endpoint = TelemetryEndpointConfig(baseUrl = baseUrl(), ingestKey = "test-key"),
            deviceIdRepository = fakeDeviceIdRepository(),
            consentProvider = { true },
        )

        collector.sendDiagnosticSession(sampleSession())

        assertEquals(1, requestCount.get())
        assertEquals("/ingest/nethal/session", receivedRequests.single())
    }

    @Test
    fun `com consentimento e endpoint configurado, envia capability para o path correto`() = runBlocking {
        val collector = HttpTelemetryCollector(
            endpoint = TelemetryEndpointConfig(baseUrl = baseUrl(), ingestKey = "test-key"),
            deviceIdRepository = fakeDeviceIdRepository(),
            consentProvider = { true },
        )

        collector.sendCapabilityResult(sampleCapabilityResult())

        assertEquals(1, requestCount.get())
        assertEquals("/ingest/nethal/capability", receivedRequests.single())
    }

    @Test
    fun `payload de sessao nunca contem campo proibido pela spec 8-9`() = runBlocking {
        val collector = HttpTelemetryCollector(
            endpoint = TelemetryEndpointConfig(baseUrl = baseUrl(), ingestKey = "test-key"),
            deviceIdRepository = fakeDeviceIdRepository(),
            consentProvider = { true },
        )

        collector.sendDiagnosticSession(sampleSession())

        val body = lastBody.get().orEmpty().lowercase()
        assertForbiddenFieldsAbsent(body)
    }

    @Test
    fun `payload de capability nunca contem campo proibido pela spec 8-9`() = runBlocking {
        val collector = HttpTelemetryCollector(
            endpoint = TelemetryEndpointConfig(baseUrl = baseUrl(), ingestKey = "test-key"),
            deviceIdRepository = fakeDeviceIdRepository(),
            consentProvider = { true },
        )

        collector.sendCapabilityResult(sampleCapabilityResult())

        val body = lastBody.get().orEmpty().lowercase()
        assertForbiddenFieldsAbsent(body)
    }

    @Test
    fun `device_id enviado no payload e o mesmo devolvido pelo repository injetado - nao gerado do zero por chamada`() =
        runBlocking {
            val collector = HttpTelemetryCollector(
                endpoint = TelemetryEndpointConfig(baseUrl = baseUrl(), ingestKey = "test-key"),
                deviceIdRepository = fakeDeviceIdRepository(fixedId = "stable-uuid-1234"),
                consentProvider = { true },
            )

            collector.sendDiagnosticSession(sampleSession())

            assertTrue(lastBody.get().orEmpty().contains("stable-uuid-1234"))
        }

    @Test
    fun `gate de consentimento bloqueia sendProductEvent quando consentProvider retorna false`() = runBlocking {
        val collector = HttpTelemetryCollector(
            endpoint = TelemetryEndpointConfig(baseUrl = baseUrl(), ingestKey = "test-key"),
            deviceIdRepository = fakeDeviceIdRepository(),
            consentProvider = { false },
        )

        collector.sendProductEvent(sampleScreenView())

        assertEquals("nenhuma requisição HTTP deveria sair sem consentimento", 0, requestCount.get())
    }

    @Test
    fun `com consentimento e endpoint configurado, envia evento de produto para o path correto`() = runBlocking {
        val collector = HttpTelemetryCollector(
            endpoint = TelemetryEndpointConfig(baseUrl = baseUrl(), ingestKey = "test-key"),
            deviceIdRepository = fakeDeviceIdRepository(),
            consentProvider = { true },
        )

        collector.sendProductEvent(sampleScreenView())

        assertEquals(1, requestCount.get())
        assertEquals("/ingest/nethal/analytics", receivedRequests.single())
    }

    @Test
    fun `payload de evento de produto nunca contem campo proibido pela spec 8-9`() = runBlocking {
        val collector = HttpTelemetryCollector(
            endpoint = TelemetryEndpointConfig(baseUrl = baseUrl(), ingestKey = "test-key"),
            deviceIdRepository = fakeDeviceIdRepository(),
            consentProvider = { true },
        )

        collector.sendProductEvent(sampleScreenView())

        val body = lastBody.get().orEmpty().lowercase()
        assertForbiddenFieldsAbsent(body)
    }

    @Test
    fun `session_start gera um sessionId de uso que e reaproveitado pelos screen_view seguintes`() = runBlocking {
        val bodies = mutableListOf<String>()
        val collector = HttpTelemetryCollector(
            endpoint = TelemetryEndpointConfig(baseUrl = baseUrl(), ingestKey = "test-key"),
            deviceIdRepository = fakeDeviceIdRepository(),
            consentProvider = { true },
        )

        collector.sendProductEvent(sampleSessionStart())
        bodies.add(lastBody.get().orEmpty())
        collector.sendProductEvent(sampleScreenView())
        bodies.add(lastBody.get().orEmpty())

        val sessionStartId = Regex("\"sessionId\":\"([^\"]+)\"").find(bodies[0])?.groupValues?.get(1)
        val screenViewId = Regex("\"sessionId\":\"([^\"]+)\"").find(bodies[1])?.groupValues?.get(1)

        assertTrue("session_start deveria carregar um sessionId gerado", !sessionStartId.isNullOrBlank())
        assertEquals(
            "screen_view disparado depois de session_start deveria carregar o mesmo sessionId",
            sessionStartId,
            screenViewId,
        )
    }

    @Test
    fun `session_end limpa o sessionId de uso - screen_view seguinte nao carrega mais o id antigo`() = runBlocking {
        val collector = HttpTelemetryCollector(
            endpoint = TelemetryEndpointConfig(baseUrl = baseUrl(), ingestKey = "test-key"),
            deviceIdRepository = fakeDeviceIdRepository(),
            consentProvider = { true },
        )

        collector.sendProductEvent(sampleSessionStart())
        val sessionStartId = Regex("\"sessionId\":\"([^\"]+)\"").find(lastBody.get().orEmpty())?.groupValues?.get(1)

        collector.sendProductEvent(sampleSessionEnd())
        collector.sendProductEvent(sampleScreenView())
        val screenViewAfterEnd = lastBody.get().orEmpty()

        assertTrue(
            "screen_view após session_end não deveria carregar o sessionId da sessão encerrada",
            sessionStartId == null || !screenViewAfterEnd.contains(sessionStartId),
        )
    }

    @Test
    fun `feature_crash carrega so o nome da classe da excecao em errorType`() = runBlocking {
        val collector = HttpTelemetryCollector(
            endpoint = TelemetryEndpointConfig(baseUrl = baseUrl(), ingestKey = "test-key"),
            deviceIdRepository = fakeDeviceIdRepository(),
            consentProvider = { true },
        )

        collector.sendProductEvent(sampleFeatureCrash())

        val body = lastBody.get().orEmpty()
        assertTrue(body.contains("\"errorType\":\"IllegalStateException\""))
        assertFalse("nunca deveria carregar mensagem de exceção", body.contains("mensagem sensível"))
    }

    private fun sampleScreenView() = ProductEvent(
        name = TelemetryProductEventName.SCREEN_VIEW,
        screenName = "home/status",
        appVersion = "1.0.0",
        environment = "production",
        versionCode = 1,
    )

    private fun sampleSessionStart() = ProductEvent(
        name = TelemetryProductEventName.SESSION_START,
        appVersion = "1.0.0",
        environment = "production",
        versionCode = 1,
    )

    private fun sampleSessionEnd() = ProductEvent(
        name = TelemetryProductEventName.SESSION_END,
        appVersion = "1.0.0",
        environment = "production",
        versionCode = 1,
    )

    private fun sampleFeatureCrash() = ProductEvent(
        name = TelemetryProductEventName.FEATURE_CRASH,
        errorType = "IllegalStateException",
        appVersion = "1.0.0",
        environment = "production",
        versionCode = 1,
    )

    private fun assertForbiddenFieldsAbsent(bodyLowercase: String) {
        val forbiddenSubstrings = listOf(
            "ssid",
            "password",
            "senha",
            "psk",
            "mac_address",
            "\"mac\"",
            "ip_address",
            "\"ip\"",
            "cookie",
            "token",
            "hostname",
            "credential",
        )
        forbiddenSubstrings.forEach { forbidden ->
            assertFalse(
                "payload não deveria conter '$forbidden' — body=$bodyLowercase",
                bodyLowercase.contains(forbidden),
            )
        }
    }

    private fun sampleSession() = DiagnosticSessionEvent(
        sessionId = "session-1",
        matchedProfileId = "tplink-archer-c6",
        vendor = "TP-Link",
        model = "Archer C6",
        confidence = 0.92,
        driverFamilyId = "tplink-stok-luci",
        manifestVersion = "2026.07.26",
        authResult = TelemetryAuthResult.SUCCESS,
        durationMs = 1_250,
        appVersion = "1.0.0",
        environment = "production",
        buildType = "release",
    )

    private fun sampleCapabilityResult() = CapabilityResultEvent(
        sessionId = "session-1",
        capabilityId = CapabilityId.READ_WIFI_STATUS,
        state = CapabilityState.AVAILABLE,
        outcome = TelemetryCapabilityOutcome.SUCCESS,
        reasonCode = null,
        durationMs = 340,
    )
}
