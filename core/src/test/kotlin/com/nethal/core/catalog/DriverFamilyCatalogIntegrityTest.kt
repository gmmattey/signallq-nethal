package com.nethal.core.catalog

import com.nethal.core.driver.family.defaultDriverFamilyRegistry
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guard permanente contra regressão entre o catálogo real (`DriverRegistry`) e o composition
 * root real de Driver Family (`defaultDriverFamilyRegistry()`) — issue #42.
 *
 * Carrega os dois lados de verdade (não fakes): o manifesto embarcado ativo e o mapa fixo
 * `driverFamilyId -> DriverFamilyFactory` montado em `DriverFamilies.kt`. Isso é
 * deliberadamente um teste de integração do estado real do produto, não de uma unidade isolada
 * — o próprio objetivo é detectar descompasso entre os dois lados quando um evolui sem o outro.
 *
 * Invariante testado (plano-mãe, Workstream 0.3):
 * 1. Todo profile em estágio >= READ_ONLY_ALPHA precisa ter uma DriverFamilyFactory registrada
 *    (senão o Capability Engine promete leitura real que não existe — `UnknownDriverFamilyException`
 *    em runtime). Profiles DRAFT/DISCOVERY_ONLY são isentos: ainda não prometem dado real.
 * 2. Toda DriverFamilyFactory registrada precisa ser referenciada por >= 1 profile do catálogo —
 *    família órfã é código morto ou catálogo desatualizado.
 */
class DriverFamilyCatalogIntegrityTest {

    // Estágios maduros o suficiente para prometer leitura real (spec `/ciclo-vida-driver`).
    private val matureStages = setOf(
        DriverStage.READ_ONLY_ALPHA,
        DriverStage.READ_ONLY_BETA,
        DriverStage.WRITE_BETA,
        DriverStage.STABLE,
    )

    @Test
    fun `every mature profile has a registered DriverFamilyFactory`() {
        val registry = DefaultDriverRegistry(embeddedManifestLoader = ::loadEmbeddedCatalogResource)
        val familyRegistry = defaultDriverFamilyRegistry()
        val registeredFamilyIds = familyRegistry.registeredFamilyIds()

        val matureProfiles = registry.profiles().filter { it.stage in matureStages }

        val profilesWithoutFactory = matureProfiles.filter { it.driverFamilyId !in registeredFamilyIds }

        assertTrue(
            "Profile(s) em estágio >= READ_ONLY_ALPHA sem DriverFamilyFactory registrada em " +
                "defaultDriverFamilyRegistry() — Capability Engine vai falhar com " +
                "UnknownDriverFamilyException em runtime para: " +
                profilesWithoutFactory.joinToString(", ") {
                    "profileId=\"${it.profileId}\" (stage=${it.stage}, driverFamilyId=\"${it.driverFamilyId}\")"
                },
            profilesWithoutFactory.isEmpty(),
        )
    }

    @Test
    fun `every registered DriverFamilyFactory is referenced by at least one catalog profile`() {
        val registry = DefaultDriverRegistry(embeddedManifestLoader = ::loadEmbeddedCatalogResource)
        val familyRegistry = defaultDriverFamilyRegistry()
        val registeredFamilyIds = familyRegistry.registeredFamilyIds()

        val referencedFamilyIds = registry.profiles().map { it.driverFamilyId }.toSet()

        val orphanFamilyIds = registeredFamilyIds - referencedFamilyIds

        assertTrue(
            "DriverFamilyFactory(s) registrada(s) em defaultDriverFamilyRegistry() sem nenhum " +
                "profile do catálogo referenciando — família órfã (código morto ou catálogo " +
                "desatualizado) para driverFamilyId(s): " + orphanFamilyIds.joinToString(", "),
            orphanFamilyIds.isEmpty(),
        )
    }
}
