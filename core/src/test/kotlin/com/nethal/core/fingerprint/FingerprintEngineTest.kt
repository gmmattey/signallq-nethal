package com.nethal.core.fingerprint

import com.nethal.core.catalog.CatalogDeviceType
import com.nethal.core.catalog.CompatibilityManifest
import com.nethal.core.catalog.CompatibilityProfile
import com.nethal.core.catalog.CredentialConvention
import com.nethal.core.catalog.DefaultDriverRegistry
import com.nethal.core.catalog.DriverStage
import com.nethal.core.catalog.FingerprintEvidenceEntry
import com.nethal.core.catalog.FingerprintEvidenceType
import com.nethal.core.catalog.FingerprintConfidenceLevel
import com.nethal.core.catalog.ManagementDefaults
import com.nethal.core.catalog.loadEmbeddedCatalogResource
import com.nethal.core.model.DetectedProtocol
import com.nethal.core.model.NetworkTarget
import com.nethal.core.model.TargetRole
import com.nethal.core.model.TargetSource
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FingerprintEngineTest {

    private fun target(ip: String) = NetworkTarget(ip = ip, role = TargetRole.PRIMARY_GATEWAY, source = TargetSource.GATEWAY)

    @Test
    fun `identification never reads credentialConvention from the matched profile`() = runTest {
        // Regra inegociável do catálogo: credentialConvention é documental. O
        // FingerprintResult não expõe nenhum campo de credencial — a única forma deste teste
        // falhar seria se um campo desse tipo fosse adicionado futuramente sem essa mesma
        // proteção, então também travamos aqui a superfície pública do resultado.
        val probe = object : HttpFingerprintProbe {
            override suspend fun probe(ip: String, port: Int): HttpFingerprintEvidence {
                return HttpFingerprintEvidence(httpTitle = "Login", serverHeader = null, wwwAuthenticateHeader = null, statusCode = 200)
            }
        }
        val registry = DefaultDriverRegistry(embeddedManifestLoader = ::loadEmbeddedCatalogResource)
        val engine = DefaultFingerprintEngine(probe, registry)

        val result = engine.identify(target("172.16.5.5"))

        val declaredFields = FingerprintResult::class.java.declaredFields.map { it.name }
        assertTrue(declaredFields.none { it.contains("credential", ignoreCase = true) || it.contains("password", ignoreCase = true) })
        assertNull(result.vendor) // 172.16.5.5 não é candidateIp de nenhum profile do catálogo real
    }

    @Test
    fun `against the real embedded catalog with null evidence, confidence stays low and no vendor is forced`() = runTest {
        val probe = object : HttpFingerprintProbe {
            override suspend fun probe(ip: String, port: Int): HttpFingerprintEvidence? = null
        }
        val registry = DefaultDriverRegistry(embeddedManifestLoader = ::loadEmbeddedCatalogResource)
        val engine = DefaultFingerprintEngine(probe, registry)

        val result = engine.identify(target("10.0.0.99"))

        assertNull(result.vendor)
        assertNull(result.model)
        assertEquals(0.0, result.confidence, 0.0)
        assertEquals("2026.07.26", result.manifestVersion)
    }

    @Test
    fun `matching candidate ip alone yields endpoint match plus catalog presence score`() = runTest {
        val probe = object : HttpFingerprintProbe {
            override suspend fun probe(ip: String, port: Int): HttpFingerprintEvidence? = null
        }
        val registry = DefaultDriverRegistry(embeddedManifestLoader = ::loadEmbeddedCatalogResource)
        val engine = DefaultFingerprintEngine(probe, registry)

        // 192.168.1.1 é candidateIp tanto do TP-Link Archer C6 quanto do Archer C20 no manifesto
        // real (mesmo endpoint de gerência canônico da família Archer). Sem outra evidência, os
        // dois empatam em confidence — o engine desempata pegando o primeiro na ordem em que
        // DriverRegistry.profiles() devolve o catálogo (ordem do array `profiles` do JSON: C6
        // antes de C20). Se o catálogo for reordenado, este teste pode passar a bater no C20 e
        // precisa ser revisto — não é uma garantia de contrato do engine, só reflete a ordem atual.
        val result = engine.identify(target("192.168.1.1"))

        assertEquals("TP-Link", result.vendor)
        assertEquals("Archer C6", result.model)
        assertEquals(0.30, result.confidence, 0.0001) // 0.20 endpoint canônico + 0.10 presença no catálogo
    }

    @Test
    fun `header title match against a synthetic profile yields full weighted score`() = runTest {
        val syntheticProfile = CompatibilityProfile(
            profileId = "synthetic_v1",
            vendor = "Synthetic",
            model = "Router X",
            deviceType = CatalogDeviceType.ROUTER,
            productLine = "Synthetic family",
            platformId = "synthetic-platform",
            driverFamilyId = "synthetic-driver",
            firmwareKnown = listOf("1.0.0"),
            stage = DriverStage.DRAFT,
            stageReason = "teste sintético",
            physicalTestAccess = false,
            managementDefaults = ManagementDefaults(
                candidateIps = listOf("192.168.50.1"),
                ipConfidence = 0.9,
                ipConfidenceNote = "teste",
                managementPort = 80,
                managementPortNote = "teste",
            ),
            credentialConvention = CredentialConvention(
                defaultUser = null,
                defaultPasswordPattern = null,
                confidence = 0.0,
                confidenceNote = "teste",
                policyNote = "nunca usar para login automático",
            ),
            fingerprintEvidence = listOf(
                FingerprintEvidenceEntry(
                    type = FingerprintEvidenceType.HTML_TITLE,
                    value = JsonPrimitive("Synthetic Router Login"),
                    confidence = 0.9,
                    confidenceLevel = FingerprintConfidenceLevel.HIGH,
                    source = "teste sintético",
                ),
            ),
            confidenceScoreOverall = 0.9,
            confidenceScoreOverallNote = "teste sintético",
        )
        val manifest = CompatibilityManifest(
            schema = "test",
            manifestVersion = "test.manifest",
            generatedAt = "2026-01-01T00:00:00Z",
            generatedBy = "test",
            profiles = listOf(syntheticProfile),
        )
        val registry = DefaultDriverRegistry(embeddedManifestLoader = {
            kotlinx.serialization.json.Json.encodeToString(CompatibilityManifest.serializer(), manifest)
        })
        val probe = object : HttpFingerprintProbe {
            override suspend fun probe(ip: String, port: Int): HttpFingerprintEvidence =
                HttpFingerprintEvidence(httpTitle = "Synthetic Router Login Page", serverHeader = null, wwwAuthenticateHeader = null, statusCode = 200)
        }
        val engine = DefaultFingerprintEngine(probe, registry)

        val result = engine.identify(target("192.168.50.1"))

        assertEquals("Synthetic", result.vendor)
        // 0.25 header/banner + 0.20 endpoint canônico + 0.10 presença no catálogo = 0.55
        assertEquals(0.55, result.confidence, 0.0001)
        assertTrue(result.detectedProtocols.contains(DetectedProtocol.HTTP_LOCAL_WEBUI))
    }

    @Test
    fun `null fingerprint evidence value in catalog never contributes to score`() = runTest {
        val nullEvidenceProfile = CompatibilityProfile(
            profileId = "null_evidence_v1",
            vendor = "NullVendor",
            model = "Model Z",
            deviceType = CatalogDeviceType.ROUTER,
            productLine = "teste",
            platformId = "test-platform",
            driverFamilyId = "test-driver",
            stage = DriverStage.DRAFT,
            stageReason = "teste sintético",
            physicalTestAccess = false,
            managementDefaults = ManagementDefaults(
                candidateIps = emptyList(),
                ipConfidence = 0.0,
                ipConfidenceNote = "teste",
                managementPort = 80,
                managementPortNote = "teste",
            ),
            credentialConvention = CredentialConvention(
                defaultUser = null,
                defaultPasswordPattern = null,
                confidence = 0.0,
                confidenceNote = "teste",
                policyNote = "nunca usar para login automático",
            ),
            fingerprintEvidence = listOf(
                FingerprintEvidenceEntry(
                    type = FingerprintEvidenceType.HTML_TITLE,
                    value = JsonNull,
                    confidence = 0.0,
                    confidenceLevel = FingerprintConfidenceLevel.NONE_VERIFIED,
                    source = "não capturado",
                ),
            ),
            confidenceScoreOverall = 0.0,
            confidenceScoreOverallNote = "teste sintético",
        )
        val manifest = CompatibilityManifest(
            schema = "test",
            manifestVersion = "test.manifest",
            generatedAt = "2026-01-01T00:00:00Z",
            generatedBy = "test",
            profiles = listOf(nullEvidenceProfile),
        )
        val registry = DefaultDriverRegistry(embeddedManifestLoader = {
            kotlinx.serialization.json.Json.encodeToString(CompatibilityManifest.serializer(), manifest)
        })
        val probe = object : HttpFingerprintProbe {
            override suspend fun probe(ip: String, port: Int): HttpFingerprintEvidence =
                HttpFingerprintEvidence(httpTitle = "", serverHeader = null, wwwAuthenticateHeader = null, statusCode = 200)
        }
        val engine = DefaultFingerprintEngine(probe, registry)

        val result = engine.identify(target("10.10.10.10"))

        assertNull(result.vendor)
        assertEquals(0.0, result.confidence, 0.0)
    }

    @Test
    fun `tie between two real C6 profiles prefers the more mature stage over catalog order`() = runTest {
        // Regressão da issue #45: tplink_archer_c6_v1 (DRAFT, driver que não lê nada) e
        // tplink_archer_c6_stok_v1 (READ_ONLY_ALPHA, driver real) compartilham vendor/model e os
        // mesmos candidateIps no catálogo real — sem evidência de header, o score empata em 0.30
        // (endpoint canônico + presença no catálogo) para os dois. O engine precisa escolher o
        // profile mais maduro, não o primeiro na ordem do array (que é o DRAFT).
        val realManifestJson = loadEmbeddedCatalogResource()
        val realManifest = kotlinx.serialization.json.Json.decodeFromString(CompatibilityManifest.serializer(), realManifestJson)
        val draftProfile = realManifest.profiles.single { it.profileId == "tplink_archer_c6_v1" }
        val stokProfile = realManifest.profiles.single { it.profileId == "tplink_archer_c6_stok_v1" }
        assertEquals(DriverStage.DRAFT, draftProfile.stage)
        assertEquals(DriverStage.READ_ONLY_ALPHA, stokProfile.stage)

        val tieManifest = realManifest.copy(profiles = listOf(draftProfile, stokProfile))
        val registry = DefaultDriverRegistry(embeddedManifestLoader = {
            kotlinx.serialization.json.Json.encodeToString(CompatibilityManifest.serializer(), tieManifest)
        })
        val probe = object : HttpFingerprintProbe {
            override suspend fun probe(ip: String, port: Int): HttpFingerprintEvidence? = null
        }
        val engine = DefaultFingerprintEngine(probe, registry)

        val result = engine.identify(target("192.168.0.1"))

        assertEquals(0.30, result.confidence, 0.0001)
        assertEquals("tplink_archer_c6_stok_v1", result.matchedProfileId)
    }
}
