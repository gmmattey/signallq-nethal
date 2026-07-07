package com.nethal.core.driver.tplink

/**
 * Codec do protocolo real do dispatcher `/cgi` do Archer C20 (profile `tplink_archer_c20_v1`),
 * confirmado por captura via DevTools contra unidade física do Luiz (2026-07-06, ver SIG-337/
 * SIG-338). Substitui o parser especulativo anterior (JSON por endpoint, REFUTED por HTTP 500).
 *
 * Formato de request (corpo `text/plain`, um ou mais blocos):
 * ```
 * [NOME_SECAO#0,0,0,0,0,0#0,0,0,0,0,0]indice,qtdCampos
 * campo1
 * campo2
 * ```
 * `indice` é a posição do bloco na sequência de blocos do request (0-based). O bloco especial
 * `/cgi/info` (0 campos) devolve a confirmação de sessão `[cgi]<indice>` no response — mas ele NÃO
 * é universal: numa captura HAR completa de 252 requisições reais contra a unidade física do Luiz
 * (2026-07-06), `/cgi/info` só apareceu numa única combinação (a carga inicial da tela de Status:
 * `IGD_DEV_INFO+ETH_SWITCH+SYS_MODE+/cgi/info`). Todas as outras 251 requisições reais (single-
 * seção ou multi-seção) tiveram sucesso (`[error]0`) sem nenhum bloco `/cgi/info`. `buildRequestBody`
 * NÃO o inclui automaticamente — o chamador deve passá-lo explicitamente como seção
 * (`"/cgi/info" to emptyList()`) só quando quiser replicar aquele bundle específico.
 *
 * Formato de response (corpo `text/plain`, um bloco por linha de resultado):
 * ```
 * [a,b,c,d,e,f]indice
 * campo1=valor1
 * campo2=valor2
 * ```
 * Seções com múltiplas linhas (ex.: LAN_WLAN com 2 rádios, LAN_HOST_ENTRY com N clientes) repetem
 * o mesmo índice final com prefixos `[a,b,...]` diferentes — cada bloco com um dado índice de
 * seção é tratado como "uma linha de resultado dessa seção", agrupado por índice. O significado
 * exato de `a,b,c,d,e,f` não é conhecido e não é usado por este parser.
 *
 * O bloco de sessão vem como `[cgi]<indice>` com conteúdo tipo JS (`var x=...;`, `$.ret=0`) em vez
 * de `campo=valor` — tratado à parte, nunca confundido com uma seção de dados.
 *
 * Ao final do corpo, sempre um status global `[error]<codigo>` (0 = sucesso; qualquer outro valor
 * é tratado como falha genérica, significado exato dos códigos != 0 ainda não é conhecido).
 *
 * Parsing é defensivo em toda a extensão, mesma filosofia do parser do C6 (`TplinkResponseParser`)
 * e do parser especulativo anterior deste mesmo driver: campo ausente vira `null`, nunca exceção.
 */
internal object TplinkC20ResponseParser {

    /** Nome da seção de sessão, distinta de uma seção de dados normal. */
    private const val SESSION_BLOCK_NAME = "cgi"

    /**
     * Monta o corpo de request para uma lista de seções (nome + campos), na ordem dada. Não inclui
     * nenhum bloco automaticamente — se o chamador quiser o bloco de sessão `/cgi/info`, deve
     * incluí-lo explicitamente na lista (`"/cgi/info" to emptyList()`), replicando exatamente a
     * combinação comprovada por captura real, não uma combinação nova inventada.
     */
    fun buildRequestBody(sections: List<Pair<String, List<String>>>): String {
        val builder = StringBuilder()
        sections.forEachIndexed { index, (sectionName, fields) ->
            builder.append("[$sectionName#0,0,0,0,0,0#0,0,0,0,0,0]$index,${fields.size}\n")
            fields.forEach { field -> builder.append("$field\n") }
        }
        return builder.toString()
    }

    /**
     * Extrai o status global `[error]<codigo>` do corpo de response. Retorna `null` se o marcador
     * não aparecer (resposta inesperada/vazia) — tratado como falha pelo chamador, não como exceção
     * aqui.
     */
    fun extractGlobalErrorCode(body: String): Int? =
        Regex("""\[error](-?\d+)""").find(body)?.groupValues?.get(1)?.toIntOrNull()

    /** `true` quando o response tem `[error]0` — único código de sucesso confirmado. */
    fun isSuccess(body: String): Boolean = extractGlobalErrorCode(body) == 0

