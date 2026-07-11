package com.nethal.core.capability

import com.nethal.core.catalog.CapabilityActionResult
import com.nethal.core.catalog.CapabilityReadResult
import com.nethal.core.catalog.DriverFamily
import com.nethal.core.catalog.DriverFamilyAuthResult
import com.nethal.core.model.Capability
import com.nethal.core.model.CapabilityId
import com.nethal.core.model.CapabilityPayload
import com.nethal.core.model.CapabilityState
import com.nethal.core.model.LanStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DriverFamily] fake, controlável por teste, para validar só a política genérica de sessão do
 * [CapabilityEngine] (criação lazy, reaproveitamento, renovação em uma única tentativa) — sem
 * depender de nenhum protocolo/criptografia real de nenhuma Driver Family concreta.
 */
private class FakeSessionDriverFamily(
    private val authenticateBehavior: (attempt: Int, username: String, password: String) -> DriverFamilyAuthResult,
    private val readBehavior: (attempt: Int, id: CapabilityId) -> CapabilityReadResult,
    private val actionBehavior: (attempt: Int, id: CapabilityId) -> CapabilityActionResult = { _, id ->
        CapabilityActionResult.Unavailable("FakeSessionDriverFamily não implementa ações por padrão neste teste")
    },
) : DriverFamily {

    var authenticateCallCount = 0
        private set
    var readCallCount = 0
        private set
    var actionCallCount = 0
        private set
    val authenticateCalls = mutableListOf<Pair<String, String>>()

    override suspend fun authenticate(username: String, password: String): DriverFamilyAuthResult {
        authenticateCallCount++
        authenticateCalls += username to password
        return authenticateBehavior(authenticateCallCount, username, password)
    }

    override suspend fun readCapability(id: CapabilityId): CapabilityReadResult {
        readCallCount++
        return readBehavior(readCallCount, id)
    }

    override suspend fun executeAction(id: CapabilityId): CapabilityActionResult {
        actionCallCount++
        return actionBehavior(actionCallCount, id)
    }
}

private fun successResult(id: CapabilityId): CapabilityReadResult = CapabilityReadResult.Success(
    capability = Capability(id = id, state = CapabilityState.AVAILABLE, confidence = 1.0),
    payload = CapabilityPayload.Lan(LanStatus(macAddress = "AA:BB:CC:DD:EE:FF", ipv4Address = "192.168.0.1")),
)

class CapabilityEngineTest {

    @Test
    fun `no authentication happens until the first readCapability call`() = runTest {
        val driverFamily = FakeSessionDriverFamily(
            authenticateBehavior = { _, _, _ -> DriverFamilyAuthResult.Success },
            readBehavior = { _, id -> successResult(id) },
        )
        val engine = CapabilityEngine(driverFamily, username = "admin", password = "secret")

        assertEquals(0, driverFamily.authenticateCallCount)
        assertFalse(engine.isSessionActive)
    }

    @Test
    fun `session is created lazily on the first readCapability call`() = runTest {
        val driverFamily = FakeSessionDriverFamily(
            authenticateBehavior = { _, _, _ -> DriverFamilyAuthResult.Success },
            readBehavior = { _, id -> successResult(id) },
        )
        val engine = CapabilityEngine(driverFamily, username = "admin", password = "secret")

        val result = engine.readCapability(CapabilityId.READ_LAN_STATUS)

        assertEquals(1, driverFamily.authenticateCallCount)
        assertTrue(engine.isSessionActive)
        assertTrue(result is CapabilityReadResult.Success)
    }

    @Test
    fun `session is reused across subsequent readCapability calls - no repeated login`() = runTest {
        val driverFamily = FakeSessionDriverFamily(
            authenticateBehavior = { _, _, _ -> DriverFamilyAuthResult.Success },
            readBehavior = { _, id -> successResult(id) },
        )
        val engine = CapabilityEngine(driverFamily, username = "admin", password = "secret")

        engine.readCapability(CapabilityId.READ_LAN_STATUS)
        engine.readCapability(CapabilityId.READ_WAN_STATUS)
        engine.readCapability(CapabilityId.READ_WIFI_STATUS)

        assertEquals(1, driverFamily.authenticateCallCount)
        assertEquals(3, driverFamily.readCallCount)
    }

    @Test
    fun `session is renewed automatically once when a read reports SessionExpired, then the read is retried`() = runTest {
        val driverFamily = FakeSessionDriverFamily(
            authenticateBehavior = { _, _, _ -> DriverFamilyAuthResult.Success },
            readBehavior = { attempt, id ->
                if (attempt == 1) CapabilityReadResult.SessionExpired(reason = "stok expirado (fake)") else successResult(id)
            },
        )
        val engine = CapabilityEngine(driverFamily, username = "admin", password = "secret")

        val result = engine.readCapability(CapabilityId.READ_LAN_STATUS)

        assertEquals(2, driverFamily.authenticateCallCount) // login inicial + renovação
        assertEquals(2, driverFamily.readCallCount) // tentativa que expirou + retentativa pós-renovação
        assertTrue(result is CapabilityReadResult.Success)
        assertTrue(engine.isSessionActive)
    }

