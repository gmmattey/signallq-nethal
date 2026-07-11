package com.nethal.core.driver.family.tplink.stokluci

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Parser do corpo já decifrado de `admin/onemesh_network?form=mesh_topology` (ver
 * [TpLinkStokLuciDriverConfig.meshTopologyPath]/[TpLinkStokLuciDriverConfig.meshTopologyQuery])
 * para o vocabulário de capabilities do NetHAL — cobre `READ_MESH_TOPOLOGY` (issue #32).
 *
 * Campos documentados em `TPLINK_ARCHER_ROUTER_FIELD_MAP.md` (seção OneMesh), sem captura byte a
 * byte própria confirmada nesta rodada ainda: `mesh_nclient_list[]` (`mac`, `hostname`, `ip`,
 * `wire_type`, `guest`, `access_time`), `mesh_sclient_list` (nós satélite — vazio no equipamento de
 * evidência, sem extensor pareado), `model`/`name`/`mac` do próprio roteador. Best-effort, mesma
 * filosofia defensiva de [TpLinkStokLuciStatusParser]: nunca lança, ausência de campo vira `null`.
 */
internal object TpLinkStokLuciMeshTopologyParser {

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(rawBody: String): TpLinkStokLuciMeshTopology {
        val root = runCatching { json.parseToJsonElement(rawBody) }.getOrNull() as? JsonObject
            ?: return empty()
        val fields = (root["data"] as? JsonObject) ?: root

        return TpLinkStokLuciMeshTopology(
            routerModel = stringField(fields, "model"),
            routerName = stringField(fields, "name"),
            routerMacAddress = stringField(fields, "mac"),
            clients = parseClients(fields),
            satelliteNodeCount = (fields["mesh_sclient_list"] as? JsonArray)?.size ?: 0,
        )
    }

    private fun empty() = TpLinkStokLuciMeshTopology(
        routerModel = null,
        routerName = null,
        routerMacAddress = null,
        clients = emptyList(),
        satelliteNodeCount = 0,
    )

    private fun parseClients(fields: JsonObject): List<TpLinkStokLuciMeshNode> {
        val list = fields["mesh_nclient_list"] as? JsonArray ?: return emptyList()
        return list.mapNotNull { element ->
            val node = element as? JsonObject ?: return@mapNotNull null
            val mac = stringField(node, "mac")
            val hostname = stringField(node, "hostname")
            val ip = stringField(node, "ip")
            val wireType = stringField(node, "wire_type")
            val guest = booleanField(node, "guest")
            val accessTime = (node["access_time"] as? JsonPrimitive)?.longOrNull
            if (mac == null && hostname == null && ip == null) return@mapNotNull null
            TpLinkStokLuciMeshNode(
                macAddress = mac,
                hostname = hostname,
                ipAddress = ip,
                wireType = wireType,
                guestNetwork = guest,
                accessTimeEpochSeconds = accessTime,
            )
        }
    }

    private fun stringField(obj: JsonObject, key: String): String? =
        (obj[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    /** Best-effort: aceita booleano JSON real ou `"1"`/`"0"`/`"true"`/`"false"` como string — formato exato não confirmado por evidência ao vivo. */
    private fun booleanField(obj: JsonObject, key: String): Boolean? {
        val primitive = obj[key] as? JsonPrimitive ?: return null
        primitive.booleanOrNull?.let { return it }
        return when (primitive.contentOrNull) {
            "1", "true" -> true
            "0", "false" -> false
            else -> null
        }
    }
}
