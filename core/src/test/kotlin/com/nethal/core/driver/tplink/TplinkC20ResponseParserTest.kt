package com.nethal.core.driver.tplink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TplinkC20ResponseParserTest {

    @Test
    fun `parseDeviceInfo extracts known fields`() {
        val json = """{"model":"Archer C20","hardwareVersion":"V4","firmwareVersion":"0.9.1","uptime":3600}"""

        val info = TplinkC20ResponseParser.parseDeviceInfo(json)

        assertEquals("Archer C20", info?.model)
        assertEquals("V4", info?.hardwareVersion)
        assertEquals("0.9.1", info?.firmwareVersion)
        assertEquals(3600L, info?.uptimeSeconds)
    }

    @Test
    fun `parseDeviceInfo returns null when model field is absent - defensive, not exception`() {
        val json = """{"hardwareVersion":"V4"}"""

        assertNull(TplinkC20ResponseParser.parseDeviceInfo(json))
    }

    @Test
    fun `parseWanStatus extracts connection fields and status`() {
        val json = """{"connectionType":"PPPoE","externalIp":"203.0.113.20","gateway":"203.0.113.1","primaryDns":"8.8.8.8","secondaryDns":"8.8.4.4","connectionStatus":"Connected"}"""

        val wan = TplinkC20ResponseParser.parseWanStatus(json)

        assertEquals("PPPoE", wan?.connectionType)
        assertEquals("203.0.113.20", wan?.externalIp)
        assertTrue(wan?.isConnected == true)
    }

    @Test
    fun `parseWifiStatus extracts single band from array`() {
        val json = """[{"band":"2.4GHz","enabled":"true","ssid":"RedeC20","channel":"6"}]"""

        val bands = TplinkC20ResponseParser.parseWifiStatus(json)

        assertEquals(1, bands.size)
        assertEquals("2.4GHz", bands[0].band)
        assertTrue(bands[0].enabled)
    }

    @Test
    fun `parseConnectedClients masks MAC address to OUI only`() {
        val json = """[{"hostname":"celular","ip":"192.168.0.20","mac":"11:22:33:AA:BB:CC","type":"wifi"}]"""

        val clients = TplinkC20ResponseParser.parseConnectedClients(json)

        assertEquals(1, clients.size)
        assertEquals("192.168.0.20", clients.first().ipAddress)
        assertEquals("11:22:33:**:**:**", clients.first().macAddressMasked)
    }

    @Test
    fun `parseConnectedClients never leaks full MAC even with malformed input`() {
        val json = """[{"hostname":"x","ip":"192.168.0.30","mac":"not-a-real-mac","type":"lan"}]"""

        val clients = TplinkC20ResponseParser.parseConnectedClients(json)

        assertEquals("**:**:**:**:**:**", clients.first().macAddressMasked)
    }
}
