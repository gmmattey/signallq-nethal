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
