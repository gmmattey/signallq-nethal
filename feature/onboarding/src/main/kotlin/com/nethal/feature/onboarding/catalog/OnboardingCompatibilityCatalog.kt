package com.nethal.feature.onboarding.catalog

import com.nethal.core.catalog.CatalogDeviceType
import com.nethal.core.catalog.CompatibilityProfile
import com.nethal.core.catalog.DriverRegistry
import com.nethal.core.catalog.DriverStage

/**
 * Um modelo comercial exibido na tela `1f` (issue #73) — já deduplicado por `(vendor, model)`.
 * O Fingerprint Engine decide em runtime qual `driverFamilyId`/profile usar quando há mais de um
 * para o mesmo modelo; o usuário nunca escolhe entre profiles concorrentes nesta tela (ver
 * `docs/design/specs/2026-07-11-onboarding-e-pareamento-manual.md` §0, decisão de dedupe do
 * Archer C6).
 */
data class OnboardingCompatibleDevice(
    val vendor: String,
    val model: String,
    val typeLabel: String,
    val stage: DriverStage,
)

data class OnboardingCompatibilityCatalog(
    val readingData: List<OnboardingCompatibleDevice>,
    val inResearch: List<OnboardingCompatibleDevice>,
)

/** Estágios que a tela `1f` mostra no grupo "LEITURA DE DADOS". */
private val READING_STAGES = setOf(
    DriverStage.READ_ONLY_ALPHA,
    DriverStage.READ_ONLY_BETA,
    DriverStage.WRITE_BETA,
    DriverStage.STABLE,
)

/** Estágios que a tela `1f` mostra no grupo "EM PESQUISA — AINDA NÃO FUNCIONA". */
private val RESEARCH_STAGES = setOf(
    DriverStage.DRAFT,
    DriverStage.DISCOVERY_ONLY,
)

/**
 * Monta a lista de compatibilidade real da tela `1f` a partir do [DriverRegistry] — nunca texto
 * fixo (issue #73: "para não ficar desatualizado quando um driver novo entrar ou mudar de
 * estágio"). `DEPRECATED`/`BLOCKED` são excluídos por completo (nunca oferecidos a um usuário
 * entrando no onboarding — não há hoje nenhum profile nesses estágios no catálogo).
 *
 * Dedupe por `(vendor, model)`: quando mais de um profile existe para o mesmo modelo comercial
 * (ex.: TP-Link Archer C6 tem `tplink-encrypted-web-driver` em `DRAFT` e `tplink-stok-luci-driver`
 * em `READ_ONLY_ALPHA`), o modelo aparece **uma única vez**, com o estágio de maior maturidade
 * entre os profiles do grupo.
 */
fun DriverRegistry.buildOnboardingCompatibilityCatalog(): OnboardingCompatibilityCatalog {
    val relevantProfiles = profiles().filter { it.stage in READING_STAGES || it.stage in RESEARCH_STAGES }

    val grouped = LinkedHashMap<Pair<String, String>, MutableList<CompatibilityProfile>>()
    for (profile in relevantProfiles) {
        val key = profile.vendor to profile.model
        grouped.getOrPut(key) { mutableListOf() }.add(profile)
    }

    val readingData = mutableListOf<OnboardingCompatibleDevice>()
    val inResearch = mutableListOf<OnboardingCompatibleDevice>()

    for ((key, profilesForModel) in grouped) {
        val bestProfile = profilesForModel.maxBy { it.stage.ordinal }
        val device = OnboardingCompatibleDevice(
            vendor = key.first,
            model = key.second,
            typeLabel = bestProfile.deviceType.toOnboardingTypeLabel(),
            stage = bestProfile.stage,
        )
        if (bestProfile.stage in READING_STAGES) readingData += device else inResearch += device
    }

    return OnboardingCompatibilityCatalog(readingData = readingData, inResearch = inResearch)
}

/** Vocabulário PT-BR de tipo de equipamento — nunca o enum cru em inglês para o usuário final. */
private fun CatalogDeviceType.toOnboardingTypeLabel(): String = when (this) {
    CatalogDeviceType.ROUTER -> "Roteador"
    CatalogDeviceType.ONT -> "ONT"
    CatalogDeviceType.ONU -> "ONU"
    CatalogDeviceType.MESH -> "Mesh"
    CatalogDeviceType.AP -> "Ponto de acesso"
    CatalogDeviceType.REPEATER -> "Repetidor"
    CatalogDeviceType.UNKNOWN -> "Equipamento"
}

/**
 * Vocabulário PT-BR de estágio — nunca "Homologado"/"Suportado (Beta)" (não é vocabulário de
 * `/ciclo-vida-driver`), conforme tabela da spec §0.
 */
fun DriverStage.toOnboardingStageLabel(): String = when (this) {
    DriverStage.READ_ONLY_ALPHA, DriverStage.READ_ONLY_BETA -> "Leitura"
    DriverStage.DRAFT, DriverStage.DISCOVERY_ONLY -> "Em pesquisa"
    DriverStage.WRITE_BETA, DriverStage.STABLE -> "Leitura e escrita"
    DriverStage.DEPRECATED, DriverStage.BLOCKED -> "Indisponível"
}
