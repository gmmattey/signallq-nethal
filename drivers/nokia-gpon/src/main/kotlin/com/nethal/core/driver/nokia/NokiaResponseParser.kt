package com.nethal.core.driver.nokia

import kotlin.math.floor
import kotlin.math.log10

/**
 * Parsers HTML/JSON dos 4 endpoints de leitura do driver Nokia G-1425G-B. As páginas de status
 * deste firmware embutem os dados como atribuições JS inline (não JSON puro), por isso o parsing
 * é feito por regex tolerante a variação de aspas/formatação — mesma abordagem adotada pelo
 * driver Nokia de produção do SignallQ (produto irmão), adaptada aqui com vocabulário próprio do
 * NetHAL. Todo parser retorna `null` em vez de lançar exceção: ausência/formato inesperado de
 * dados é um resultado válido para o orquestrador tratar, não uma falha de programação.
 */
internal object NokiaResponseParser {

    fun parseGponStatus(html: String): NokiaGponStatus? {
        return try {
            val connectionStat = extractJsInt(html, listOf("GponConnectionStat"))
            val isUp = connectionStat == 1
            val mode = extractJsString(html, listOf("GponMode", "ConnectionMode")) ?: "—"

            val rxRaw = extractJsNumericToken(html, listOf("RXPower", "RxPower", "ReceivePower"))
            val txRaw = extractJsNumericToken(html, listOf("TXPower", "TxPower", "TransmitPower"))
            val tempRaw = extractJsInt(html, listOf("TransceiverTemperature"))
            val serial = extractJsString(html, listOf("SerialNumber", "GPONSerial")) ?: "—"
            // Typo real conhecido do firmware: "SupplyVottage". Ver knownFirmwareBugs no catálogo.
            val voltageRaw = extractJsInt(html, listOf("SupplyVoltage", "SupplyVottage"))
            // Unidade bruta é 0.5 µA, não µA — daí a divisão por 500 (não 1000) para chegar em mA.
            val laserRaw = extractJsInt(html, listOf("LaserCurrent", "TxBiasCurrent", "BiasCurrent"))

            val rxDbm = normalizeRxSign(convertOpticalPowerToDbm(rxRaw, minDbm = -80.0, maxDbm = 0.0))

            // Thresholds do próprio transceptor (issue #28) — inteiro + fração decimal separada,
            // já em dBm (não passa pela conversão logarítmica de RXPower/TXPower acima). Não
            // confirmado contra corpo bruto real do equipamento (ver combineIntAndFractionDbm).
            val lowerInt = extractJsInt(html, listOf("RXPowerLower"))
            val lowerDec = extractJsInt(html, listOf("RXPowerLowerDec"))
            val upperInt = extractJsInt(html, listOf("RXPowerUpper"))
            val upperDec = extractJsInt(html, listOf("RXPowerUpperDec"))
            val lowerThresholdDbm = lowerInt?.let { combineIntAndFractionDbm(it, lowerDec ?: 0) }
            val upperThresholdDbm = upperInt?.let { combineIntAndFractionDbm(it, upperDec ?: 0) }
            val margin = lowerThresholdDbm?.let { rxDbm - it }

            NokiaGponStatus(
                isUp = isUp,
                connectionMode = mode,
                rxPowerDbm = rxDbm,
                txPowerDbm = convertOpticalPowerToDbm(txRaw, minDbm = -40.0, maxDbm = 10.0),
                transceiverTemperatureCelsius = (tempRaw ?: 0) / 256.0,
                serialNumber = serial,
                supplyVoltageVolts = (voltageRaw ?: 0) / 10_000.0,
                laserCurrentMilliAmps = (laserRaw ?: 0) / 500.0,
                rxPowerLowerThresholdDbm = lowerThresholdDbm,
                rxPowerUpperThresholdDbm = upperThresholdDbm,
                rxPowerMarginToLowerThresholdDb = margin,
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Contadores de erro da camada GPON (issue #29), lidos do objeto `stats` do mesmo endpoint de
     * [parseGponStatus]. Retorna `null` só se nenhum dos três contadores foi encontrado — presença
     * parcial (ex.: só `FECError`) é resultado válido, os demais ficam em `0`.
     */
    fun parseGponErrorCounters(html: String): NokiaGponErrorCounters? {
        return try {
            val fec = extractJsInt(html, listOf("FECError"))
            val hec = extractJsInt(html, listOf("HECError"))
            val drop = extractJsInt(html, listOf("DropPackets"))
            if (fec == null && hec == null && drop == null) return null

            NokiaGponErrorCounters(
                fecErrorCount = (fec ?: 0).toLong(),
                hecErrorCount = (hec ?: 0).toLong(),
                dropPacketsCount = (drop ?: 0).toLong(),
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Status por porta LAN Ethernet (issue #30), lido do array `lan_ether[]` de
     * `lan_status.cgi?lan`. Cada objeto do array traz `Status` ("Up"/"NoLink"),
     * `X_ALU_COM_CurMaxBitRate`/`MaxBitRate` e, dentro de um `stat:{...}` aninhado,
     * `ErrorsSent`/`ErrorsReceived` — [extractJsInt] encontra o campo aninhado sem precisar
     * de um parser de JS estruturado, mesma abordagem tolerante do resto desta classe.
     */
    fun parseLanPortStatus(html: String): List<NokiaLanPort> {
        return try {
            extractJsArrayObjects(html, "lan_ether").mapIndexedNotNull { index, portBody ->
                val statusRaw = extractJsString(portBody, listOf("Status")) ?: return@mapIndexedNotNull null
                NokiaLanPort(
                    portNumber = index + 1,
                    isUp = statusRaw.equals("Up", ignoreCase = true),
                    statusRaw = statusRaw,
                    maxBitRateMbps = extractJsString(portBody, listOf("X_ALU_COM_CurMaxBitRate", "MaxBitRate")) ?: "—",
                    errorsSent = (extractJsInt(portBody, listOf("ErrorsSent")) ?: 0).toLong(),
                    errorsReceived = (extractJsInt(portBody, listOf("ErrorsReceived")) ?: 0).toLong(),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun parseWanStatus(html: String): NokiaWanStatus? {
        return try {
            val connectedBlock = Regex(
                """ConnectionStatus:'Connected'[^}]*?ExternalIPAddress:'([^']*)'[^}]*?RemoteIPAddress:'([^']*)'[^}]*?DNSServers:'([^']*)'""",
                setOf(RegexOption.DOT_MATCHES_ALL),
            ).find(html)

            val externalIp = connectedBlock?.groupValues?.get(1).orEmpty()
            val gateway = connectedBlock?.groupValues?.get(2).orEmpty()
            val dnsEntries = connectedBlock?.groupValues?.get(3).orEmpty()
                .split(",").map(String::trim).filter(String::isNotEmpty)

            if (externalIp.isEmpty() && gateway.isEmpty()) return null

            NokiaWanStatus(
                externalIp = externalIp.ifEmpty { "—" },
                gateway = gateway.ifEmpty { "—" },
                primaryDns = dnsEntries.getOrElse(0) { "—" },
                secondaryDns = dnsEntries.getOrElse(1) { "—" },
                vlanId = Regex("""VLANIDMark:\s*(\d+)""").find(html)?.groupValues?.get(1) ?: "—",
                interfaceName = Regex("""X_ASB_COM_IfName:'([^']*)'""").find(html)?.groupValues?.get(1)?.ifEmpty { "—" } ?: "—",
                pppoeConcentratorName = Regex("""PPPoEACName:'([^']*)'""").find(html)?.groupValues?.get(1)?.ifEmpty { "—" } ?: "—",
                connectionType = Regex("""ConnectionType:'([^']*)'""").find(html)?.groupValues?.get(1)?.ifEmpty { "—" } ?: "—",
                connectionUptimeSeconds = Regex("""Uptime:(\d+),""").find(html)?.groupValues?.get(1)?.toLongOrNull() ?: 0L,
            )
        } catch (_: Exception) {
            null
        }
    }

    fun parsePppStatus(json: String): NokiaPppStatus? {
        return try {
            val entry = extractFirstJsonArrayObject(json, "ppp_status") ?: return null
            val status = extractJsonStringField(entry, "ConnectionStatus") ?: "Unknown"
            NokiaPppStatus(
                isConnected = status == "Connected",
                connectionStatus = status,
                connectionType = extractJsonStringField(entry, "ConnectionType").orEmpty(),
                sessionName = extractJsonStringField(entry, "Name").orEmpty(),
                lastConnectionError = extractJsonStringField(entry, "LastConnectionError").orEmpty(),
            )
        } catch (_: Exception) {
            null
        }
    }

    fun parseDeviceInfo(json: String): NokiaDeviceInfo? {
        return try {
            val model = extractJsonStringField(json, "ModelName").orEmpty()
            val serial = extractJsonStringField(json, "SerialNumber").orEmpty()
            if (model.isEmpty() && serial.isEmpty()) return null

            NokiaDeviceInfo(
                model = model.ifEmpty { "—" },
                manufacturer = extractJsonStringField(json, "Manufacturer")?.ifEmpty { "—" } ?: "—",
                serialNumber = serial.ifEmpty { "—" },
                softwareVersion = extractJsonStringField(json, "SoftwareVersion")?.ifEmpty { "—" } ?: "—",
                hardwareVersion = extractJsonStringField(json, "HardwareVersion")?.ifEmpty { "—" } ?: "—",
                uptimeSeconds = Regex(""""UpTime":(\d+)""").find(json)?.groupValues?.get(1)?.toLongOrNull() ?: 0L,
            )
        } catch (_: Exception) {
            null
        }
    }

    fun parseConnectedClients(html: String): List<NokiaConnectedClient> {
        return try {
            val rows = extractHtmlTableRows(html)
            val headerIndex = rows.indexOfFirst { row ->
                row.size >= 8 &&
                    row[0].equals("Status", ignoreCase = true) &&
                    row[1].equals("Connection Type", ignoreCase = true) &&
                    row[2].equals("Device Name", ignoreCase = true) &&
                    row[3].equals("IPv4 Address", ignoreCase = true) &&
                    row[4].equals("Hardware Address", ignoreCase = true)
            }
            if (headerIndex == -1) return emptyList()

            rows.drop(headerIndex + 1)
                .takeWhile { row -> row.size >= 8 && !row[0].equals("Status", ignoreCase = true) }
                .mapNotNull { row ->
                    val ipAddress = row.getOrNull(3).orEmpty()
                    if (ipAddress.isBlank()) return@mapNotNull null
                    NokiaConnectedClient(
                        status = row.getOrNull(0).orEmpty(),
                        connectionType = row.getOrNull(1).orEmpty(),
                        deviceName = row.getOrNull(2).orEmpty(),
                        ipAddress = ipAddress,
                        macAddressMasked = maskMac(row.getOrNull(4).orEmpty()),
                        allocation = row.getOrNull(5).orEmpty(),
                        leaseRemaining = row.getOrNull(6).orEmpty(),
                        lastActiveTime = row.getOrNull(7).orEmpty(),
                    )
                }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // --- Extração tolerante de atribuições JS inline (não é JSON puro nestes 2 endpoints HTML) ---

    internal fun extractJsString(source: String, keys: List<String>): String? = keys.firstNotNullOfOrNull { key ->
        val escaped = Regex.escape(key)
        listOf(
            Regex(""""$escaped"\s*:\s*"([^"]*)""""),
            Regex(""""$escaped"\s*:\s*'([^']*)'"""),
            Regex("""\b$escaped\b\s*:\s*"([^"]*)""""),
            Regex("""\b$escaped\b\s*:\s*'([^']*)'"""),
            Regex("""\b$escaped\b\s*=\s*"([^"]*)""""),
            Regex("""\b$escaped\b\s*=\s*'([^']*)'"""),
        ).firstNotNullOfOrNull { it.find(source)?.groupValues?.get(1) }
    }

    internal fun extractJsInt(source: String, keys: List<String>): Int? = keys.firstNotNullOfOrNull { key ->
        val escaped = Regex.escape(key)
        listOf(
            Regex(""""$escaped"\s*:\s*(-?\d+)"""),
            Regex("""\b$escaped\b\s*:\s*(-?\d+)"""),
            Regex("""\b$escaped\b\s*=\s*(-?\d+)"""),
        ).firstNotNullOfOrNull { it.find(source)?.groupValues?.get(1)?.toIntOrNull() }
    }

    private fun extractJsNumericToken(source: String, keys: List<String>): String? {
        extractJsString(source, keys)?.let { return it }
        return keys.firstNotNullOfOrNull { key ->
            val escaped = Regex.escape(key)
            listOf(
                Regex(""""$escaped"\s*:\s*(-?\d+(?:[,.]\d+)?)"""),
                Regex("""\b$escaped\b\s*:\s*(-?\d+(?:[,.]\d+)?)"""),
                Regex("""\b$escaped\b\s*=\s*(-?\d+(?:[,.]\d+)?)"""),
            ).firstNotNullOfOrNull { it.find(source)?.groupValues?.get(1) }
        }
    }

    // --- Extração mínima de JSON, sem dependência de biblioteca (endpoints retornam JSON pequeno e previsível) ---

    private fun extractJsonStringField(json: String, key: String): String? =
        Regex(""""${Regex.escape(key)}"\s*:\s*"([^"]*)"""").find(json)?.groupValues?.get(1)

    /** Extrai o primeiro objeto de um array JSON nomeado, ex.: `{"ppp_status":[{...}, ...]}`. */
    private fun extractFirstJsonArrayObject(json: String, arrayKey: String): String? {
        val arrayStart = Regex(""""${Regex.escape(arrayKey)}"\s*:\s*\[""").find(json)?.range?.last ?: return null
        val objStart = json.indexOf('{', arrayStart)
        if (objStart < 0) return null
        var depth = 0
        for (i in objStart until json.length) {
            when (json[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return json.substring(objStart, i + 1)
                }
            }
        }
        return null
    }

    /**
     * Localiza um array JS pelo nome da variável (ex.: `var lan_ether = [{...}, {...}];`) e retorna
     * o corpo bruto de cada objeto `{...}` do array, respeitando aninhamento de chaves (ex.: um
     * `stat:{...}` dentro de cada porta) — mesma técnica de balanceamento de
     * [extractFirstJsonArrayObject], generalizada para todos os objetos do array (não só o
     * primeiro) e para sintaxe JS solta (chave sem aspas, `:` ou `=`), não só JSON estrito.
     */
    internal fun extractJsArrayObjects(source: String, arrayKey: String): List<String> {
        val escaped = Regex.escape(arrayKey)
        val arrayStartRegex = Regex(""""$escaped"\s*:\s*\[|\b$escaped\b\s*[:=]\s*\[""")
        // `range.last` aponta para o `[` de abertura em si — começa em `+1` para não contar essa
        // mesma abertura duas vezes (uma pelo regex, outra pelo `when` abaixo).
        var i = (arrayStartRegex.find(source)?.range?.last ?: return emptyList()) + 1

        val objects = mutableListOf<String>()
        var arrayDepth = 1
        while (i < source.length && arrayDepth > 0) {
            when (source[i]) {
                '[' -> arrayDepth++
                ']' -> arrayDepth--
                '{' -> {
                    val objStart = i
                    var braceDepth = 0
                    while (i < source.length) {
                        when (source[i]) {
                            '{' -> braceDepth++
                            '}' -> {
                                braceDepth--
                                if (braceDepth == 0) {
                                    objects.add(source.substring(objStart, i + 1))
                                    break
                                }
                            }
                        }
                        i++
                    }
                }
            }
            i++
        }
        return objects
    }

    private fun extractHtmlTableRows(html: String): List<List<String>> {
        val rowRegex = Regex("""(?is)<tr\b[^>]*>(.*?)</tr>""")
        val cellRegex = Regex("""(?is)<t[dh]\b[^>]*>(.*?)</t[dh]>""")
        return rowRegex.findAll(html).mapNotNull { rowMatch ->
            val cells = cellRegex.findAll(rowMatch.groupValues[1]).map { cellMatch ->
                normalizeHtmlCell(cellMatch.groupValues[1])
            }.filter { it.isNotEmpty() }.toList()
            cells.takeIf { it.isNotEmpty() }
        }.toList()
    }

    private fun normalizeHtmlCell(raw: String): String {
        val withoutTags = raw.replace(Regex("""(?is)<[^>]+>"""), " ")
        return decodeBasicHtmlEntities(withoutTags)
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun decodeBasicHtmlEntities(value: String): String = value
        .replace("&nbsp;", " ")
        .replace("&#160;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")

    private fun maskMac(mac: String): String {
        val parts = mac.split(":", "-")
        if (parts.size != 6) return "**:**:**:**:**:**"
        return "${parts[0]}:${parts[1]}:${parts[2]}:**:**:**"
    }

    // --- Conversões de unidade ---

    /**
     * Alguns firmwares desta família reportam RX sem o sinal negativo (ex.: "22.7" em vez de
     * "-22.7" dBm). Normaliza para negativo quando o valor cai no range plausível de perda óptica.
     */
    internal fun normalizeRxSign(rxDbm: Double): Double =
        if (rxDbm in 1.0..60.0) -rxDbm else rxDbm

    /**
     * Converte o valor bruto em milliwatts (como o firmware expõe) para dBm, usando a mesma
     * fórmula do firmware: floor(log10(mW * 0.00001) * 1000) / 100. Valores fora do range
     * plausível (`minDbm`..`maxDbm`) são tratados como leitura inválida (retorna 0.0, mesmo
     * sentinel usado pelo driver de produção do SignallQ para "sem leitura").
     */
    internal fun convertOpticalPowerToDbm(rawMilliwattsToken: String?, minDbm: Double, maxDbm: Double): Double {
        val milliwatts = rawMilliwattsToken?.toDoubleOrNull()?.toInt() ?: return 0.0
        if (milliwatts <= 0) return 0.0
        val dbm = floor(log10(milliwatts * 0.00001) * 1000) / 100.0
        return if (dbm in minDbm..maxDbm) dbm else 0.0
    }

    /**
     * Combina os dois campos de threshold RX do firmware (`RXPowerLower`/`RXPowerLowerDec`,
     * `RXPowerUpper`/`RXPowerUpperDec`) num único valor em dBm — issue #28.
     *
     * **Assunção não confirmada contra corpo bruto real do equipamento** (nenhum driver, do NetHAL
     * ou do produto irmão SignallQ, tinha parseado estes dois campos antes desta rodada — só o
     * exemplo textual do levantamento de campo estava disponível: threshold Rx Lower ≈ -27,95 dBm
     * com `RXPowerLower`/`RXPowerLowerDec` não capturados em bruto). Modelo adotado: `intPart` é a
     * parte inteira já com o sinal correto (negativo para threshold de RX, como o resto do driver);
     * `decPart` é a fração em centésimos (0-99) que se soma à magnitude — ex.: `intPart=-27`,
     * `decPart=95` → `-27.95`. Precisa de confirmação em `nokiaManualCheck` real antes de confiar
     * neste valor para qualquer decisão automática (a Tela 4 já expõe como "não confirmado" via
     * `knownFirmwareBugs` do catálogo).
     */
    internal fun combineIntAndFractionDbm(intPart: Int, decPart: Int): Double {
        val fraction = decPart.coerceIn(0, 99) / 100.0
        return if (intPart < 0) intPart - fraction else intPart + fraction
    }
}