    @Test
    fun `renewal failure after SessionExpired closes the session and never retries indefinitely`() = runTest {
        val driverFamily = FakeSessionDriverFamily(
            authenticateBehavior = { attempt, _, _ ->
                if (attempt == 1) DriverFamilyAuthResult.Success else DriverFamilyAuthResult.Failure("equipamento fora do ar")
            },
            readBehavior = { _, _ -> CapabilityReadResult.SessionExpired(reason = "stok expirado (fake)") },
        )
        val engine = CapabilityEngine(driverFamily, username = "admin", password = "secret")

        val result = engine.readCapability(CapabilityId.READ_LAN_STATUS)

        assertEquals(2, driverFamily.authenticateCallCount) // login inicial + 1 tentativa de renovação, nunca mais
        assertEquals(1, driverFamily.readCallCount) // não tenta ler de novo depois de falhar a renovação
        assertTrue(result is CapabilityReadResult.Unavailable)
        assertFalse(engine.isSessionActive)
    }

    @Test
    fun `invalid credentials on the first authentication never reach readCapability on the DriverFamily`() = runTest {
        val driverFamily = FakeSessionDriverFamily(
            authenticateBehavior = { _, _, _ -> DriverFamilyAuthResult.InvalidCredentials("senha incorreta") },
            readBehavior = { _, id -> successResult(id) },
        )
        val engine = CapabilityEngine(driverFamily, username = "admin", password = "wrong")

        val result = engine.readCapability(CapabilityId.READ_LAN_STATUS)

        assertEquals(0, driverFamily.readCallCount)
        assertTrue(result is CapabilityReadResult.Unavailable)
        assertFalse(engine.isSessionActive)
    }

    @Test
    fun `closeSession discards the in-memory credential and a further read must reauthenticate`() = runTest {
        val driverFamily = FakeSessionDriverFamily(
            authenticateBehavior = { _, _, _ -> DriverFamilyAuthResult.Success },
            readBehavior = { _, id -> successResult(id) },
        )
        val engine = CapabilityEngine(driverFamily, username = "admin", password = "secret")
        engine.readCapability(CapabilityId.READ_LAN_STATUS)
        assertTrue(engine.isSessionActive)

        engine.closeSession()
        assertFalse(engine.isSessionActive)

        val result = engine.readCapability(CapabilityId.READ_LAN_STATUS)

        // sem credencial em memória depois de closeSession(), a leitura falha honestamente em vez de
        // reautenticar sozinha - nunca guarda a senha além do necessário.
        assertTrue(result is CapabilityReadResult.Unavailable)
        assertFalse(engine.isSessionActive)
    }

    @Test
    fun `testCredentials authenticates immediately without requiring a capability read`() = runTest {
        val driverFamily = FakeSessionDriverFamily(
            authenticateBehavior = { _, _, _ -> DriverFamilyAuthResult.Success },
            readBehavior = { _, id -> successResult(id) },
        )
        val engine = CapabilityEngine(driverFamily, username = "admin", password = "secret")

        val result = engine.testCredentials()

        assertTrue(result is CapabilitySessionResult.Active)
        assertEquals(1, driverFamily.authenticateCallCount)
        assertEquals(0, driverFamily.readCallCount)
        assertTrue(engine.isSessionActive)
    }

    @Test
    fun `the credential is never exposed by the engine or by any result reason - toString and messages stay clean`() = runTest {
        val secretPassword = "S3nhaSuperSecreta!!"
        val driverFamily = FakeSessionDriverFamily(
            authenticateBehavior = { _, _, _ -> DriverFamilyAuthResult.InvalidCredentials("credencial rejeitada pelo equipamento") },
            readBehavior = { _, id -> successResult(id) },
        )
        val engine = CapabilityEngine(driverFamily, username = "admin", password = secretPassword)

        val sessionResult = engine.testCredentials()
        val readResult = engine.readCapability(CapabilityId.READ_LAN_STATUS)

        assertFalse(engine.toString().contains(secretPassword))
        assertFalse((sessionResult as CapabilitySessionResult.InvalidCredentials).reason.contains(secretPassword))
        assertFalse((readResult as CapabilityReadResult.Unavailable).reason.contains(secretPassword))
        assertTrue(driverFamily.authenticateCalls.all { (_, password) -> password == secretPassword })
    }

    // --- executeAction (issue #103) — mesma política de sessão de readCapability, testada em separado
    // para garantir que nenhuma lógica de renovação foi duplicada/divergiu entre os dois métodos. ---

