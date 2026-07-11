package com.nethal.core.driver.family.tplink.stokluci

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Parser do corpo já decifrado de `admin/status?form=all` (ver [TpLinkStokLuciDriverConfig],
 * leitura autenticada validada ao vivo em [TpLinkStokLuciDriverFamily.readStatusRaw]) para o
 * vocabulário de capabilities do NetHAL (`CapabilityId`, ver `/modelo-capacidades`).
 *
 * Campos confirmados por evidência ao vivo (sessão de teste manual contra a unidade física do
 * Luiz, 2026-07-07, exemplo sanitizado antes de entrar neste código-fonte): `wireless_2g_ssid`,
 * `wireless_5g_ssid`, `wireless_2g_channel`, `wireless_2g_psk_key`, `lan_macaddr`,
 * `lan_ipv4_ipaddr`, `wan_ipv4_ipaddr`, `access_devices_wired` (lista de
 * `{macaddr, ipaddr, hostname}`), `guest_2g_ssid`, `guest_5g_ssid`. Campos análogos por simetria
 * (`wireless_5g_channel`, `guest_2g_channel`, `guest_5g_channel`) são tentados defensivamente, sem
 * confirmação de evidência ao vivo própria — ausência de qualquer campo nunca lança exceção, só
 * produz `null`/lista vazia (mesmo tratamento defensivo de `TpLinkLegacyCgiResponseParser`/
 * `TplinkResponseParser`).
 *
 * `wireless_*_psk_key`/`guest_*_psk_key` (a senha do Wi-Fi) **nunca são lidos para nenhum campo**
 * do modelo resultante — não existe campo para isso em [TpLinkStokLuciWifiRadio] de propósito, essa
 * é uma regra de proibição de coleta, não de sanitização. SSID e MAC são carregados brutos (sem
 * hash, sem mascaramento): por decisão de
 * `docs/architecture/adr/0001-fronteira-sanitizacao-telemetria.md`, sanitização de telemetria é
 * responsabilidade exclusiva de um futuro Telemetry Collector, aplicada só na fronteira de
 * exportação — não do modelo interno do driver.
 *
 * Envelope real observado: `{"success":true,"data":{...campos...}}` (mesmo formato do corpo
 * decifrado de login, ver [TpLinkStokLuciResponseParser]). Aceita também o objeto de campos direto
 * na raiz (sem envelope `data`), para não quebrar se uma geração de firmware futura simplificar o
 * formato — best-effort, nunca lança.
 */
internal object TpLinkStokLuciStatusParser {

    private val json = Json { ignoreUnknownKeys = true }

    fun parseSnapshot(rawBody: String): TpLinkStokLuciSnapshot {
        val root = runCatching { json.parseToJsonElement(rawBody) }.getOrNull() as? JsonObject
            ?: return emptySnapshot()
        val fields = (root["data"] as? JsonObject) ?: root

        return TpLinkStokLuciSnapshot(
            wifi = parseWifiRadios(fields),
            lan = parseLanStatus(fields),
            wan = parseWanStatus(fields),
            connectedClients = parseConnectedClients(fields),
        )
    }

    private fun emptySnapshot() = TpLinkStokLuciSnapshot(
        wifi = emptyList(),
        lan = null,
        wan = null,
        connectedClients = emptyList(),
    )

    private data class RadioSpec(
        val id: String,
        val ssidKey: String,
        val channelKey: String,
        val band: TpLinkStokLuciWifiBand,
        val guest: Boolean,
        /** `null` para rádios sem campo de canal-em-uso/potência confirmado (rede de convidados). */
        val currentChannelKey: String? = null,
        val txPowerKey: String? = null,
    )

