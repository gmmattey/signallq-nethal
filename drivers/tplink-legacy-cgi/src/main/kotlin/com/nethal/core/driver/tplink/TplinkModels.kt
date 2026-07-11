package com.nethal.core.driver.tplink

/**
 * Tipos de resultado do driver TP-Link Archer C6 (profile `tplink_archer_c6_v1` do catálogo).
 * Capabilities candidatas: READ_DEVICE_INFO, READ_WAN_STATUS, READ_WIFI_STATUS,
 * READ_CONNECTED_CLIENTS, READ_FIRMWARE — mesmo vocabulário do catálogo, nenhuma ação de escrita
 * (REBOOT_DEVICE listado no catálogo como capability candidata de terceiros, não implementada
 * aqui).
 */

/** Variante de cifra do handshake "web encrypted password", conforme geração de firmware. */
internal enum class TplinkCipherVariant {
    AES_CBC,
    AES_GCM,
}

/** Identificação do equipamento. */
data class TplinkDeviceInfo(
    val model: String,
    val hardwareVersion: String,
    val firmwareVersion: String,
    val uptimeSeconds: Long,
)

/** Status da conexão WAN. */
data class TplinkWanStatus(
    val connectionType: String,
    val externalIp: String,
    val gateway: String,
    val primaryDns: String,
    val secondaryDns: String,
    val isConnected: Boolean,
)

/** Status do rádio Wi-Fi (um por banda — 2.4GHz e 5GHz reportados separadamente pelo orquestrador). */
data class TplinkWifiStatus(
    val band: String,
    val enabled: Boolean,
    val ssid: String,
    val channel: Int,
)

/** Um cliente conectado (LAN cabeada ou Wi-Fi). */
data class TplinkConnectedClient(
    val hostname: String,
    val ipAddress: String,
    val macAddressMasked: String,
    val connectionType: String,
)

/** Snapshot agregado dos endpoints de leitura, retornado pelo orquestrador do driver. */
data class TplinkDriverSnapshot(
    val deviceInfo: TplinkDeviceInfo?,
    val wan: TplinkWanStatus?,
    val wifi: List<TplinkWifiStatus>,
    val connectedClients: List<TplinkConnectedClient>,
)
