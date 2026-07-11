package com.nethal.core.model

/**
 * Vocabulário oficial de capabilities — ver skill /modelo-capacidades e spec §8.6.
 * Nenhum código deve decidir comportamento por fabricante; sempre por capability.
 */
enum class CapabilityId {
    READ_DEVICE_INFO,
    READ_WAN_STATUS,
    READ_LAN_STATUS,
    READ_WIFI_STATUS,
    READ_WIFI_RADIOS,
    READ_CONNECTED_CLIENTS,
    READ_FIRMWARE,
    READ_UPTIME,
    READ_DNS,
    READ_DHCP,
    READ_CPU,
    READ_MEMORY,
    READ_SIGNAL,
    READ_MESH_STATUS,
    /**
     * Contadores de erro da camada GPON (FEC corrigido, erro de cabeçalho, pacotes descartados) —
     * issue #29. Distinto de `READ_SIGNAL`: sinal é potência/temperatura instantânea, isto é
     * contador cumulativo de degradação de linha óptica.
     */
    READ_GPON_ERROR_COUNTERS,
    /**
     * Status físico por porta LAN Ethernet (link up/down, velocidade negociada, erros por porta) —
     * issue #30. Capability genérica (não vendor-specific): qualquer equipamento com portas LAN
     * gerenciáveis pode implementá-la, hoje só o driver Nokia G-1425G-B tem parser real.
     */
    READ_LAN_PORT_STATUS,
    SET_WIFI_SSID,
    SET_WIFI_PASSWORD,
    SET_WIFI_CHANNEL,
    SET_WIFI_BANDWIDTH,
    SET_WIFI_ENABLED,
    SET_DNS,
    REBOOT_DEVICE,
    RESTART_WIFI,
}

enum class CapabilityState {
    AVAILABLE,
    UNAVAILABLE,
    REQUIRES_AUTH,
    EXPERIMENTAL,
    UNSAFE,
    UNKNOWN,
}

data class Capability(
    val id: CapabilityId,
    val state: CapabilityState,
    val confidence: Double,
    val reason: String? = null,
)
