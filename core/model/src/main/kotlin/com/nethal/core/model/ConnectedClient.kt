package com.nethal.core.model

/**
 * Um dispositivo conectado à LAN — parte do payload de `READ_CONNECTED_CLIENTS`. Dado bruto (MAC
 * completo, IP, hostname), sem mascaramento — mesma decisão de ADR 0001
 * (`docs/architecture/adr/0001-fronteira-sanitizacao-telemetria.md`).
 */
data class ConnectedClient(
    val hostname: String? = null,
    val ipAddress: String? = null,
    val macAddress: String? = null,
)

/** Lista de clientes conectados — payload de `READ_CONNECTED_CLIENTS`. */
data class ConnectedClientList(
    val clients: List<ConnectedClient>,
)
