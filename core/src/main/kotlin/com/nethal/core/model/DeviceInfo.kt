package com.nethal.core.model

enum class DeviceType {
    ROUTER,
    ONT,
    ONU,
    MESH,
    AP,
    REPEATER,
    UNKNOWN,
}

data class DeviceInfo(
    val vendor: String? = null,
    val model: String? = null,
    val firmware: String? = null,
    val hardwareVersion: String? = null,
    val serialNumberHash: String? = null,
    val uptimeSeconds: Long? = null,
    val deviceType: DeviceType? = null,
)
