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

data class NetworkTarget(
    val ip: String,
    val role: TargetRole,
    val source: TargetSource,
)

data class DiscoveryResult(
    val devices: List<NetworkTarget>,
    val possibleDoubleNat: Boolean,
)
