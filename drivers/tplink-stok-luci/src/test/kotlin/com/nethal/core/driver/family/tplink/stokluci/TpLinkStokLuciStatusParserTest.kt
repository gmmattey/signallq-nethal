package com.nethal.core.driver.family.tplink.stokluci

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testes de [TpLinkStokLuciStatusParser] — puros, sem rede, cobrindo o mapeamento de campos brutos
 * de `admin/status?form=all` para o vocabulário de capabilities do NetHAL. Por decisão de
 * `docs/architecture/adr/0001-fronteira-sanitizacao-telemetria.md`, o modelo carrega dado bruto
 * (SSID e MAC sem sanitização) — a única regra que permanece é a de proibição de coleta da senha
 * do Wi-Fi (`*_psk_key`), que nunca é lida.
 */
class TpLinkStokLuciStatusParserTest {

    private fun sampleBody(): String = """
        {
          "success": true,
          "data": {
            "wireless_2g_ssid": "CasaLuiz_2G",
            "wireless_2g_channel": "6",
            "wireless_2g_psk_key": "senhaSecretaDoWifi123",
            "wireless_5g_ssid": "CasaLuiz_5G",
            "guest_2g_ssid": "CasaLuiz_Convidados",
            "guest_5g_ssid": "CasaLuiz_Convidados_5G",
            "lan_macaddr": "AA:BB:CC:DD:EE:FF",
            "lan_ipv4_ipaddr": "192.168.0.1",
            "wan_ipv4_ipaddr": "201.17.45.90",
            "access_devices_wired": [
              {"macaddr": "11:22:33:44:55:66", "ipaddr": "192.168.0.100", "hostname": "notebook-luiz"},
              {"macaddr": "AA:11:BB:22:CC:33", "ipaddr": "192.168.0.101", "hostname": "celular-luiz"}
            ]
          }
        }
    """.trimIndent()

    @Test
    fun `maps wireless SSID fields to radios with raw SSID`() {
        val snapshot = TpLinkStokLuciStatusParser.parseSnapshot(sampleBody())

        val main2g = snapshot.wifi.first { it.id == "main-2g" }
        assertEquals(TpLinkStokLuciWifiBand.GHZ_2_4, main2g.band)
        assertEquals(false, main2g.guestNetwork)
        assertEquals(6, main2g.channel)
        assertEquals("CasaLuiz_2G", main2g.ssid)

        val main5g = snapshot.wifi.first { it.id == "main-5g" }
        assertEquals(TpLinkStokLuciWifiBand.GHZ_5, main5g.band)
        assertEquals("CasaLuiz_5G", main5g.ssid)
    }

    @Test
    fun `maps guest SSID fields as distinct guest radios`() {
        val snapshot = TpLinkStokLuciStatusParser.parseSnapshot(sampleBody())

        val guest2g = snapshot.wifi.first { it.id == "guest-2g" }
        assertEquals(true, guest2g.guestNetwork)
        assertEquals(TpLinkStokLuciWifiBand.GHZ_2_4, guest2g.band)

        val guest5g = snapshot.wifi.first { it.id == "guest-5g" }
        assertEquals(true, guest5g.guestNetwork)
        assertEquals(TpLinkStokLuciWifiBand.GHZ_5, guest5g.band)
    }

    @Test
    fun `never exposes psk_key in any field of the parsed model`() {
        val snapshot = TpLinkStokLuciStatusParser.parseSnapshot(sampleBody())

        val serialized = snapshot.toString()
        assertTrue(!serialized.contains("senhaSecretaDoWifi123"))
    }

    @Test
    fun `maps lan status with raw MAC and raw LAN IPv4`() {
        val snapshot = TpLinkStokLuciStatusParser.parseSnapshot(sampleBody())

        assertEquals("AA:BB:CC:DD:EE:FF", snapshot.lan?.macAddress)
        assertEquals("192.168.0.1", snapshot.lan?.ipv4Address)
    }

    @Test
    fun `maps wan status with raw ipv4`() {
        val snapshot = TpLinkStokLuciStatusParser.parseSnapshot(sampleBody())

        assertEquals("201.17.45.90", snapshot.wan?.ipv4Address)
    }

    @Test
    fun `maps wired connected clients with raw MAC`() {
        val snapshot = TpLinkStokLuciStatusParser.parseSnapshot(sampleBody())

        assertEquals(2, snapshot.connectedClients.size)
        val first = snapshot.connectedClients[0]
        assertEquals("notebook-luiz", first.hostname)
        assertEquals("192.168.0.100", first.ipAddress)
        assertEquals("11:22:33:44:55:66", first.macAddress)
    }

    @Test
    fun `missing fields never throw and produce null or empty results`() {
        val snapshot = TpLinkStokLuciStatusParser.parseSnapshot("""{"success":true,"data":{}}""")

        assertTrue(snapshot.wifi.isEmpty())
        assertNull(snapshot.lan)
        assertNull(snapshot.wan)
        assertTrue(snapshot.connectedClients.isEmpty())
    }

    @Test
    fun `malformed JSON never throws`() {
        val snapshot = TpLinkStokLuciStatusParser.parseSnapshot("not a json at all")

        assertTrue(snapshot.wifi.isEmpty())
        assertNull(snapshot.lan)
        assertNull(snapshot.wan)
        assertTrue(snapshot.connectedClients.isEmpty())
    }

    @Test
    fun `maps current channel and tx power for main radios only, issue 33`() {
        val body = """
            {
              "success": true,
              "data": {
                "wireless_2g_ssid": "CasaLuiz_2G",
                "wireless_2g_current_channel": 10,
                "wireless_2g_txpower": "high",
                "wireless_5g_ssid": "CasaLuiz_5G",
                "wireless_5g_current_channel": 149,
                "wireless_5g_txpower": "middle",
                "guest_2g_ssid": "CasaLuiz_Convidados"
              }
            }
        """.trimIndent()

        val snapshot = TpLinkStokLuciStatusParser.parseSnapshot(body)

        val main2g = snapshot.wifi.first { it.id == "main-2g" }
        assertEquals(10, main2g.currentChannel)
        assertEquals(TpLinkStokLuciTxPower.HIGH, main2g.txPower)

        val main5g = snapshot.wifi.first { it.id == "main-5g" }
        assertEquals(149, main5g.currentChannel)
        assertEquals(TpLinkStokLuciTxPower.MIDDLE, main5g.txPower)

        val guest2g = snapshot.wifi.first { it.id == "guest-2g" }
        assertNull(guest2g.currentChannel)
        assertNull(guest2g.txPower)
    }

    @Test
    fun `unknown tx power text maps to UNKNOWN instead of throwing`() {
        val body = """{"data":{"wireless_2g_ssid":"x","wireless_2g_txpower":"turbo"}}"""

        val snapshot = TpLinkStokLuciStatusParser.parseSnapshot(body)

        assertEquals(TpLinkStokLuciTxPower.UNKNOWN, snapshot.wifi.first { it.id == "main-2g" }.txPower)
    }

    @Test
    fun `accepts flat body without the data envelope`() {
        val flatBody = """
            {
              "wireless_2g_ssid": "RedeSemEnvelope",
              "wan_ipv4_ipaddr": "10.0.0.5"
            }
        """.trimIndent()

        val snapshot = TpLinkStokLuciStatusParser.parseSnapshot(flatBody)

        assertEquals(1, snapshot.wifi.size)
        assertEquals("10.0.0.5", snapshot.wan?.ipv4Address)
    }
}