    private fun successAction(id: CapabilityId): CapabilityActionResult = CapabilityActionResult.Success(
        capability = Capability(id = id, state = CapabilityState.AVAILABLE, confidence = 1.0),
    )

    @Test
    fun `executeAction authenticates lazily on the first call, just like readCapability`() = runTest {
        val driverFamily = FakeSessionDriverFamily(
            authenticateBehavior = { _, _, _ -> DriverFamilyAuthResult.Success },
            readBehavior = { _, id -> successResult(id) },
            actionBehavior = { _, id -> successAction(id) },
        )
        val engine = CapabilityEngine(driverFamily, username = "admin", password = "secret")

        assertEquals(0, driverFamily.authenticateCallCount)

        val result = engine.executeAction(CapabilityId.REBOOT_DEVICE)

        assertEquals(1, driverFamily.authenticateCallCount)
        assertTrue(engine.isSessionActive)
        assertTrue(result is CapabilityActionResult.Success)
    }

    @Test
    fun `executeAction reuses an already active session - no repeated login`() = runTest {
        val driverFamily = FakeSessionDriverFamily(
            authenticateBehavior = { _, _, _ -> DriverFamilyAuthResult.Success },
            readBehavior = { _, id -> successResult(id) },
            actionBehavior = { _, id -> successAction(id) },
        )
        val engine = CapabilityEngine(driverFamily, username = "admin", password = "secret")

        engine.readCapability(CapabilityId.READ_LAN_STATUS) // abre a sessão
        val result = engine.executeAction(CapabilityId.REBOOT_DEVICE)

        assertEquals(1, driverFamily.authenticateCallCount)
        assertEquals(1, driverFamily.actionCallCount)
        assertTrue(result is CapabilityActionResult.Success)
    }

    @Test
    fun `executeAction renews the session automatically once on SessionExpired, then retries the action`() = runTest {
        val driverFamily = FakeSessionDriverFamily(
            authenticateBehavior = { _, _, _ -> DriverFamilyAuthResult.Success },
            readBehavior = { _, id -> successResult(id) },
            actionBehavior = { attempt, id ->
                if (attempt == 1) CapabilityActionResult.SessionExpired(reason = "stok expirado (fake)") else successAction(id)
            },
        )
        val engine = CapabilityEngine(driverFamily, username = "admin", password = "secret")

        val result = engine.executeAction(CapabilityId.REBOOT_DEVICE)

        assertEquals(2, driverFamily.authenticateCallCount) // login inicial + renovação
        assertEquals(2, driverFamily.actionCallCount) // tentativa que expirou + retentativa pós-renovação
        assertTrue(result is CapabilityActionResult.Success)
        assertTrue(engine.isSessionActive)
    }

    @Test
    fun `executeAction renewal failure after SessionExpired closes the session and never retries indefinitely`() = runTest {
        val driverFamily = FakeSessionDriverFamily(
            authenticateBehavior = { attempt, _, _ ->
                if (attempt == 1) DriverFamilyAuthResult.Success else DriverFamilyAuthResult.Failure("equipamento fora do ar")
            },
            readBehavior = { _, id -> successResult(id) },
            actionBehavior = { _, _ -> CapabilityActionResult.SessionExpired(reason = "stok expirado (fake)") },
        )
        val engine = CapabilityEngine(driverFamily, username = "admin", password = "secret")

        val result = engine.executeAction(CapabilityId.REBOOT_DEVICE)

        assertEquals(2, driverFamily.authenticateCallCount)
        assertEquals(1, driverFamily.actionCallCount)
        assertTrue(result is CapabilityActionResult.Unavailable)
        assertFalse(engine.isSessionActive)
    }

    @Test
    fun `executeAction never authenticates automatically for an unsupported action - the DriverFamily default answers honestly without hitting the transport`() = runTest {
        val driverFamily = FakeSessionDriverFamily(
            authenticateBehavior = { _, _, _ -> DriverFamilyAuthResult.Success },
            readBehavior = { _, id -> successResult(id) },
            // actionBehavior default (Unavailable) — mesma resposta honesta de um driver que não
            // implementa nenhuma ação, ex. Archer C20/Nokia para REBOOT_DEVICE.
        )
        val engine = CapabilityEngine(driverFamily, username = "admin", password = "secret")

        val result = engine.executeAction(CapabilityId.REBOOT_DEVICE)

        assertTrue(result is CapabilityActionResult.Unavailable)
        // ainda assim autentica (a decisao de "quem suporta o que" e da DriverFamily, nao do engine) -
        // mas nunca finge sucesso.
        assertEquals(1, driverFamily.authenticateCallCount)
        assertEquals(1, driverFamily.actionCallCount)
    }
}
