package com.nethal.core.catalog

import com.nethal.core.model.CapabilityId
import com.nethal.core.protocol.http.HttpTransport
import com.nethal.core.protocol.http.HttpTransportResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Factory/DriverFamily fake usadas só para testar o [DriverFamilyRegistry] — nenhuma das três
 * implementações reais (TP-Link, Nokia) existe ainda nesta rodada (passo 4 do plano de refatoração,
 * `hal-layering-model.md` §10).
 */
private class FakeDriverFamily(val profile: CompatibilityProfile, val host: String) : DriverFamily {
    override suspend fun readCapability(id: CapabilityId): CapabilityReadResult =
        CapabilityReadResult.Unavailable(reason = "fake driver family - sem implementação real")
}

private class FakeDriverFamilyFactory(override val familyId: String) : DriverFamilyFactory {
    override fun create(profile: CompatibilityProfile, host: String, transport: HttpTransport): DriverFamily =
        FakeDriverFamily(profile, host)
}

private class NoOpHttpTransport : HttpTransport {
    override fun get(url: String, extraHeaders: Map<String, String>): HttpTransportResponse =
        HttpTransportResponse(statusCode = 200, body = "", headers = emptyMap(), cookies = emptyMap())

    override fun post(
        url: String,
        body: String,
        cookies: Map<String, String>,
        extraHeaders: Map<String, String>,
    ): HttpTransportResponse =
        HttpTransportResponse(statusCode = 200, body = "", headers = emptyMap(), cookies = emptyMap())
}

private fun fakeProfile(driverFamilyId: String) = CompatibilityProfile(
    profileId = "fake_profile_v1",
    vendor = "FakeVendor",
    model = "FakeModel",
    deviceType = CatalogDeviceType.ROUTER,
    productLine = "FakeLine",
    platformId = "fake-platform",
    driverFamilyId = driverFamilyId,
    stage = DriverStage.DRAFT,
    stageReason = "profile de teste, não é dado real de catálogo",
    physicalTestAccess = false,
    managementDefaults = ManagementDefaults(
        candidateIps = listOf("192.168.1.1"),
        ipConfidence = 0.0,
        ipConfidenceNote = "teste",
        managementPort = 80,
        managementPortNote = "teste",
    ),
    credentialConvention = CredentialConvention(
        confidence = 0.0,
        confidenceNote = "teste",
        policyNote = "teste",
    ),
    confidenceScoreOverall = 0.0,
    confidenceScoreOverallNote = "teste",
)

class DriverFamilyRegistryTest {

    @Test
    fun `resolves DriverFamily for a registered driverFamilyId`() = runTest {
        val registry = DriverFamilyRegistry(listOf(FakeDriverFamilyFactory(familyId = "fake-driver")))
        val profile = fakeProfile(driverFamilyId = "fake-driver")

        val family = registry.resolve(profile, host = "192.168.1.1", transport = NoOpHttpTransport())

        assertTrue(family is FakeDriverFamily)
        assertEquals(profile, (family as FakeDriverFamily).profile)
        assertEquals("192.168.1.1", family.host)
    }

    @Test
    fun `throws a clear exception when driverFamilyId has no registered factory`() = runTest {
        val registry = DriverFamilyRegistry(listOf(FakeDriverFamilyFactory(familyId = "fake-driver")))
        val profile = fakeProfile(driverFamilyId = "nao-registrado")

        val exception = try {
            registry.resolve(profile, host = "192.168.1.1", transport = NoOpHttpTransport())
            null
        } catch (e: UnknownDriverFamilyException) {
            e
        }

        assertTrue(exception != null)
        assertEquals("nao-registrado", exception?.driverFamilyId)
    }

    @Test
    fun `map constructor resolves the same as the list constructor`() = runTest {
        val registry = DriverFamilyRegistry(mapOf("fake-driver" to FakeDriverFamilyFactory(familyId = "fake-driver")))
        val profile = fakeProfile(driverFamilyId = "fake-driver")

        val family = registry.resolve(profile, host = "10.0.0.1", transport = NoOpHttpTransport())

        assertTrue(family is FakeDriverFamily)
    }
}