    /**
     * `main-2g`/`main-5g` confirmados por evidência ao vivo; `guest-2g`/`guest-5g` idem para o SSID
     * (canal por simetria, não confirmado). `currentChannelKey`/`txPowerKey` (issue #33) só
     * declarados para os rádios principais — campo de campo de canal-em-uso/potência de convidados
     * não documentado em `TPLINK_ARCHER_ROUTER_FIELD_MAP.md`.
     */
    private val radioSpecs: List<RadioSpec> = listOf(
        RadioSpec(
            "main-2g", "wireless_2g_ssid", "wireless_2g_channel", TpLinkStokLuciWifiBand.GHZ_2_4, guest = false,
            currentChannelKey = "wireless_2g_current_channel", txPowerKey = "wireless_2g_txpower",
        ),
        RadioSpec(
            "main-5g", "wireless_5g_ssid", "wireless_5g_channel", TpLinkStokLuciWifiBand.GHZ_5, guest = false,
            currentChannelKey = "wireless_5g_current_channel", txPowerKey = "wireless_5g_txpower",
        ),
        RadioSpec("guest-2g", "guest_2g_ssid", "guest_2g_channel", TpLinkStokLuciWifiBand.GHZ_2_4, guest = true),
        RadioSpec("guest-5g", "guest_5g_ssid", "guest_5g_channel", TpLinkStokLuciWifiBand.GHZ_5, guest = true),
    )

    private fun parseWifiRadios(fields: JsonObject): List<TpLinkStokLuciWifiRadio> =
        radioSpecs.mapNotNull { spec ->
            val ssid = stringField(fields, spec.ssidKey)
            val channel = intField(fields, spec.channelKey)
            val currentChannel = spec.currentChannelKey?.let { intField(fields, it) }
            val txPower = spec.txPowerKey?.let { txPowerField(fields, it) }
            if (ssid == null && channel == null && currentChannel == null && txPower == null) return@mapNotNull null
            TpLinkStokLuciWifiRadio(
                id = spec.id,
                band = spec.band,
                guestNetwork = spec.guest,
                ssid = ssid,
                channel = channel,
                currentChannel = currentChannel,
                txPower = txPower,
            )
        }

    private fun txPowerField(obj: JsonObject, key: String): TpLinkStokLuciTxPower? =
        when (stringField(obj, key)?.lowercase()) {
            "high" -> TpLinkStokLuciTxPower.HIGH
            "middle", "medium" -> TpLinkStokLuciTxPower.MIDDLE
            "low" -> TpLinkStokLuciTxPower.LOW
            null -> null
            else -> TpLinkStokLuciTxPower.UNKNOWN
        }

    private fun parseLanStatus(fields: JsonObject): TpLinkStokLuciLanStatus? {
        val mac = stringField(fields, "lan_macaddr")
        val ip = stringField(fields, "lan_ipv4_ipaddr")
        if (mac == null && ip == null) return null
        return TpLinkStokLuciLanStatus(macAddress = mac, ipv4Address = ip)
    }

    private fun parseWanStatus(fields: JsonObject): TpLinkStokLuciWanStatus? {
        val ip = stringField(fields, "wan_ipv4_ipaddr") ?: return null
        return TpLinkStokLuciWanStatus(ipv4Address = ip)
    }

    private fun parseConnectedClients(fields: JsonObject): List<TpLinkStokLuciConnectedClient> {
        val devices = fields["access_devices_wired"] as? JsonArray ?: return emptyList()
        return devices.mapNotNull { element ->
            val device = element as? JsonObject ?: return@mapNotNull null
            val mac = stringField(device, "macaddr")
            val ip = stringField(device, "ipaddr")
            val hostname = stringField(device, "hostname")
            if (mac == null && ip == null && hostname == null) return@mapNotNull null
            TpLinkStokLuciConnectedClient(
                hostname = hostname,
                ipAddress = ip,
                macAddress = mac,
            )
        }
    }

    private fun stringField(obj: JsonObject, key: String): String? =
        (obj[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun intField(obj: JsonObject, key: String): Int? =
        (obj[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
}
