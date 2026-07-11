package com.nethal.core.model

/**
 * Dado real devolvido por uma leitura de capability bem-sucedida
 * ([com.nethal.core.catalog.CapabilityReadResult.Success]) — um caso por [CapabilityId] com parser
 * estruturado real em pelo menos uma Driver Family (issue #16, primeiro caso real é
 * `TpLinkStokLuciDriverFamily`/`READ_WIFI_STATUS`/`READ_LAN_STATUS`/`READ_WAN_STATUS`/
 * `READ_CONNECTED_CLIENTS`; issue #18 soma `DeviceInfo`/`Signal`, primeiro caso real
 * `NokiaGponDriverFamily`/`READ_DEVICE_INFO`/`READ_SIGNAL`).
 *
 * Cresce um caso de cada vez, só quando uma Driver Family real passa a ter parser estruturado para
 * a capability correspondente — nunca existe um caso genérico `Any`/`Map<String, Any?>` aqui: cada
 * leitura devolve o tipo forte que corresponde ao dado que ela promete, mesmo espírito de tipagem
 * forte já usado por `CapabilityId`/`CapabilityState`.
 */
sealed interface CapabilityPayload {
    data class Wifi(val status: WifiStatus) : CapabilityPayload
    data class Lan(val status: LanStatus) : CapabilityPayload
    data class Wan(val status: WanStatus) : CapabilityPayload
    data class ConnectedClients(val clients: ConnectedClientList) : CapabilityPayload
    data class DeviceInfo(val info: com.nethal.core.model.DeviceInfo) : CapabilityPayload
    data class Signal(val status: SignalStatus) : CapabilityPayload
}
