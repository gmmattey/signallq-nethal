package com.nethal.core.model

/**
 * Um nó/cliente da topologia mesh — parte do payload de `READ_MESH_TOPOLOGY` (issue #32). Dado bruto
 * (MAC completo, IP, hostname), sem mascaramento — mesma decisão de ADR 0001
 * (`docs/architecture/adr/0001-fronteira-sanitizacao-telemetria.md`) já aplicada a
 * `ConnectedClient`.
 *
 * `wireType` carrega o valor bruto do campo `wire_type` do equipamento (ex.: TP-Link
 * `mesh_nclient_list[].wire_type`) — sem semântica própria decidida neste modelo porque nenhuma
 * evidência ao vivo confirmou os valores possíveis desse campo; interpretação (com/sem fio) fica
 * para quem consumir, não para o parser do driver inventar.
 */
data class MeshTopologyNode(
    val macAddress: String? = null,
    val hostname: String? = null,
    val ipAddress: String? = null,
    val wireType: String? = null,
    val guestNetwork: Boolean? = null,
    val accessTimeEpochSeconds: Long? = null,
)

/**
 * Topologia mesh completa — grafo de nós + clientes por nó, complementar (não substituto) a
 * `READ_CONNECTED_CLIENTS`. Mais estável que o scanner ativo do Android porque vem do próprio
 * servidor DHCP/mesh do roteador (ver Task #32).
 *
 * `satelliteNodeCount` reflete só a contagem de `mesh_sclient_list` (nós satélite/extensores
 * pareados) — o shape exato de cada nó satélite não tem evidência ao vivo confirmada ainda (lista
 * vazia em todo teste real até aqui, sem extensor OneMesh pareado no equipamento testado), então o
 * modelo não expõe uma lista estruturada de satélites até essa evidência existir.
 */
data class MeshTopology(
    val routerModel: String? = null,
    val routerName: String? = null,
    val routerMacAddress: String? = null,
    val clients: List<MeshTopologyNode> = emptyList(),
    val satelliteNodeCount: Int = 0,
)
