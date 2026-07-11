package com.nethal.core.driver.family.tplink.gdprcgi

import kotlinx.serialization.Serializable

@Serializable
internal enum class TpLinkGdprCgiCryptoMode {
    AES_CBC,
    AES_GCM,
}

@Serializable
internal enum class TpLinkGdprCgiRsaPaddingMode {
    RAW_NO_PADDING,
    PKCS1_V1_5,
}

@Serializable
internal enum class TpLinkGdprCgiLoginStyle {
    MR_QUERY_LOGIN,
    C50_GDPR_BODY_LOGIN,
    EX_JSON_GDPR_BODY_LOGIN,
}

internal data class TpLinkGdprCgiRsaParams(
    val modulusHex: String,
    val exponentHex: String,
    val sequence: Long,
)

internal data class TpLinkGdprCgiSession(
    val tokenId: String,
    val cookies: Map<String, String>,
)

internal data class TpLinkGdprCgiEncryptionContext(
    val keyAscii: String,
    val ivOrNonceAscii: String,
    val credentialHash: String,
    val rsaParams: TpLinkGdprCgiRsaParams,
)
