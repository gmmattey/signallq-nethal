package com.nethal.core.driver.nokia

/**
 * Tipos de resultado do driver Nokia G-1425G-B (profile `nokia_g1425gb_v1` do catálogo).
 * Não force estes campos dentro de `DeviceInfo`/`WifiStatus` genéricos: GPON, WAN e PPP são
 * conceitos próprios de ONT/RGW que ainda não têm equivalente no vocabulário genérico do core.
 * Capabilities candidatas destes tipos: READ_DEVICE_INFO, READ_WAN_STATUS, READ_SIGNAL,
 * READ_UPTIME, READ_FIRMWARE (ver catalog-2026.07.13.json, profile nokia_g1425gb_v1).
 */

/** Status óptico GPON — potência RX/TX em dBm, temperatura do transceptor, tensão e corrente do laser. */
data class NokiaGponStatus(
    val isUp: Boolean,
    val connectionMode: String,
    val rxPowerDbm: Double,
    val txPowerDbm: Double,
    val transceiverTemperatureCelsius: Double,
    val serialNumber: String,
    val supplyVoltageVolts: Double,
    val laserCurrentMilliAmps: Double,
)

/** Status da conexão WAN (IP externo, gateway, DNS, VLAN, tipo de conexão, uptime). */
data class NokiaWanStatus(
    val externalIp: String,
    val gateway: String,
    val primaryDns: String,
    val secondaryDns: String,
    val vlanId: String,
    val interfaceName: String,
    val pppoeConcentratorName: String,
    val connectionType: String,
    val connectionUptimeSeconds: Long,
)

/** Status da sessão PPP (quando a WAN é PPPoE), lido de `/index.cgi?getppp`. */
data class NokiaPppStatus(
    val isConnected: Boolean,
    val connectionStatus: String,
    val connectionType: String,
    val sessionName: String,
    val lastConnectionError: String,
)

/** Identificação do equipamento, lida de `/device_status.cgi`. */
data class NokiaDeviceInfo(
    val model: String,
    val manufacturer: String,
    val serialNumber: String,
    val softwareVersion: String,
    val hardwareVersion: String,
    val uptimeSeconds: Long,
)

/**
 * Evidência de fingerprint passivo (título HTML e header `Server`) da própria página de login,
 * já obtida pelo GET que `NokiaAuthenticationClient.login()` faz na raiz do equipamento para
 * extrair `pubkey`/`nonce`/`csrf_token` — não é uma nova chamada de rede, só a exposição de um
 * dado que já existia em memória durante o handshake. Nenhum dos dois campos é sensível
 * (não é credencial): título de página e header de servidor HTTP.
 */
data class NokiaLoginPageEvidence(
    val httpTitle: String?,
    val serverHeader: String?,
)

/** Um cliente visível na tabela de rede local da UI (`/lan_status.cgi?wlan`). */
data class NokiaConnectedClient(
    val status: String,
    val connectionType: String,
    val deviceName: String,
    val ipAddress: String,
    val macAddressMasked: String,
    val allocation: String,
    val leaseRemaining: String,
    val lastActiveTime: String,
)

/** Snapshot agregado dos 4 endpoints de leitura, retornado pelo orquestrador do driver. */
data class NokiaDriverSnapshot(
    val gpon: NokiaGponStatus?,
    val wan: NokiaWanStatus?,
    val ppp: NokiaPppStatus?,
    val deviceInfo: NokiaDeviceInfo?,
    val connectedClients: List<NokiaConnectedClient> = emptyList(),
    val loginPageEvidence: NokiaLoginPageEvidence? = null,
)
