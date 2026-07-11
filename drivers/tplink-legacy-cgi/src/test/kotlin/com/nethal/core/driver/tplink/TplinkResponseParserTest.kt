package com.nethal.core.driver.tplink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TplinkResponseParserTest {

    @Test
    fun `parseDeviceInfo extracts known fields`() {
        val json = """{"model":"Archer C6","hardwareVersion":"V4","firmwareVersion":"1.2.3","uptime":7200}"""

        val info = TplinkResponseParser.parseDeviceInfo(json)

        assertEquals("Archer C6", info?.model)
        assertEquals("V4", info?.hardwareVersion)
        assertEquals("1.2.3", info?.firmwareVersion)
        assertEquals(7200L, info?.uptimeSeconds)
    }

    @Test
    fun `parseDeviceInfo returns null when model field is absent - defensive, not exception`() {
        val json = """{"hardwareVersion":"V4"}"""

        assertNull(TplinkResponseParser.parseDeviceInfo(json))
    }

    @Test
    fun `parseWanStatus extracts connection fields and status`() {
        val json = """{"connectionType":"PPPoE","externalIp":"203.0.113.10","gateway":"203.0.113.1","primaryDns":"8.8.8.8","secondaryDns":"8.8.4.4","connectionStatus":"Connected"}"""

        val wan = TplinkResponseParser.parseWanStatus(json)

        assertEquals("PPPoE", wan?.connectionType)
        assertEquals("203.0.113.10", wan?.externalIp)
        assertTrue(wan?.isConnected == true)
    }

    @Test
    fun `parseWifiStatus extracts both bands from array`() {
        val json = """[{"band":"2.4GHz","enabled":"true","ssid":"Rede","channel":"6"},{"band":"5GHz","enabled":"false","ssid":"Rede5G","channel":"36"}]"""

        val bands = TplinkResponseParser.parseWifiStatus(json)

        assertEquals(2, bands.size)
        assertEquals("2.4GHz", bands[0].band)
        assertTrue(bands[0].enabled)
        assertEquals("5GHz", bands[1].band)
        assertTrue(!bands[1].enabled)
    }

    @Test
    fun `parseConnectedClients masks MAC address to OUI only`() {
        val json = """[{"hostname":"celular","ip":"192.168.0.20","mac":"11:22:33:AA:BB:CC","type":"wifi"}]"""

        val clients = TplinkResponseParser.parseConnectedClients(json)

        assertEquals(1, clients.size)
        assertEquals("192.168.0.20", clients.first().ipAddress)
        assertEquals("11:22:33:**:**:**", clients.first().macAddressMasked)
    }

    @Test
    fun `parseConnectedClients never leaks full MAC even with malformed input`() {
        val json = """[{"hostname":"x","ip":"192.168.0.30","mac":"not-a-real-mac","type":"lan"}]"""

        val clients = TplinkResponseParser.parseConnectedClients(json)

        assertEquals("**:**:**:**:**:**", clients.first().macAddressMasked)
    }
}
