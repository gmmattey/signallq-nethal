package com.nethal.core.driver.family.nokia.gpon

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Schema concreto (opaco para o resto do catálogo, ver `hal-layering-model.md` §5.6/§11.1) do
 * `driverConfig` consumido por [NokiaGponDriverFamily] — só esta Driver Family sabe interpretar
 * este formato.
 *
 * Os 5 caminhos abaixo replicam, um a um, os endpoints antes hardcoded em
 * `com.nethal.core.driver.nokia.NokiaOntDriver.readSnapshot` — migrados para `profile.driverConfig`
 * nesta rodada (issue #18) para essa Driver Family nunca hardcodar endpoint de modelo específico,
 * mesma regra já aplicada a `TpLinkStokLuciDriverConfig`/`TpLinkLegacyCgiDriverConfig`.
 * [pppStatusPath] não corresponde a nenhuma capability do vocabulário oficial (`CapabilityId`) —
 * fica reservado no config por paridade com os 5 endpoints reais do firmware, mesmo sem uso direto
 * em [NokiaGponDriverFamily.readCapability] nesta rodada.
 */
@Serializable
data class NokiaGponDriverConfig(
    /** Status óptico GPON (RX/TX, temperatura, tensão/corrente do laser) — usado por `READ_SIGNAL`. */
    val gponStatusPath: String,
    /** Status da conexão WAN (IP externo, gateway, DNS) — usado por `READ_WAN_STATUS`. */
    val wanStatusPath: String,
    /** Status da sessão PPP (quando a WAN é PPPoE) — sem capability correspondente hoje. */
    val pppStatusPath: String,
    /** Identificação do equipamento (modelo, fabricante, versões) — usado por `READ_DEVICE_INFO`. */
    val deviceInfoPath: String,
    /** Tabela de clientes conectados à rede local — usado por `READ_CONNECTED_CLIENTS`. */
    val connectedClientsPath: String,
    /**
     * Status por porta LAN Ethernet (`lan_ether[]`) — usado por `READ_GPON_ERROR_COUNTERS`
     * (mesmo endpoint de [gponStatusPath], objeto `stats`, então reaproveita [gponStatusPath]) e
     * por `READ_LAN_PORT_STATUS` (issue #30). Endpoint distinto de [connectedClientsPath]:
     * `lan_status.cgi?lan` (portas físicas) vs. `lan_status.cgi?wlan` (clientes conectados).
     * Default `"/lan_status.cgi?lan"` para não quebrar `driverConfig` de manifestos antigos que
     * ainda não têm este campo.
     */
    val lanStatusPath: String = "/lan_status.cgi?lan",
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Desserializa `profile.driverConfig` (`JsonElement` opaco no catálogo) no schema
         * concreto desta Driver Family. Lança se o profile resolvido para `nokia-ont-gpon-driver`
         * não tiver um `driverConfig` no formato esperado — mesma filosofia de falha alta e cedo de
         * `TpLinkLegacyCgiDriverConfig`/`TpLinkStokLuciDriverConfig`.
         */
        fun fromJsonElement(element: JsonElement): NokiaGponDriverConfig =
            json.decodeFromJsonElement(serializer(), element)
    }
}
