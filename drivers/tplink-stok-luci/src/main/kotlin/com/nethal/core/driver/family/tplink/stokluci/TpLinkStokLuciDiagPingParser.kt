package com.nethal.core.driver.family.tplink.stokluci

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Parser do corpo já decifrado de `admin/diag?form=diag` após a leitura de resultado (issue #26,
 * capability de AÇÃO `RUN_NATIVE_DIAGNOSTIC_PING`). **Sem confirmação por evidência ao vivo do
 * formato exato do campo `result`** até a primeira execução real contra o Archer C6 do Luiz — este
 * parser tenta, best-effort, os dois formatos de texto de ping mais comuns (estilo Linux/BusyBox e
 * estilo Windows), mas [TpLinkStokLuciDiagPingResult.rawResultText] sempre preserva o texto original
 * intacto, para o caso de nenhum padrão bater.
 *
 * Nunca lança — mesma filosofia defensiva do resto do parser desta plataforma.
 */
internal object TpLinkStokLuciDiagPingParser {

    private val json = Json { ignoreUnknownKeys = true }

    /** Estilo BusyBox/Linux: `4 packets transmitted, 4 packets received, 0% packet loss` + `round-trip min/avg/max = 1.2/3.4/5.6 ms`. */
    private val busyboxSummaryRegex = Regex(
        """(\d+)\s+packets transmitted,\s*(\d+)\s+(?:packets )?received,\s*(?:\+\d+ errors,\s*)?(\d+)%\s*packet loss""",
        RegexOption.IGNORE_CASE,
    )
    private val busyboxRttRegex = Regex(
        """(?:round-trip|rtt)\s+min/avg/max(?:/mdev)?\s*=\s*([\d.]+)/([\d.]+)/([\d.]+)""",
        RegexOption.IGNORE_CASE,
    )

    /** Estilo Windows: `Packets: Sent = 4, Received = 4, Lost = 0 (0% loss)` + `Average = 12ms`. */
    private val windowsSummaryRegex = Regex(
        """Sent\s*=\s*(\d+),\s*Received\s*=\s*(\d+),\s*Lost\s*=\s*(\d+)\s*\((\d+)%\s*loss\)""",
        RegexOption.IGNORE_CASE,
    )
    private val windowsAverageRegex = Regex("""Average\s*=\s*([\d.]+)\s*ms""", RegexOption.IGNORE_CASE)

    /** Linhas individuais `time=1.23 ms` / `time<1ms`, presentes nos dois estilos. */
    private val perPacketTimeRegex = Regex("""time[=<]([\d.]+)\s*ms""", RegexOption.IGNORE_CASE)

    /** Recebe o corpo bruto (envelope `{"success":true,"data":{"result":"<texto>"}}` ou raiz direta) já decifrado da leitura `operation=read` de `admin/diag?form=diag`. */
    fun parse(rawBody: String): TpLinkStokLuciDiagPingResult {
        val root = runCatching { json.parseToJsonElement(rawBody) }.getOrNull() as? JsonObject
        val fields = (root?.get("data") as? JsonObject) ?: root
        val resultText = fields?.let { (it["result"] as? JsonPrimitive)?.contentOrNull }
            ?: return TpLinkStokLuciDiagPingResult(null, null, null, emptyList(), null, timedOut = false, rawResultText = null)

        return parseResultText(resultText)
    }

    private fun parseResultText(resultText: String): TpLinkStokLuciDiagPingResult {
        val roundTripTimes = perPacketTimeRegex.findAll(resultText)
            .mapNotNull { it.groupValues[1].toDoubleOrNull()?.let { ms -> (ms).toLong() } }
            .toList()

        busyboxSummaryRegex.find(resultText)?.let { match ->
            val sent = match.groupValues[1].toIntOrNull()
            val received = match.groupValues[2].toIntOrNull()
            val lossPercent = match.groupValues[3].toDoubleOrNull()
            val avg = busyboxRttRegex.find(resultText)?.groupValues?.get(2)?.toDoubleOrNull()
            return TpLinkStokLuciDiagPingResult(
                packetsSent = sent,
                packetsReceived = received,
                packetLossPercent = lossPercent,
                roundTripTimesMillis = roundTripTimes,
                averageRoundTripMillis = avg,
                timedOut = received == 0,
                rawResultText = resultText,
            )
        }

        windowsSummaryRegex.find(resultText)?.let { match ->
            val sent = match.groupValues[1].toIntOrNull()
            val received = match.groupValues[2].toIntOrNull()
            val lossPercent = match.groupValues[4].toDoubleOrNull()
            val avg = windowsAverageRegex.find(resultText)?.groupValues?.get(1)?.toDoubleOrNull()
            return TpLinkStokLuciDiagPingResult(
                packetsSent = sent,
                packetsReceived = received,
                packetLossPercent = lossPercent,
                roundTripTimesMillis = roundTripTimes,
                averageRoundTripMillis = avg,
                timedOut = received == 0,
                rawResultText = resultText,
            )
        }

        // Nenhum padrão conhecido bateu: preserva o texto bruto, sem inventar dado estruturado.
        return TpLinkStokLuciDiagPingResult(
            packetsSent = null,
            packetsReceived = null,
            packetLossPercent = null,
            roundTripTimesMillis = roundTripTimes,
            averageRoundTripMillis = null,
            timedOut = false,
            rawResultText = resultText,
        )
    }
}
