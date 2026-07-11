package com.nethal.core.driver.family.tplink.xdrds

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

@Serializable
internal data class TpLinkXdrDsDriverConfig(
    val encryptInfoPath: String = "/",
    val loginPath: String = "/",
    val authenticatedPathTemplate: String = "/stok={stok}/ds",
    val authenticatedReadPayloadJson: String,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun fromJsonElement(element: JsonElement): TpLinkXdrDsDriverConfig =
            json.decodeFromJsonElement(serializer(), element)
    }
}