    /**
     * Agrupa por índice final de bloco todas as linhas `campo=valor` de blocos de dados
     * (`[a,b,c,d,e,f]indice`), ignorando o bloco de sessão `[cgi]indice` e o status global
     * `[error]codigo`. Cada entrada da lista de um índice é um mapa campo→valor de uma linha.
     */
    private fun parseSectionBlocksByIndex(body: String): Map<Int, List<Map<String, String>>> {
        val lines = body.lines()
        val result = mutableMapOf<Int, MutableList<Map<String, String>>>()

        var currentIndex: Int? = null
        var currentFields: MutableMap<String, String>? = null

        fun flush() {
            val index = currentIndex
            val fields = currentFields
            if (index != null && fields != null) {
                result.getOrPut(index) { mutableListOf() }.add(fields)
            }
            currentIndex = null
            currentFields = null
        }

        val dataBlockHeader = Regex("""^\[[^\[\]]*]\s*(\d+)\s*$""")
        val sessionBlockHeader = Regex("""^\[$SESSION_BLOCK_NAME]\s*(\d+)\s*$""")
        val errorLine = Regex("""^\[error]\s*-?\d+\s*$""")

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            if (errorLine.matches(line)) {
                flush()
                continue
            }
            if (sessionBlockHeader.matches(line)) {
                // Bloco de sessão: não é dado de seção, encerra o bloco de dados corrente e ignora.
                flush()
                continue
            }
            val dataMatch = dataBlockHeader.find(line)
            if (dataMatch != null) {
                flush()
                currentIndex = dataMatch.groupValues[1].toIntOrNull()
                currentFields = mutableMapOf()
                continue
            }

            val eqIndex = line.indexOf('=')
            if (eqIndex > 0 && currentFields != null) {
                val key = line.substring(0, eqIndex).trim()
                val value = line.substring(eqIndex + 1).trim()
                currentFields!![key] = value
            }
        }
        flush()

        return result
    }

    /** Todas as linhas de dados associadas a um dado índice de bloco do request, na ordem em que apareceram. */
    fun linesForIndex(body: String, index: Int): List<Map<String, String>> =
        parseSectionBlocksByIndex(body)[index].orEmpty()

    /** Primeira (e presumivelmente única) linha de dados associada a um índice, ou mapa vazio se ausente. */
    fun firstLineForIndex(body: String, index: Int): Map<String, String> =
        linesForIndex(body, index).firstOrNull().orEmpty()

    fun parseDeviceInfo(body: String, deviceInfoIndex: Int, ethSwitchIndex: Int, sysModeIndex: Int): TplinkC20DeviceInfo? {
        val deviceFields = firstLineForIndex(body, deviceInfoIndex)
        val modelName = deviceFields["modelName"] ?: return null
        val ethFields = firstLineForIndex(body, ethSwitchIndex)
        val sysFields = firstLineForIndex(body, sysModeIndex)

        return TplinkC20DeviceInfo(
            modelName = modelName,
            description = deviceFields["description"].orEmpty(),
            isFactoryDefault = deviceFields["X_TP_isFD"]?.let { it == "1" },
            numberOfVirtualPorts = ethFields["numberOfVirtualPorts"]?.toIntOrNull(),
            mode = sysFields["mode"],
        )
    }

    fun parseWifiStatus(body: String, lanWlanIndex: Int): List<TplinkC20WifiStatus> {
        return linesForIndex(body, lanWlanIndex).mapNotNull { fields ->
            val name = fields["name"] ?: return@mapNotNull null
            TplinkC20WifiStatus(
                name = name,
                ssid = fields["SSID"].orEmpty(),
            )
        }
    }

    fun parseConnectedClients(body: String, lanHostEntryIndex: Int): List<TplinkC20ConnectedClient> {
        return linesForIndex(body, lanHostEntryIndex).mapNotNull { fields ->
            val ipAddress = fields["IPAddress"] ?: return@mapNotNull null
            TplinkC20ConnectedClient(
                hostname = fields["hostName"].orEmpty(),
                ipAddress = ipAddress,
                macAddressMasked = maskMac(fields["MACAddress"].orEmpty()),
                leaseTimeRemainingSeconds = fields["leaseTimeRemaining"]?.toLongOrNull(),
            )
        }
    }

    /** Mascara os 3 últimos octetos do MAC (mantém só o OUI/fabricante) — mesma regra de telemetria sanitizada de todo o NetHAL. */
    private fun maskMac(mac: String): String {
        val parts = mac.split(":", "-")
        if (parts.size != 6) return "**:**:**:**:**:**"
        return "${parts[0]}:${parts[1]}:${parts[2]}:**:**:**"
    }
}
