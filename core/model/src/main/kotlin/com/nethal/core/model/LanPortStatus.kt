package com.nethal.core.model

/**
 * Status físico de uma porta LAN Ethernet — parte do payload de `READ_LAN_PORT_STATUS` (issue #30).
 * Capability genérica (vocabulário `/modelo-capacidades`), não vendor-specific: qualquer
 * equipamento com portas LAN gerenciáveis pode implementá-la. Dado bruto (MAC não incluído aqui,
 * ver `LanStatus`/`ConnectedClient` para isso), sem mascaramento — mesma decisão de ADR 0001.
 */
data class LanPort(
    /** Número da porta física (1-based), na ordem em que o equipamento reporta. */
    val portNumber: Int,
    /** Status físico de link — `true` para "Up", `false` para "NoLink"/qualquer outro valor. */
    val isUp: Boolean,
    /** Velocidade negociada, como o equipamento reporta (ex.: `"1000"`, `"Auto"`) — sem normalizar
     * para um enum, formato varia por fabricante/firmware. */
    val linkSpeedMbps: String? = null,
    /** Pacotes descartados/errados enviados por esta porta (camada física). */
    val errorsSent: Long? = null,
    /** Pacotes descartados/errados recebidos por esta porta (camada física). */
    val errorsReceived: Long? = null,
)

/** Lista de portas LAN — payload de `READ_LAN_PORT_STATUS`. */
data class LanPortStatusList(
    val ports: List<LanPort> = emptyList(),
)
