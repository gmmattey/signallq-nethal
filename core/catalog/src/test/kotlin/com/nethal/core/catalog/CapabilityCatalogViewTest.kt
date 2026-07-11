package com.nethal.core.catalog

import com.nethal.core.model.CapabilityId
import com.nethal.core.model.CapabilityState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun fakeProfile(capabilities: List<CatalogCapabilityEntry>, confidenceScoreOverall: Double = 0.5) =
    CompatibilityProfile(
        profileId = "fake_profile_v1",
        vendor = "FakeVendor",
        model = "FakeModel",
        deviceType = CatalogDeviceType.ROUTER,
        productLine = "FakeLine",
        platformId = "fake-platform",
        driverFamilyId = "fake-driver",
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
        capabilities = capabilities,
        confidenceScoreOverall = confidenceScoreOverall,
        confidenceScoreOverallNote = "teste",
    )

class CapabilityCatalogViewTest {

    // --- Catálogo real ativo (catalog-2026.07.26.json) ---

    @Test
    fun `projects declared capabilities for every real profile in the active manifest`() {
        val registry = DefaultDriverRegistry(embeddedManifestLoader = ::loadEmbeddedCatalogResource)

        val nokia = registry.findProfile("nokia", "g-1425g-b")!!
        val declared = CapabilityCatalogView.declaredCapabilities(nokia)

        assertEquals(5, declared.size)
        assertTrue(declared.all { it.state == CapabilityState.AVAILABLE })
        assertTrue(declared.all { it.confidence == 0.9 })
        // AVAILABLE com reason preenchido no catálogo (reconfirmação) é preservado, não descartado.
        assertTrue(declared.all { it.reason != null })
        assertTrue(declared.map { it.id }.containsAll(
            listOf(
                CapabilityId.READ_DEVICE_INFO,
                CapabilityId.READ_WAN_STATUS,
                CapabilityId.READ_SIGNAL,
                CapabilityId.READ_UPTIME,
                CapabilityId.READ_FIRMWARE,
            ),
        ))
    }

    @Test
    fun `projects UNKNOWN declared capabilities with reason for a DRAFT profile`() {
        val registry = DefaultDriverRegistry(embeddedManifestLoader = ::loadEmbeddedCatalogResource)

        val c6 = registry.profiles().first { it.profileId == "tplink_archer_c6_v1" }
        val declared = CapabilityCatalogView.declaredCapabilities(c6)

        assertEquals(6, declared.size)
        assertTrue(declared.all { it.state == CapabilityState.UNKNOWN })
        assertTrue(declared.all { it.reason != null })
    }

    @Test
    fun `projects mixed EXPERIMENTAL and UNKNOWN declared capabilities for tplink_archer_c6_stok_v1`() {
        val registry = DefaultDriverRegistry(embeddedManifestLoader = ::loadEmbeddedCatalogResource)

        val c6Stok = registry.profiles().first { it.profileId == "tplink_archer_c6_stok_v1" }
        val declared = CapabilityCatalogView.declaredCapabilities(c6Stok)

        assertEquals(7, declared.size)
        assertEquals(
            CapabilityState.EXPERIMENTAL,
            declared.first { it.id == CapabilityId.READ_WAN_STATUS }.state,
        )
        assertEquals(
            CapabilityState.UNKNOWN,
            declared.first { it.id == CapabilityId.READ_DEVICE_INFO }.state,
        )
        assertTrue(declared.all { it.reason != null })
    }

    @Test
    fun `every non-AVAILABLE declared capability in the active manifest has a reason`() {
        val registry = DefaultDriverRegistry(embeddedManifestLoader = ::loadEmbeddedCatalogResource)

        registry.profiles().forEach { profile ->
            val declared = CapabilityCatalogView.declaredCapabilities(profile)
            declared.filter { it.state != CapabilityState.AVAILABLE }.forEach { capability ->
                assertTrue(
                    "profile=${profile.profileId} capability=${capability.id} deveria ter reason",
                    capability.reason != null,
                )
            }
        }
    }

