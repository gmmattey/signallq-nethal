package com.nethal.core.model

enum class WifiBand {
    GHZ_2_4,
    GHZ_5,
    GHZ_6,
    UNKNOWN,
}

data class WifiRadio(
    val id: String,
    val band: WifiBand,
    val enabled: Boolean? = null,
    val ssidHash: String? = null,
    val channel: Int? = null,
    val bandwidth: String? = null,
    val security: String? = null,
    val clientCount: Int? = null,
)

data class WifiStatus(
    val radios: List<WifiRadio>,
)
