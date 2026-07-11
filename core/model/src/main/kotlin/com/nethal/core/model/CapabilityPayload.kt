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

    /**
     * Payload de `READ_DEVICE_INFO` — primeiro parser estruturado real desta capability veio de
     * duas Driver Families em paralelo (`NokiaGponDriverFamily`, issue #18; `TpLinkLegacyCgiDriverFamily`,
     * issue #19). Campo tipado `com.nethal.core.model.DeviceInfo` (fully-qualified aqui só porque o
     * nome do case colide com o nome do tipo, mesmo problema que não existe nos outros cases).
     */
    data class DeviceInfo(val info: com.nethal.core.model.DeviceInfo) : CapabilityPayload
    data class Signal(val status: SignalStatus) : CapabilityPayload

    /**
     * Payload de `READ_GPON_ERROR_COUNTERS` (issue #29) — primeiro parser estruturado real veio de
     * `NokiaGponDriverFamily`. Mesmo padrão de qualificação de nome de `DeviceInfo` acima (case e
     * tipo de campo compartilham nome simples, por isso o tipo é fully-qualified).
     */
    data class GponErrorCounters(val counters: com.nethal.core.model.GponErrorCounters) : CapabilityPayload

    /** Payload de `READ_LAN_PORT_STATUS` (issue #30) — primeiro parser estruturado real veio de `NokiaGponDriverFamily`. */
    data class LanPorts(val status: LanPortStatusList) : CapabilityPayload

    /**
     * Payload de `READ_MESH_TOPOLOGY` (issue #32) — primeiro parser real: `TpLinkStokLuciDriverFamily`
     * / `admin/onemesh_network?form=mesh_topology`. Nome do case colide com o nome do tipo, mesmo
     * caso de [DeviceInfo] acima.
     */
    data class MeshTopology(val topology: com.nethal.core.model.MeshTopology) : CapabilityPayload

    /** Payload de `READ_DOS_PROTECTION_THRESHOLDS` (issue #34) — primeiro parser real: `TpLinkStokLuciDriverFamily` / `admin/security_settings?form=dos_setting`. */
    data class DosProtectionThresholds(val thresholds: com.nethal.core.model.DosProtectionThresholds) : CapabilityPayload
}