    // --- Cenários sintéticos de dado malformado ---

    @Test
    fun `drops entries whose id has no match in CapabilityId`() {
        val profile = fakeProfile(
            capabilities = listOf(
                CatalogCapabilityEntry(id = "READ_DEVICE_INFO", state = "AVAILABLE"),
                CatalogCapabilityEntry(id = "READ_ALIEN_STATUS", state = "AVAILABLE"),
            ),
        )

        val declared = CapabilityCatalogView.declaredCapabilities(profile)

        assertEquals(1, declared.size)
        assertEquals(CapabilityId.READ_DEVICE_INFO, declared.single().id)
    }

    @Test
    fun `maps unrecognized state string to UNKNOWN and synthesizes a reason when catalog reason is missing`() {
        val profile = fakeProfile(
            capabilities = listOf(
                CatalogCapabilityEntry(id = "READ_FIRMWARE", state = "PENDING_REVIEW", reason = null),
            ),
        )

        val declared = CapabilityCatalogView.declaredCapabilities(profile)

        assertEquals(1, declared.size)
        val capability = declared.single()
        assertEquals(CapabilityState.UNKNOWN, capability.state)
        assertTrue(capability.reason!!.contains("PENDING_REVIEW"))
    }

    @Test
    fun `preserves the original reason when state is unrecognized but catalog already explains it`() {
        val profile = fakeProfile(
            capabilities = listOf(
                CatalogCapabilityEntry(
                    id = "READ_FIRMWARE",
                    state = "PENDING_REVIEW",
                    reason = "motivo real do catálogo para este estado ainda não modelado",
                ),
            ),
        )

        val declared = CapabilityCatalogView.declaredCapabilities(profile)

        assertEquals(
            "motivo real do catálogo para este estado ainda não modelado",
            declared.single().reason,
        )
    }

    @Test
    fun `synthesizes a reason when a non-AVAILABLE known state is missing the required reason`() {
        val profile = fakeProfile(
            capabilities = listOf(
                CatalogCapabilityEntry(id = "READ_WIFI_STATUS", state = "EXPERIMENTAL", reason = null),
            ),
        )

        val declared = CapabilityCatalogView.declaredCapabilities(profile)

        val capability = declared.single()
        assertEquals(CapabilityState.EXPERIMENTAL, capability.state)
        assertTrue(capability.reason!!.contains("READ_WIFI_STATUS"))
        assertTrue(capability.reason!!.contains("EXPERIMENTAL"))
    }

    @Test
    fun `AVAILABLE state without reason is not treated as malformed`() {
        val profile = fakeProfile(
            capabilities = listOf(
                CatalogCapabilityEntry(id = "READ_DEVICE_INFO", state = "AVAILABLE", reason = null),
            ),
        )

        val declared = CapabilityCatalogView.declaredCapabilities(profile)

        assertEquals(CapabilityState.AVAILABLE, declared.single().state)
        assertNull(declared.single().reason)
    }

    @Test
    fun `reuses profile confidenceScoreOverall as the declared confidence for every entry`() {
        val profile = fakeProfile(
            capabilities = listOf(
                CatalogCapabilityEntry(id = "READ_DEVICE_INFO", state = "AVAILABLE"),
                CatalogCapabilityEntry(id = "READ_FIRMWARE", state = "UNKNOWN", reason = "sem evidência ainda"),
            ),
            confidenceScoreOverall = 0.42,
        )

        val declared = CapabilityCatalogView.declaredCapabilities(profile)

        assertTrue(declared.all { it.confidence == 0.42 })
    }

    @Test
    fun `empty capabilities list projects to an empty list`() {
        val profile = fakeProfile(capabilities = emptyList())

        assertTrue(CapabilityCatalogView.declaredCapabilities(profile).isEmpty())
    }
}
