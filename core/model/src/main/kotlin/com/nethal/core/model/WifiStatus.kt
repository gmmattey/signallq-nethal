package com.nethal.core.model

enum class WifiBand {
    GHZ_2_4,
    GHZ_5,
    GHZ_6,
    UNKNOWN,
}

/**
 * `ssid` carrega dado bruto (não hash), por decisão de
 * `docs/architecture/adr/0001-fronteira-sanitizacao-telemetria.md`: sanitização de telemetria
 * (hash de SSID, mascaramento de MAC/IP) é responsabilidade exclusiva de um futuro Telemetry
 * Collector, aplicada só na fronteira de exportação — nunca no modelo de dados local consumido
 * pelo NetHAL Lab, que precisa mostrar ao usuário o nome real da própria rede. Campo renomeado de
 * `ssidHash` para `ssid` (2026-07-07) ao ligar este tipo ao primeiro leitor real
 * (`TpLinkStokLuciDriverFamily.readCapability`) — a especificação (`specification.md` §13) segue o
 * mesmo ajuste.
 */
data class WifiRadio(
    val id: String,
    val band: WifiBand,
    val enabled: Boolean? = null,
    val ssid: String? = null,
    val channel: Int? = null,
    val bandwidth: String? = null,
    val security: String? = null,
    val clientCount: Int? = null,
)

data class WifiStatus(
    val radios: List<WifiRadio>,
)
