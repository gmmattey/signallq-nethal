package com.nethal.core.catalog

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/**
 * Modelo Kotlin do manifesto de compatibilidade (schema documentado em
 * `docs/drivers/compatibility-catalog.md`, dados de `catalog-<YYYY.MM.DD>.json`). Espelha o
 * schema do Diego campo a campo — este módulo só desserializa e expõe, nunca reinterpreta ou
 * preenche valor plausível no lugar de `null`.
 */
@Serializable
data class CompatibilityManifest(
    @SerialName("\$schema") val schema: String,
    val manifestVersion: String,
    val generatedAt: String,
    val generatedBy: String,
    val previousManifest: String? = null,
    val profiles: List<CompatibilityProfile>,
)

/** Vocabulário de estágio de driver — mesmo de `/ciclo-vida-driver` e spec §9. */
@Serializable
enum class DriverStage {
    DRAFT,
    DISCOVERY_ONLY,
    READ_ONLY_ALPHA,
    READ_ONLY_BETA,
    WRITE_BETA,
    STABLE,
    DEPRECATED,
    BLOCKED,
}

@Serializable
enum class CatalogDeviceType {
    ROUTER,
    ONT,
    ONU,
    MESH,
    AP,
    REPEATER,
    UNKNOWN,
}

@Serializable
data class CompatibilityProfile(
    val profileId: String,
    val vendor: String,
    val model: String,
    val deviceType: CatalogDeviceType,
    // Linha de produto comercial (ex.: "Archer") — renomeado de `family` para não colidir com
    // `driverFamilyId`, que é outro conceito (chave de resolução de Driver Family, HAL §5.6/§11.1).
    val productLine: String,
    // Metadado de catálogo apontando para a plataforma tecnológica compartilhada
    // (ex. "tplink-encrypted-web", "nokia-gpon-rsa-aes"). Deliberadamente `String` simples, sem
    // tipo Kotlin próprio — decisão explícita do Luiz (hal-layering-model.md §11.1).
    val platformId: String,
    // Chave de resolução no futuro Driver Family Registry (ainda não implementado — passo 6 do
    // plano de refatoração). Por enquanto é só dado de catálogo, sem nenhuma lógica associada.
    val driverFamilyId: String,
    // Payload opaco de configuração específica do driver (endpoints, seções, mapeamento de
    // campos). Cada Driver Family interpreta seu próprio formato — nenhum schema comum é imposto
    // entre plataformas diferentes (ex. TP-Link vs Nokia).
    val driverConfig: JsonElement = JsonNull,
    val firmwareKnown: List<String> = emptyList(),
    val stage: DriverStage,
    val stageReason: String,
    val physicalTestAccess: Boolean,
    val physicalTestAccessNote: String? = null,
    val managementDefaults: ManagementDefaults,
    // Documental — nunca lido para preencher login automático. Ver regra explícita em
    // `compatibility-catalog.md` e `SECURITY.md`.
    val credentialConvention: CredentialConvention,
    val fingerprintEvidence: List<FingerprintEvidenceEntry> = emptyList(),
    val expectedProtocols: List<ExpectedProtocolEntry> = emptyList(),
    val capabilities: List<CatalogCapabilityEntry> = emptyList(),
    val knownFirmwareBugs: List<KnownFirmwareBug> = emptyList(),
    val operatorProvisioningRisk: OperatorProvisioningRisk? = null,
    val confidenceScoreOverall: Double,
    val confidenceScoreOverallNote: String,
)

@Serializable
data class ManagementDefaults(
    val candidateIps: List<String> = emptyList(),
    val ipConfidence: Double,
    val ipConfidenceNote: String,
    val managementPort: Int,
    val managementPortNote: String,
)

@Serializable
data class CredentialConvention(
    val defaultUser: String? = null,
    val defaultPasswordPattern: String? = null,
    val confidence: Double,
    val confidenceNote: String,
    val policyNote: String,
)

@Serializable
enum class FingerprintEvidenceType {
    @SerialName("html_title") HTML_TITLE,
    @SerialName("http_headers") HTTP_HEADERS,
    @SerialName("management_protocol") MANAGEMENT_PROTOCOL,
    @SerialName("webui_menu_structure") WEBUI_MENU_STRUCTURE,
    @SerialName("auth_mechanism") AUTH_MECHANISM,
    @SerialName("session_behavior") SESSION_BEHAVIOR,
    @SerialName("vendor_app_reference") VENDOR_APP_REFERENCE,
    @SerialName("product_documentation") PRODUCT_DOCUMENTATION,
    // Referência a projeto open source de terceiros que documenta/testa o mesmo modelo (ex.:
    // integração de Home Assistant para TP-Link) — achado incidental durante o passo 4 do plano de
    // refatoração HAL, mesmo motivo de `FingerprintConfidenceLevel.REFUTED`: valor já existia em
    // manifestos publicados desde catalog-2026.07.11, mas nenhum teste carregava essas versões via
    // DefaultDriverRegistry até agora.
    @SerialName("vendor_class_reference") VENDOR_CLASS_REFERENCE,
}

@Serializable
enum class FingerprintConfidenceLevel {
    NONE_VERIFIED,
    LOW,
    MEDIUM,
    MEDIUM_HIGH,
    HIGH,
    // Mecanismo/hipótese testado e refutado por evidência real (ex.: tentativa de login que
    // retornou HTTP 500) — usado por `fingerprintEvidence[]` desde o manifesto catalog-2026.07.11
    // (achado incidental durante o passo 4 do plano de refatoração HAL: o valor já existia em três
    // manifestos publicados, mas nenhum teste carregava essas versões via DefaultDriverRegistry
    // até agora, então a ausência deste valor no enum nunca quebrou nada em CI).
    REFUTED,
}

/**
 * `value` chega como `JsonElement` porque o schema documenta `<string|string[]|null>` —
 * evita um parser dedicado só para essa união de tipos nesta entrega.
 */
@Serializable
data class FingerprintEvidenceEntry(
    val type: FingerprintEvidenceType,
    val value: kotlinx.serialization.json.JsonElement,
    val confidence: Double,
    val confidenceLevel: FingerprintConfidenceLevel,
    val source: String,
    val note: String? = null,
)

@Serializable
enum class ProtocolDetectionState {
    SUPPORTED,
    DETECTED_BUT_UNSUPPORTED,
    REQUIRES_AUTH,
    BLOCKED,
    UNKNOWN,
}

@Serializable
data class ExpectedProtocolEntry(
    val protocol: String,
    val detectionState: ProtocolDetectionState,
    val note: String? = null,
)

@Serializable
data class CatalogCapabilityEntry(
    val id: String,
    val state: String,
    val reason: String? = null,
)

@Serializable
data class KnownFirmwareBug(
    val description: String,
    val confidence: Double,
    val source: String,
)

@Serializable
enum class ProvisioningRiskLevel { LOW, MEDIUM, HIGH }

@Serializable
data class OperatorProvisioningRisk(
    val risk: ProvisioningRiskLevel,
    val note: String,
)
