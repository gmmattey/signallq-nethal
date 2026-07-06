package com.nethal.core.model

/**
 * Modelo de dados público do SDK, conforme spec §13. Declarado aqui para a interface pública
 * do NetHAL Core ficar estável desde já — a implementação dos engines que produzem/consomem
 * estes tipos (Discovery, Fingerprint, Capability) é de outra entrega.
 */
enum class TargetRole {
    PRIMARY_GATEWAY,
    UPSTREAM_CANDIDATE,
    MESH_NODE,
    MANUAL,
}

enum class TargetSource {
    GATEWAY,
    SSDP,
    MDNS,
    USER_INPUT,
}

/**
 * Protocolo local candidato/detectado sobre um `NetworkTarget`. Vocabulário reduzido ao que o
 * Protocol Detector (esqueleto, heurísticas completas fora de escopo desta entrega) e o
 * Fingerprint Engine já conseguem sinalizar por evidência HTTP passiva — não é o vocabulário
 * completo de `/protocolos-locais` (SNMP, SSH, TR-064 etc. entram quando houver probe real).
 */
enum class DetectedProtocol {
    HTTP_LOCAL_WEBUI,
    HTTPS_LOCAL_WEBUI,
    UNKNOWN,
}

data class NetworkTarget(
    val ip: String,
    val role: TargetRole,
    val source: TargetSource,
    // Preenchidos pelo Fingerprint Engine/Protocol Detector (Feat 3), nunca pelo Discovery
    // Engine (Feat 2) — este último só resolve ip/role/source. `confidence` segue a mesma
    // escala 0.0-1.0 usada no restante do SDK (Capability, catálogo de compatibilidade).
    val confidence: Double? = null,
    val detectedProtocols: List<DetectedProtocol> = emptyList(),
)

data class DiscoveryResult(
    val devices: List<NetworkTarget>,
    val possibleDoubleNat: Boolean,
)
