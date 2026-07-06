package com.nethal.core.fingerprint

import com.nethal.core.catalog.CompatibilityProfile
import com.nethal.core.catalog.DriverRegistry
import com.nethal.core.catalog.FingerprintEvidenceType
import com.nethal.core.model.DetectedProtocol
import com.nethal.core.model.NetworkTarget
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Resultado do Fingerprint Engine para um `NetworkTarget` (spec §8.2). `vendor`/`model` ficam
 * `null` quando nenhum profile do catálogo bate com confiança suficiente — nunca forçamos um
 * match. `manifestVersion`/`manifestGeneratedAt` acompanham o resultado para a Tela 3 poder
 * avisar o usuário se o catálogo pode estar desatualizado (spec §11, Tela 3).
 */
data class FingerprintResult(
    val vendor: String?,
    val model: String?,
    val firmware: String?,
    val matchedProfileId: String?,
    val confidence: Double,
    val detectedProtocols: List<DetectedProtocol>,
    val manifestVersion: String,
    val manifestGeneratedAt: String,
    val rawEvidence: HttpFingerprintEvidence?,
)

/**
 * Peso de cada evidência de fingerprint passivo dentro do score do Fingerprint Engine. Segue
 * a heurística documentada em `/protocolos-locais` e `compatibility-catalog.md` ("Scoring de
 * confiança"), mas restrita às parcelas que um probe HTTP passivo pode de fato produzir —
 * autenticação testada (0,20) e capability sanity check (0,15) exigem sessão autenticada e
 * ficam fora de escopo do Fingerprint Engine por definição (nunca faz login).
 *
 * - HEADER_BANNER_MATCH (0,25): título HTML ou header Server/WWW-Authenticate do probe bate
 *   com uma evidência `html_title`/`http_headers` do profile.
 * - CANONICAL_ENDPOINT_MATCH (0,20): IP candidato do target está entre os `candidateIps` do
 *   profile (endpoint de gerência canônico do fabricante).
 * - OFFLINE_CATALOG_PRESENCE (0,10): o profile existe no catálogo offline (sempre 0,10 quando
 *   há qualquer match de vendor/model, mesmo fraco).
 */
private const val WEIGHT_HEADER_BANNER_MATCH = 0.25
private const val WEIGHT_CANONICAL_ENDPOINT_MATCH = 0.20
private const val WEIGHT_OFFLINE_CATALOG_PRESENCE = 0.10

/** Abaixo deste score um match não é reportado como identificação — vira "não identificado". */
private const val MINIMUM_REPORTABLE_CONFIDENCE = 0.10

interface FingerprintEngine {
    suspend fun identify(target: NetworkTarget): FingerprintResult
}

class DefaultFingerprintEngine(
    private val httpFingerprintProbe: HttpFingerprintProbe,
    private val driverRegistry: DriverRegistry,
) : FingerprintEngine {

    override suspend fun identify(target: NetworkTarget): FingerprintResult {
        val evidence = httpFingerprintProbe.probe(target.ip)
        val manifestVersion = driverRegistry.manifestVersion()
        val manifestGeneratedAt = driverRegistry.generatedAt()
        val detectedProtocols = detectProtocols(evidence)

        val bestMatch = driverRegistry.profiles()
            .map { profile -> profile to scoreProfile(profile, target, evidence) }
            .filter { (_, score) -> score >= MINIMUM_REPORTABLE_CONFIDENCE }
            .maxByOrNull { (_, score) -> score }

        val (profile, score) = bestMatch ?: (null to 0.0)

        return FingerprintResult(
            vendor = profile?.vendor,
            model = profile?.model,
            // Firmware nunca vem de fingerprint passivo puro (nenhuma evidência de firmware
            // exato é coletável sem autenticação) — só populado quando/se `firmwareKnown`
            // do profile tiver exatamente um candidato, como indício fraco, nunca afirmação.
            firmware = profile?.firmwareKnown?.singleOrNull(),
            matchedProfileId = profile?.profileId,
            confidence = score,
            detectedProtocols = detectedProtocols,
            manifestVersion = manifestVersion,
            manifestGeneratedAt = manifestGeneratedAt,
            rawEvidence = evidence,
        )
    }

    private fun scoreProfile(
        profile: CompatibilityProfile,
        target: NetworkTarget,
        evidence: HttpFingerprintEvidence?,
    ): Double {
        var score = 0.0

        if (evidence != null && matchesHeaderOrBanner(profile, evidence)) {
            score += WEIGHT_HEADER_BANNER_MATCH
        }

        if (profile.managementDefaults.candidateIps.any { it.equals(target.ip, ignoreCase = true) }) {
            score += WEIGHT_CANONICAL_ENDPOINT_MATCH
        }

        // Presença no catálogo offline só conta quando alguma outra evidência já apontou para
        // este profile — caso contrário, todo profile do catálogo ganharia 0,10 por padrão,
        // inflando confiança sem nenhuma evidência real do target.
        if (score > 0.0) {
            score += WEIGHT_OFFLINE_CATALOG_PRESENCE
        }

        return score.coerceIn(0.0, 1.0)
    }

    /**
     * Compara evidência capturada (título HTML real, headers reais) contra os valores
     * documentados em `fingerprintEvidence[]` do profile. Os dois profiles atuais (Nokia,
     * TP-Link) têm `html_title`/`http_headers` com `value: null` — nenhum match é possível
     * ainda por definição do próprio catálogo, não por bug deste engine. Isso muda assim que
     * o catálogo do Diego ganhar evidência real (primeiro probe físico).
     */
    private fun matchesHeaderOrBanner(profile: CompatibilityProfile, evidence: HttpFingerprintEvidence): Boolean {
        val candidateStrings = listOfNotNull(evidence.httpTitle, evidence.serverHeader, evidence.wwwAuthenticateHeader)
        if (candidateStrings.isEmpty()) return false

        return profile.fingerprintEvidence
            .filter { it.type == FingerprintEvidenceType.HTML_TITLE || it.type == FingerprintEvidenceType.HTTP_HEADERS }
            .any { entry -> jsonValueMatchesAny(entry.value, candidateStrings) }
    }

    private fun jsonValueMatchesAny(value: kotlinx.serialization.json.JsonElement, candidates: List<String>): Boolean {
        val expectedStrings = when (value) {
            is JsonNull -> emptyList()
            is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            is JsonPrimitive -> listOfNotNull(value.contentOrNull)
            else -> emptyList()
        }
        if (expectedStrings.isEmpty()) return false

        return expectedStrings.any { expected ->
            candidates.any { candidate -> candidate.contains(expected, ignoreCase = true) }
        }
    }

    private fun detectProtocols(evidence: HttpFingerprintEvidence?): List<DetectedProtocol> {
        if (evidence == null || evidence.statusCode == null) return emptyList()
        return listOf(DetectedProtocol.HTTP_LOCAL_WEBUI)
    }
}
