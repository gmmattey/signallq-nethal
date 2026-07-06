package com.nethal.core.driver.tplink

/**
 * Tipos de resultado do driver TP-Link Archer C20 (profile `tplink_archer_c20_v1` do catálogo).
 * Modelos de dados espelham `TplinkModels.kt` (mesma família de fabricante), mas o driver e o
 * cliente de autenticação são deliberadamente separados dos do Archer C6 — geração de firmware e
 * mecanismo de auth diferentes, ver `TplinkC20AuthenticationClient` para o porquê.
 * Capabilities candidatas: READ_DEVICE_INFO, READ_WAN_STATUS, READ_WIFI_STATUS,
 * READ_CONNECTED_CLIENTS, READ_FIRMWARE — nenhuma ação de escrita implementada.
 */

/** Identificação do equipamento. */
data class TplinkC20DeviceInfo(
    val model: String,
    val hardwareVersion: String,
    val firmwareVersion: String,
    val uptimeSeconds: Long,
)

/** Status da conexão WAN. */
data class TplinkC20WanStatus(
    val connectionType: String,
    val externalIp: String,
    val gateway: String,
    val primaryDns: String,
    val secondaryDns: String,
    val isConnected: Boolean,
)

/** Status do rádio Wi-Fi. Arquer C20 é single-band 2.4GHz apenas (AC750 = 2.4GHz + 5GHz reais, ver nota no catálogo sobre AC750 ser dual-band de baixo alcance). */
data class TplinkC20WifiStatus(
    val band: String,
    val enabled: Boolean,
    val ssid: String,
    val channel: Int,
)

/** Um cliente conectado (LAN cabeada ou Wi-Fi). */
data class TplinkC20ConnectedClient(
    val hostname: String,
    val ipAddress: String,
    val macAddressMasked: String,
    val connectionType: String,
)

/** Snapshot agregado dos endpoints de leitura, retornado pelo orquestrador do driver. */
data class TplinkC20DriverSnapshot(
    val deviceInfo: TplinkC20DeviceInfo?,
    val wan: TplinkC20WanStatus?,
    val wifi: List<TplinkC20WifiStatus>,
    val connectedClients: List<TplinkC20ConnectedClient>,
)
