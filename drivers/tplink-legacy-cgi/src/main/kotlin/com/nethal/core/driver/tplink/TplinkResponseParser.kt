package com.nethal.core.driver.tplink

/**
 * Parser dos endpoints de leitura pós-login. **Nomes de endpoint e de campo abaixo são hipóteses
 * de trabalho, não confirmadas contra hardware real** — diferente do Nokia (onde os 4 endpoints
 * vieram do driver de produção do SignallQ), não há nenhuma fonte primária ou secundária desta
 * pesquisa que tenha documentado os endpoints JSON internos pós-login do Archer C6 (ver
 * `fingerprintEvidence[type=webui_menu_structure]` no catálogo: só a estrutura de páginas de alto
 * nível do emulador oficial foi confirmada, não os endpoints de dados). O parser é
 * best-effort/defensivo por design: campos ausentes viram `null` em vez de lançar exceção, para
 * que uma resposta parcialmente diferente do esperado não derrube o snapshot inteiro.
 *
 * Formato assumido: JSON simples de um nível (`{"campo": "valor"}`), por analogia com o padrão
 * observado em outros drivers de WebUI doméstica já implementados neste projeto (ex.: Nokia
 * `device_status.cgi`/`getppp`). Precisa ser confirmado/corrigido no primeiro teste real.
 */
internal object TplinkResponseParser {

    fun parseDeviceInfo(json: String): TplinkDeviceInfo? {
        val fields = extractFlatJsonFields(json)
        val model = fields["model"] ?: fields["Model"] ?: return null
        return TplinkDeviceInfo(
            model = model,
            hardwareVersion = fields["hardwareVersion"] ?: fields["HardwareVersion"].orEmpty(),
            firmwareVersion = fields["firmwareVersion"] ?: fields["FirmwareVersion"].orEmpty(),
            uptimeSeconds = (fields["uptime"] ?: fields["Uptime"])?.toLongOrNull() ?: 0L,
        )
    }

    fun parseWanStatus(json: String): TplinkWanStatus? {
        val fields = extractFlatJsonFields(json)
        val connectionType = fields["connectionType"] ?: fields["wanType"] ?: return null
        return TplinkWanStatus(
            connectionType = connectionType,
            externalIp = fields["externalIp"] ?: fields["wanIp"].orEmpty(),
            gateway = fields["gateway"].orEmpty(),
            primaryDns = fields["primaryDns"] ?: fields["dns1"].orEmpty(),
            secondaryDns = fields["secondaryDns"] ?: fields["dns2"].orEmpty(),
            isConnected = (fields["isConnected"] ?: fields["connected"])?.toBooleanStrictOrNull()
                ?: (fields["connectionStatus"]?.equals("Connected", ignoreCase = true) ?: false),
        )
    }

    /** Endpoint hipotético devolve as duas bandas em uma lista; cada objeto vira um [TplinkWifiStatus]. */
    fun parseWifiStatus(json: String): List<TplinkWifiStatus> {
        return extractJsonObjectList(json).mapNotNull { fields ->
            val band = fields["band"] ?: return@mapNotNull null
            TplinkWifiStatus(
                band = band,
                enabled = (fields["enabled"] ?: fields["radioEnabled"])?.toBooleanStrictOrNull() ?: false,
                ssid = fields["ssid"].orEmpty(),
                channel = fields["channel"]?.toIntOrNull() ?: 0,
            )
        }
    }

    fun parseConnectedClients(json: String): List<TplinkConnectedClient> {
        return extractJsonObjectList(json).mapNotNull { fields ->
            val ipAddress = fields["ipAddress"] ?: fields["ip"] ?: return@mapNotNull null
            TplinkConnectedClient(
                hostname = fields["hostname"].orEmpty(),
                ipAddress = ipAddress,
                macAddressMasked = maskMac(fields["macAddress"] ?: fields["mac"].orEmpty()),
                connectionType = fields["connectionType"] ?: fields["type"].orEmpty(),
            )
        }
    }

    /**
     * Mascara os 3 últimos octetos do MAC (mantém só o OUI/fabricante) — mesma regra de
     * telemetria sanitizada usada em todo o NetHAL (nunca MAC completo), aplicada aqui já na
     * origem do parsing para que nenhum código consumidor precise lembrar de mascarar depois.
     */
    private fun maskMac(mac: String): String {
        val parts = mac.split(":", "-")
        if (parts.size != 6) return "**:**:**:**:**:**"
        return "${parts[0]}:${parts[1]}:${parts[2]}:**:**:**"
    }

    /** Extrai pares `"chave": "valor"` ou `"chave": valor` de um JSON de um nível, sem parser completo. */
    private fun extractFlatJsonFields(json: String): Map<String, String> {
        val regex = Regex(""""([A-Za-z0-9_]+)"\s*:\s*"?([^",}\]]+?)"?\s*[,}]""")
        return regex.findAll(json).associate { it.groupValues[1] to it.groupValues[2].trim() }
    }

    /** Extrai objetos `{...}` de um array JSON `[{...}, {...}]`, aplicando [extractFlatJsonFields] a cada um. */
    private fun extractJsonObjectList(json: String): List<Map<String, String>> {
        val objectRegex = Regex("""\{[^{}]*}""")
        return objectRegex.findAll(json).map { extractFlatJsonFields(it.value) }.filter { it.isNotEmpty() }.toList()
    }
}
