package com.nethal.feature.onboarding.catalog

import com.nethal.core.catalog.DefaultDriverRegistry
import com.nethal.core.catalog.DriverStage
import com.nethal.core.catalog.loadEmbeddedCatalogResource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testa `buildOnboardingCompatibilityCatalog` contra o catálogo real embarcado (não mockado) —
 * critério de aceite da issue #73. Se o manifesto ativo mudar (`catalog/catalog-2026.07.26.json`),
 * este teste é o guard de que a dedupe/agrupamento continua correto.
 */
class OnboardingCompatibilityCatalogTest {

    private val registry = DefaultDriverRegistry(embeddedManifestLoader = ::loadEmbeddedCatalogResource)

    @Test
    fun `agrupa Archer C6 uma unica vez usando o melhor estagio entre os dois profiles`() {
        val catalog = registry.buildOnboardingCompatibilityCatalog()

        val archerC6Matches = (catalog.readingData + catalog.inResearch)
            .filter { it.vendor == "TP-Link" && it.model == "Archer C6" }

        assertEquals(1, archerC6Matches.size)
        assertEquals(DriverStage.READ_ONLY_ALPHA, archerC6Matches.single().stage)
        assertTrue(catalog.readingData.any { it.vendor == "TP-Link" && it.model == "Archer C6" })
        assertTrue(catalog.inResearch.none { it.vendor == "TP-Link" && it.model == "Archer C6" })
    }

    @Test
    fun `grupo leitura de dados contem os tres drivers em producao`() {
        val catalog = registry.buildOnboardingCompatibilityCatalog()

        val readingKeys = catalog.readingData.map { it.vendor to it.model }.toSet()

        assertEquals(
            setOf(
                "Nokia" to "G-1425G-B",
                "TP-Link" to "Archer C6",
                "TP-Link" to "Archer C20",
            ),
            readingKeys,
        )
        assertTrue(catalog.readingData.all { it.stage == DriverStage.READ_ONLY_ALPHA })
    }

    @Test
    fun `grupo em pesquisa contem os modelos DRAFT sem unidade fisica confirmada`() {
        val catalog = registry.buildOnboardingCompatibilityCatalog()

        val researchKeys = catalog.inResearch.map { it.vendor to it.model }.toSet()

        assertEquals(
            setOf(
                "TP-Link" to "Archer C50",
                "TP-Link" to "TL-XDR3010",
            ),
            researchKeys,
        )
        assertTrue(catalog.inResearch.all { it.stage == DriverStage.DRAFT })
    }

    @Test
    fun `tipo de equipamento e traduzido para PT-BR`() {
        val catalog = registry.buildOnboardingCompatibilityCatalog()

        val nokia = catalog.readingData.single { it.vendor == "Nokia" }
        val archerC6 = catalog.readingData.single { it.model == "Archer C6" }

        assertEquals("ONT", nokia.typeLabel)
        assertEquals("Roteador", archerC6.typeLabel)
    }

    @Test
    fun `rotulo de estagio nunca usa vocabulario Homologado ou Suportado Beta`() {
        assertEquals("Leitura", DriverStage.READ_ONLY_ALPHA.toOnboardingStageLabel())
        assertEquals("Leitura", DriverStage.READ_ONLY_BETA.toOnboardingStageLabel())
        assertEquals("Em pesquisa", DriverStage.DRAFT.toOnboardingStageLabel())
        assertEquals("Em pesquisa", DriverStage.DISCOVERY_ONLY.toOnboardingStageLabel())
        assertEquals("Leitura e escrita", DriverStage.STABLE.toOnboardingStageLabel())

        val forbiddenVocabulary = setOf("Homologado", "Homologados", "Suportado (Beta)", "Suportados (Beta)")
        DriverStage.entries.forEach { stage ->
            assertTrue(stage.toOnboardingStageLabel() !in forbiddenVocabulary)
        }
    }

    @Test
    fun `nenhum profile bloqueado ou depreciado aparece na lista`() {
        val catalog = registry.buildOnboardingCompatibilityCatalog()

        val allDevices = catalog.readingData + catalog.inResearch
        assertTrue(allDevices.none { it.stage == DriverStage.DEPRECATED || it.stage == DriverStage.BLOCKED })
    }
}
