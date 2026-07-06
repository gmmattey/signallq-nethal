package com.nethal.core.driver.tplink

/**
 * Parser dos endpoints de leitura pós-login do Archer C20. **Nomes de endpoint e de campo abaixo
 * são hipóteses de trabalho, não confirmadas contra hardware real** — mesma disciplina do parser
 * do C6 (`TplinkResponseParser`), reaproveitando a mesma estratégia de extração best-effort/
 * defensiva (campos ausentes viram `null`/lista vazia em vez de exceção), porque a evidência de
 * endpoint desta geração de firmware é ainda mais fraca que a do C6: nenhuma fonte consultada
 * (CVE-2024-57049, projetos comunitários) documenta o corpo de resposta desses endpoints
 * especificamente para o C20, só a existência do prefixo `/cgi`.
 *
 * Reaproveitar `extractFlatJsonFields`/`extractJsonObjectList` do parser do C6 via delegação
 * direta não é possível (métodos privados) — duplicado deliberadamente aqui em vez de tornar público
 * cedo demais um contrato interno que pode divergir entre as duas famílias assim que o teste real
 * mostrar o formato verdadeiro de cada uma (pode não ser JSON, pode ser HTML para scraping).
 */
internal object TplinkC20ResponseParser {

    fun parseDeviceInfo(json: String): TplinkC20DeviceInfo? {
        val fields = extractFlatJsonFields(json)
        val model = fields["model"] ?: fields["Model"] ?: return null
        return TplinkC20DeviceInfo(
            model = model,
            hardwareVersion = fields["hardwareVersion"] ?: fields["HardwareVersion"].orEmpty(),
            firmwareVersion = fields["firmwareVersion"] ?: fields["FirmwareVersion"].orEmpty(),
            uptimeSeconds = (fields["uptime"] ?: fields["Uptime"])?.toLongOrNull() ?: 0L,
        )
    }

    fun parseWanStatus(json: String): TplinkC20WanStatus? {
        val fields = extractFlatJsonFields(json)
        val connectionType = fields["connectionType"] ?: fields["wanType"] ?: return null
        return TplinkC20WanStatus(
            connectionType = connectionType,
            externalIp = fields["externalIp"] ?: fields["wanIp"].orEmpty(),
            gateway = fields["gateway"].orEmpty(),
            primaryDns = fields["primaryDns"] ?: fields["dns1"].orEmpty(),
            secondaryDns = fields["secondaryDns"] ?: fields["dns2"].orEmpty(),
            isConnected = (fields["isConnected"] ?: fields["connected"])?.toBooleanStrictOrNull()
                ?: (fields["connectionStatus"]?.equals("Connected", ignoreCase = true) ?: false),
        )
    }

    fun parseWifiStatus(json: String): List<TplinkC20WifiStatus> {
        return extractJsonObjectList(json).mapNotNull { fields ->
            val band = fields["band"] ?: return@mapNotNull null
            TplinkC20WifiStatus(
                band = band,
                enabled = (fields["enabled"] ?: fields["radioEnabled"])?.toBooleanStrictOrNull() ?: false,
                ssid = fields["ssid"].orEmpty(),
                channel = fields["channel"]?.toIntOrNull() ?: 0,
            )
        }
    }

    fun parseConnectedClients(json: String): List<TplinkC20ConnectedClient> {
        return extractJsonObjectList(json).mapNotNull { fields ->
            val ipAddress = fields["ipAddress"] ?: fields["ip"] ?: return@mapNotNull null
            TplinkC20ConnectedClient(
                hostname = fields["hostname"].orEmpty(),
                ipAddress = ipAddress,
                macAddressMasked = maskMac(fields["macAddress"] ?: fields["mac"].orEmpty()),
                connectionType = fields["connectionType"] ?: fields["type"].orEmpty(),
            )
        }
    }

    /** Mascara os 3 últimos octetos do MAC (mantém só o OUI/fabricante) — mesma regra de telemetria sanitizada de todo o NetHAL. */
    private fun maskMac(mac: String): String {
        val parts = mac.split(":", "-")
        if (parts.size != 6) return "**:**:**:**:**:**"
        return "${parts[0]}:${parts[1]}:${parts[2]}:**:**:**"
    }

    private fun extractFlatJsonFields(json: String): Map<String, String> {
        val regex = Regex(""""([A-Za-z0-9_]+)"\s*:\s*"?([^",}\]]+?)"?\s*[,}]""")
        return regex.findAll(json).associate { it.groupValues[1] to it.groupValues[2].trim() }
    }

    private fun extractJsonObjectList(json: String): List<Map<String, String>> {
        val objectRegex = Regex("""\{[^{}]*}""")
        return objectRegex.findAll(json).map { extractFlatJsonFields(it.value) }.filter { it.isNotEmpty() }.toList()
    }
}
