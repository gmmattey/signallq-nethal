package com.nethal.core.driver.tplink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testes do codec do protocolo real do dispatcher `/cgi` do Archer C20 — fixtures são as respostas
 * reais capturadas via DevTools contra unidade física do Luiz (2026-07-06, ver SIG-337/SIG-338).
 */
class TplinkC20ResponseParserTest {

    @Test
    fun `buildRequestBody monta blocos de secao com indice e conta de campos, sem bloco de sessao automatico`() {
        val body = TplinkC20ResponseParser.buildRequestBody(
            listOf(
                "IGD_DEV_INFO" to listOf("modelName", "description", "X_TP_isFD"),
                "ETH_SWITCH" to listOf("numberOfVirtualPorts"),
                "SYS_MODE" to listOf("mode"),
            ),
        )

        assertTrue(body.contains("[IGD_DEV_INFO#0,0,0,0,0,0#0,0,0,0,0,0]0,3"))
        assertTrue(body.contains("[ETH_SWITCH#0,0,0,0,0,0#0,0,0,0,0,0]1,1"))
        assertTrue(body.contains("[SYS_MODE#0,0,0,0,0,0#0,0,0,0,0,0]2,1"))
        assertFalse("buildRequestBody nao deve inventar bloco de sessao sem pedido explicito", body.contains("/cgi/info"))
        assertTrue(body.contains("modelName"))
        assertTrue(body.contains("numberOfVirtualPorts"))
        assertTrue(body.contains("mode"))
    }

    @Test
    fun `buildRequestBody inclui bloco de sessao cgi-info so quando explicitamente pedido, replicando o bundle real comprovado`() {
        val body = TplinkC20ResponseParser.buildRequestBody(
            listOf(
                "IGD_DEV_INFO" to listOf("modelName"),
                "/cgi/info" to emptyList(),
            ),
        )

        assertTrue(body.contains("[IGD_DEV_INFO#0,0,0,0,0,0#0,0,0,0,0,0]0,1"))
        assertTrue(body.contains("[/cgi/info#0,0,0,0,0,0#0,0,0,0,0,0]1,0"))
    }

    @Test
    fun `extractGlobalErrorCode reads error code zero as success marker`() {
        assertEquals(0, TplinkC20ResponseParser.extractGlobalErrorCode("[1,1,0,0,0,0]0\nmodelName=Archer C20\n[error]0"))
        assertTrue(TplinkC20ResponseParser.isSuccess("[error]0"))
    }

    @Test
    fun `extractGlobalErrorCode reads non-zero code as failure`() {
        assertEquals(1, TplinkC20ResponseParser.extractGlobalErrorCode("[error]1"))
        assertFalse(TplinkC20ResponseParser.isSuccess("[error]1"))
    }

    @Test
    fun `extractGlobalErrorCode returns null when marker is absent`() {
        assertNull(TplinkC20ResponseParser.extractGlobalErrorCode("resposta sem marcador reconhecido"))
        assertFalse(TplinkC20ResponseParser.isSuccess(""))
    }

    @Test
    fun `parseDeviceInfo extracts fields from real captured bundle response`() {
        val body = deviceInfoBundleResponse().body

        val info = TplinkC20ResponseParser.parseDeviceInfo(body, deviceInfoIndex = 0, ethSwitchIndex = 1, sysModeIndex = 2)

        assertEquals("Archer C20", info?.modelName)
        assertEquals("Roteador Wireless Dual Band AC750", info?.description)
        assertEquals(true, info?.isFactoryDefault)
        assertEquals(4, info?.numberOfVirtualPorts)
        assertEquals("ETH", info?.mode)
    }

    @Test
    fun `parseDeviceInfo returns null when modelName field is absent - defensive, not exception`() {
        val body = "[1,1,0,0,0,0]0\ndescription=sem modelo\n[error]0"

        assertNull(TplinkC20ResponseParser.parseDeviceInfo(body, deviceInfoIndex = 0, ethSwitchIndex = 1, sysModeIndex = 2))
    }

    @Test
    fun `parseWifiStatus extracts multiple radio lines sharing the same block index`() {
        val body = lanWlanResponse().body

        val radios = TplinkC20ResponseParser.parseWifiStatus(body, lanWlanIndex = 0)

        assertEquals(2, radios.size)
        assertEquals("wlan0", radios[0].name)
        assertEquals("Casa-2.4G", radios[0].ssid)
        assertEquals("wlan5", radios[1].name)
        assertEquals("Casa-5G", radios[1].ssid)
    }

    @Test
    fun `parseConnectedClients extracts fields and masks MAC address to OUI only`() {
        val body = lanHostEntryResponse().body

        val clients = TplinkC20ResponseParser.parseConnectedClients(body, lanHostEntryIndex = 0)

        assertEquals(1, clients.size)
        val client = clients.first()
        assertEquals("Notebook-Teste", client.hostname)
        assertEquals("192.168.0.100", client.ipAddress)
        assertEquals("AA:BB:CC:**:**:**", client.macAddressMasked)
        assertEquals(6231L, client.leaseTimeRemainingSeconds)
    }

    @Test
    fun `parseConnectedClients never leaks full MAC even with malformed input`() {
        val body = "[1,0,0,0,0,0]0\nMACAddress=not-a-real-mac\nhostName=x\nIPAddress=192.168.0.30\n[error]0"

        val clients = TplinkC20ResponseParser.parseConnectedClients(body, lanHostEntryIndex = 0)

        assertEquals("**:**:**:**:**:**", clients.first().macAddressMasked)
    }

    @Test
    fun `parseConnectedClients returns empty list when IPAddress field is absent - defensive, not exception`() {
        val body = "[1,0,0,0,0,0]0\nMACAddress=AA:BB:CC:DD:EE:FF\nhostName=x\n[error]0"

        assertEquals(emptyList<TplinkC20ConnectedClient>(), TplinkC20ResponseParser.parseConnectedClients(body, lanHostEntryIndex = 0))
    }

    @Test
    fun `session block cgi is never confused with a data section`() {
        val body = deviceInfoOnlyResponse().body

        val info = TplinkC20ResponseParser.parseDeviceInfo(body, deviceInfoIndex = 0, ethSwitchIndex = 99, sysModeIndex = 98)

        assertEquals("Archer C20", info?.modelName)
        // índice 1 é o bloco de sessão [cgi]1 no fixture — não deve ser interpretado como linha de dados válida
        assertEquals(emptyList<Map<String, String>>(), TplinkC20ResponseParser.linesForIndex(body, 1))
    }
}
