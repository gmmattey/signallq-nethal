package com.nethal.core.driver.family.tplink.xdrds

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal object TpLinkXdrDsResponseParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parseNonceLoginVariant(body: String): Pair<Boolean, String?> {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return false to null
        val errorCode = (root["error_code"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
        val nonce = root["nonce"]?.jsonPrimitive?.contentOrNull
        val encryptTypeElement = root["encrypt_type"]
        val encryptTypes = when (encryptTypeElement) {
            is JsonArray -> encryptTypeElement.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
            is JsonPrimitive -> setOfNotNull(encryptTypeElement.contentOrNull)
            else -> emptySet()
        }
        return (errorCode == 0 && "3" in encryptTypes && !nonce.isNullOrBlank()) to nonce
    }

    fun parseStok(body: String): String? {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null
        return root["stok"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    }

    /**
     * `error_code` do envelope JSON de resposta — único campo confirmado em toda a superfície de
     * `/ds` até aqui (usado tanto no probe `get_encrypt_info` quanto, por convenção do próprio
     * dispatcher, no corpo de qualquer leitura). `null` quando o corpo não é JSON válido ou não tem
     * o campo — nunca lança.
     */
    fun parseErrorCode(body: String): Int? {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null
        return (root["error_code"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
    }
}
