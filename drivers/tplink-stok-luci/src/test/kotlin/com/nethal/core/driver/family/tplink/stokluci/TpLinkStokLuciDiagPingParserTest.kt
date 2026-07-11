package com.nethal.core.driver.family.tplink.stokluci

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testes de [TpLinkStokLuciDiagPingParser] — puros, sem rede. Cobre os dois formatos de texto de
 * ping mais comuns (best-effort, sem confirmação por evidência ao vivo do formato real deste
 * firmware) e o caso honesto de formato desconhecido (preserva o texto bruto, não inventa dado).
 */
class TpLinkStokLuciDiagPingParserTest {

    @Test
    fun `parses busybox-style ping summary`() {
        val resultText = "PING 8.8.8.8 (8.8.8.8): 56 data bytes\n" +
            "64 bytes from 8.8.8.8: seq=0 ttl=115 time=12.3 ms\n" +
            "64 bytes from 8.8.8.8: seq=1 ttl=115 time=11.8 ms\n" +
            "--- 8.8.8.8 ping statistics ---\n" +
            "4 packets transmitted, 4 packets received, 0% packet loss\n" +
            "round-trip min/avg/max = 11.8/12.1/12.5 ms"
        val body = """{"success":true,"data":{"result":${jsonString(resultText)}}}"""

        val result = TpLinkStokLuciDiagPingParser.parse(body)

        assertEquals(4, result.packetsSent)
        assertEquals(4, result.packetsReceived)
        assertEquals(0.0, result.packetLossPercent)
        assertEquals(12L, result.averageRoundTripMillis?.toLong())
        assertEquals(listOf(12L, 11L), result.roundTripTimesMillis)
        assertEquals(false, result.timedOut)
        assertEquals(resultText, result.rawResultText)
    }

    @Test
    fun `parses windows-style ping summary`() {
        val resultText = "Pinging 8.8.8.8 with 32 bytes of data:\n" +
            "Reply from 8.8.8.8: bytes=32 time=13ms TTL=115\n" +
            "Reply from 8.8.8.8: bytes=32 time=12ms TTL=115\n" +
            "Packets: Sent = 4, Received = 4, Lost = 0 (0% loss),\n" +
            "Average = 12ms"
        val body = """{"success":true,"data":{"result":${jsonString(resultText)}}}"""

        val result = TpLinkStokLuciDiagPingParser.parse(body)

        assertEquals(4, result.packetsSent)
        assertEquals(4, result.packetsReceived)
        assertEquals(0.0, result.packetLossPercent)
        assertEquals(12.0, result.averageRoundTripMillis)
        assertEquals(false, result.timedOut)
    }

    @Test
    fun `unrecognized format never throws and preserves raw text without inventing structured data`() {
        val resultText = "formato totalmente desconhecido, sem padrao reconhecido"
        val body = """{"success":true,"data":{"result":${jsonString(resultText)}}}"""

        val result = TpLinkStokLuciDiagPingParser.parse(body)

        assertNull(result.packetsSent)
        assertNull(result.packetsReceived)
        assertNull(result.packetLossPercent)
        assertEquals(resultText, result.rawResultText)
    }

    @Test
    fun `full packet loss maps timedOut to true`() {
        val resultText = "4 packets transmitted, 0 packets received, 100% packet loss"
        val body = """{"success":true,"data":{"result":${jsonString(resultText)}}}"""

        val result = TpLinkStokLuciDiagPingParser.parse(body)

        assertEquals(true, result.timedOut)
        assertEquals(0, result.packetsReceived)
    }

    @Test
    fun `missing result field never throws`() {
        val result = TpLinkStokLuciDiagPingParser.parse("""{"success":true,"data":{}}""")

        assertNull(result.rawResultText)
    }

    @Test
    fun `malformed JSON never throws`() {
        val result = TpLinkStokLuciDiagPingParser.parse("not a json at all")

        assertNull(result.rawResultText)
        assertTrue(result.roundTripTimesMillis.isEmpty())
    }

    private fun jsonString(value: String): String =
        kotlinx.serialization.json.JsonPrimitive(value).toString()
}
