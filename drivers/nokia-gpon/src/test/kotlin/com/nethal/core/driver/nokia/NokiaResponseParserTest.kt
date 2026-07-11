package com.nethal.core.driver.nokia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NokiaResponseParserTest {

    @Test
    fun `parses gpon status with standard field names`() {
        val html = """
            <script>
            var GponConnectionStat = 1;
            var GponMode = "GPON";
            var RXPower = "251189";
            var TXPower = "158489";
            var TransceiverTemperature = 12800;
            var SerialNumber = "ALCLXXXXXXXX";
            var SupplyVoltage = 32000;
            var LaserCurrent = 9000;
            </script>
        """.trimIndent()

        val status = NokiaResponseParser.parseGponStatus(html)

        requireNotNull(status)
        assertTrue(status.isUp)
        assertEquals("GPON", status.connectionMode)
        assertEquals("ALCLXXXXXXXX", status.serialNumber)
        assertEquals(50.0, status.transceiverTemperatureCelsius, 0.001) // 12800 / 256
        assertEquals(3.2, status.supplyVoltageVolts, 0.001) // 32000 / 10000
        assertEquals(18.0, status.laserCurrentMilliAmps, 0.001) // 9000 / 500
    }

    @Test
    fun `parses gpon status accepting the known firmware typo SupplyVottage`() {
        val html = """
            var GponConnectionStat = 1;
            var RXPower = "251189";
            var TXPower = "158489";
            var SupplyVottage = 32500;
        """.trimIndent()

        val status = NokiaResponseParser.parseGponStatus(html)

        requireNotNull(status)
        assertEquals(3.25, status.supplyVoltageVolts, 0.001)
    }

    @Test
    fun `gpon down status is reflected in isUp`() {
        val html = "var GponConnectionStat = 0;"

        val status = NokiaResponseParser.parseGponStatus(html)

        requireNotNull(status)
        assertTrue(!status.isUp)
    }

    @Test
    fun `parses wan status from wan_conns block`() {
        val html = """
            var wan_conns = {
                ConnectionStatus:'Connected',
                ExternalIPAddress:'203.0.113.10',
                RemoteIPAddress:'198.51.100.1',
                DNSServers:'8.8.8.8,8.8.4.4',
                VLANIDMark: 100,
                X_ASB_COM_IfName:'ppp0',
                PPPoEACName:'concentrator-1',
                Uptime:86400,
                ConnectionType:'IP_Routed'
            };
        """.trimIndent()

        val status = NokiaResponseParser.parseWanStatus(html)

        requireNotNull(status)
        assertEquals("203.0.113.10", status.externalIp)
        assertEquals("198.51.100.1", status.gateway)
        assertEquals("8.8.8.8", status.primaryDns)
        assertEquals("8.8.4.4", status.secondaryDns)
        assertEquals("100", status.vlanId)
        assertEquals("ppp0", status.interfaceName)
        assertEquals("concentrator-1", status.pppoeConcentratorName)
        assertEquals("IP_Routed", status.connectionType)
        assertEquals(86400L, status.connectionUptimeSeconds)
    }

    @Test
    fun `wan status returns null when connection block is absent`() {
        assertNull(NokiaResponseParser.parseWanStatus("<html>no wan data here</html>"))
    }

    @Test
    fun `parses ppp status json`() {
        val json = """{"ppp_status":[{"ConnectionStatus":"Connected","ConnectionType":"PPPoE","Name":"session1","LastConnectionError":"ERROR_NONE"}]}"""

        val status = NokiaResponseParser.parsePppStatus(json)

        requireNotNull(status)
        assertTrue(status.isConnected)
        assertEquals("PPPoE", status.connectionType)
        assertEquals("session1", status.sessionName)
        assertEquals("ERROR_NONE", status.lastConnectionError)
    }

    @Test
    fun `ppp status returns null for empty array`() {
        assertNull(NokiaResponseParser.parsePppStatus("""{"ppp_status":[]}"""))
    }

    @Test
    fun `parses device info json`() {
        val json = """
            {"ModelName":"G-1425G-B","Manufacturer":"Nokia","SerialNumber":"ALCLXXXXXXXX",
             "SoftwareVersion":"3FE12345XYZ","HardwareVersion":"1.0","UpTime":172800}
        """.trimIndent()

        val info = NokiaResponseParser.parseDeviceInfo(json)

        requireNotNull(info)
        assertEquals("G-1425G-B", info.model)
        assertEquals("Nokia", info.manufacturer)
        assertEquals("3FE12345XYZ", info.softwareVersion)
        assertEquals(172800L, info.uptimeSeconds)
    }

    @Test
    fun `device info returns null when model and serial are both absent`() {
        assertNull(NokiaResponseParser.parseDeviceInfo("""{"SoftwareVersion":"x"}"""))
    }

    @Test
    fun `parses connected clients from home networking table`() {
        val clients = NokiaResponseParser.parseConnectedClients(sampleHomeNetworkingHtml())

        assertEquals(2, clients.size)
        assertEquals("Active", clients[0].status)
        assertEquals("Ethernet", clients[0].connectionType)
        assertEquals("Notebook da TIM", clients[0].deviceName)
        assertEquals("192.168.1.71", clients[0].ipAddress)
        assertEquals("08:B4:D2:**:**:**", clients[0].macAddressMasked.uppercase())
        assertEquals("DHCP", clients[0].allocation)
        assertEquals("15 hours 8 min 38 sec", clients[0].leaseRemaining)
    }

    @Test
    fun `connected clients returns empty list when table is absent`() {
        assertTrue(NokiaResponseParser.parseConnectedClients("<html>sem clientes</html>").isEmpty())
    }

    // --- Conversões de unidade isoladas ---

    @Test
    fun `converts raw milliwatt token to dbm using firmware formula`() {
        // floor(log10(251189 * 0.00001) * 1000) / 100 = floor(log10(2.51189) * 1000) / 100 ≈ -0.99 -> arredonda a formula real
        val dbm = NokiaResponseParser.convertOpticalPowerToDbm("251189", minDbm = -80.0, maxDbm = 0.0)
        assertTrue(dbm in -1.0..0.0)
    }

    @Test
    fun `optical power outside plausible range returns sentinel zero`() {
        assertEquals(0.0, NokiaResponseParser.convertOpticalPowerToDbm("999999999", minDbm = -80.0, maxDbm = 0.0), 0.0)
        assertEquals(0.0, NokiaResponseParser.convertOpticalPowerToDbm(null, minDbm = -80.0, maxDbm = 0.0), 0.0)
        assertEquals(0.0, NokiaResponseParser.convertOpticalPowerToDbm("0", minDbm = -80.0, maxDbm = 0.0), 0.0)
    }

    @Test
    fun `normalizes rx power reported without negative sign`() {
        assertEquals(-22.7, NokiaResponseParser.normalizeRxSign(22.7), 0.001)
        assertEquals(-22.7, NokiaResponseParser.normalizeRxSign(-22.7), 0.001)
        assertEquals(0.0, NokiaResponseParser.normalizeRxSign(0.0), 0.001)
        // fora do range plausivel de perda optica (1..60), nao inverte
        assertEquals(70.0, NokiaResponseParser.normalizeRxSign(70.0), 0.001)
    }
}
