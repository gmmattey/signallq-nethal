package com.nethal.core.util

import java.security.MessageDigest

/**
 * Hash de identificadores de equipamento (ex.: número de série) antes de saírem do driver.
 * SHA-256 sem salt: determinístico por equipamento, mas não reversível para o valor bruto.
 */
object PiiHashing {

    fun sha256Hex(rawValue: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(rawValue.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
