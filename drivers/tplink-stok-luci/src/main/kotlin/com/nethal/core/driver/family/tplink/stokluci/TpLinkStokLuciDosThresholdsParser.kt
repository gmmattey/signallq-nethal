package com.nethal.core.driver.family.tplink.stokluci

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Parser do corpo já decifrado de `admin/security_settings?form=dos_setting` (ver
 * [TpLinkStokLuciDriverConfig.dosSettingPath]/[TpLinkStokLuciDriverConfig.dosSettingQuery]) —
 * cobre `READ_DOS_PROTECTION_THRESHOLDS` (issue #34). Leitura pura de configuração de segurança já
 * existente no equipamento, sem alterar nada.
 *
 * Campos documentados em `TPLINK_ARCHER_ROUTER_FIELD_MAP.md` (seção Security): `icmp_low/middle/high`,
 * `syn_low/middle/high`, `udp_low/middle/high`. Best-effort, mesma filosofia defensiva do resto do
 * parser desta plataforma: nunca lança, ausência de campo vira `null`.
 */
internal object TpLinkStokLuciDosThresholdsParser {

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(rawBody: String): TpLinkStokLuciDosThresholds {
        val root = runCatching { json.parseToJsonElement(rawBody) }.getOrNull() as? JsonObject
            ?: return empty()
        val fields = (root["data"] as? JsonObject) ?: root

        return TpLinkStokLuciDosThresholds(
            icmp = threshold(fields, "icmp"),
            syn = threshold(fields, "syn"),
            udp = threshold(fields, "udp"),
        )
    }

    private fun empty() = TpLinkStokLuciDosThresholds(
        icmp = TpLinkStokLuciDosThreshold(null, null, null),
        syn = TpLinkStokLuciDosThreshold(null, null, null),
        udp = TpLinkStokLuciDosThreshold(null, null, null),
    )

    private fun threshold(fields: JsonObject, prefix: String): TpLinkStokLuciDosThreshold =
        TpLinkStokLuciDosThreshold(
            low = intField(fields, "${prefix}_low"),
            middle = intField(fields, "${prefix}_middle"),
            high = intField(fields, "${prefix}_high"),
        )

    private fun intField(obj: JsonObject, key: String): Int? =
        (obj[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
}
