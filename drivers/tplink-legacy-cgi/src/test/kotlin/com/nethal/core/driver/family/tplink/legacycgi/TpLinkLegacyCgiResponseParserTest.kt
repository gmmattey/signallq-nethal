package com.nethal.core.driver.family.tplink.legacycgi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testes do codec do protocolo real do dispatcher `/cgi` da plataforma `tplink-legacy-cgi` —
 * fixtures são as respostas reais capturadas via DevTools contra unidade física do Luiz
 * (2026-07-06, ver SIG-337/SIG-338).
 *
 * Movido de `driver/tplink/TplinkC20ResponseParserTest.kt` no passo 4 do plano de refatoração HAL
 * (`docs/architecture/hal-layering-model.md` §10) — mesma cobertura, sem mudança de comportamento.
 */
class TpLinkLegacyCgiResponseParserTest {

    @Test
    fun `buildRequestBody monta blocos de secao com indice e conta de campos, sem bloco de sessao automatico`() {
        val body = TpLinkLegacyCgiResponseParser.buildRequestBody(
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
        val body = TpLinkLegacyCgiResponseParser.buildRequestBody(
            listOf(
                "IGD_DEV_INFO" to listOf("modelName"),
                "/cgi/info" to emptyList(),
            ),
        )

        assertTrue(body.contains("[IGD_DEV_INFO#0,0,0,0,0,0#0,0,0,0,0,0]0,1"))
        assertTrue(body.contains("[/cgi/info#0,0,0,0,0,0#0,0,0,0,0,0]1,0"))
    }

    @Test
    fun `buildRequestBody terminates every line with CRLF, never bare LF - confirmado por HAR real`() {
        val body = TpLinkLegacyCgiResponseParser.buildRequestBody(
            listOf("IGD_DEV_INFO" to listOf("modelName", "description")),
        )

        assertTrue("corpo deve conter CRLF", body.contains("\r\n"))
        assertEquals(
            "nao deve haver LF sem CR precedente (todo \\n deve vir depois de \\r)",
            0,
            body.replace("\r\n", "").count { it == '\n' },
        )
    }

    @Test
    fun `extractGlobalErrorCode reads error code zero as success marker`() {
        assertEquals(0, TpLinkLegacyCgiResponseParser.extractGlobalErrorCode("[1,1,0,0,0,0]0\nmodelName=Archer C20\n[error]0"))
        assertTrue(TpLinkLegacyCgiResponseParser.isSuccess("[error]0"))
    }

    @Test
    fun `extractGlobalErrorCode reads non-zero code as failure`() {
        assertEquals(1, TpLinkLegacyCgiResponseParser.extractGlobalErrorCode("[error]1"))
        assertFalse(TpLinkLegacyCgiResponseParser.isSuccess("[error]1"))
    }

    @Test
    fun `extractGlobalErrorCode returns null when marker is absent`() {
        assertNull(TpLinkLegacyCgiResponseParser.extractGlobalErrorCode("resposta sem marcador reconhecido"))
        assertFalse(TpLinkLegacyCgiResponseParser.isSuccess(""))
    }

    @Test
    fun `parseDeviceInfo extracts fields from real captured bundle response`() {
        val body = deviceInfoBundleResponse().body

        val info = TpLinkLegacyCgiResponseParser.parseDeviceInfo(body, deviceInfoIndex = 0, ethSwitchIndex = 1, sysModeIndex = 2)

        assertEquals("Archer C20", info?.modelName)
        assertEquals("Roteador Wireless Dual Band AC750", info?.description)
        assertEquals(true, info?.isFactoryDefault)
        assertEquals(4, info?.numberOfVirtualPorts)
        assertEquals("ETH", info?.mode)
    }

    @Test
    fun `parseDeviceInfo returns null when modelName field is absent - defensive, not exception`() {
        val body = "[1,1,0,0,0,0]0\ndescription=sem modelo\n[error]0"

        assertNull(TpLinkLegacyCgiResponseParser.parseDeviceInfo(body, deviceInfoIndex = 0, ethSwitchIndex = 1, sysModeIndex = 2))
    }

    @Test
    fun `parseWifiStatus extracts multiple radio lines sharing the same block index`() {
        val body = lanWlanResponse().body

        val radios = TpLinkLegacyCgiResponseParser.parseWifiStatus(body, lanWlanIndex = 0)

        assertEquals(2, radios.size)
        assertEquals("wlan0", radios[0].name)
        assertEquals("Casa-2.4G", radios[0].ssid)
        assertEquals("wlan5", radios[1].name)
        assertEquals("Casa-5G", radios[1].ssid)
    }

    @Test
    fun `parseConnectedClients extracts fields including the raw MAC address`() {
        val body = lanHostEntryResponse().body

        val clients = TpLinkLegacyCgiResponseParser.parseConnectedClients(body, lanHostEntryIndex = 0)

        assertEquals(1, clients.size)
        val client = clients.first()
        assertEquals("Notebook-Teste", client.hostname)
        assertEquals("192.168.0.100", client.ipAddress)
        assertEquals("AA:BB:CC:DD:EE:FF", client.macAddress)
        assertEquals(6231L, client.leaseTimeRemainingSeconds)
    }

    @Test
    fun `parseConnectedClients is defensive with malformed MAC input, never throws`() {
        val body = "[1,0,0,0,0,0]0\nMACAddress=not-a-real-mac\nhostName=x\nIPAddress=192.168.0.30\n[error]0"

        val clients = TpLinkLegacyCgiResponseParser.parseConnectedClients(body, lanHostEntryIndex = 0)

        assertEquals("not-a-real-mac", clients.first().macAddress)
    }

    @Test
    fun `parseConnectedClients returns empty list when IPAddress field is absent - defensive, not exception`() {
        val body = "[1,0,0,0,0,0]0\nMACAddress=AA:BB:CC:DD:EE:FF\nhostName=x\n[error]0"

        assertEquals(emptyList<TpLinkLegacyCgiConnectedClient>(), TpLinkLegacyCgiResponseParser.parseConnectedClients(body, lanHostEntryIndex = 0))
    }

    @Test
    fun `session block cgi is never confused with a data section`() {
        val body = deviceInfoOnlyResponse().body

        val info = TpLinkLegacyCgiResponseParser.parseDeviceInfo(body, deviceInfoIndex = 0, ethSwitchIndex = 99, sysModeIndex = 98)

        assertEquals("Archer C20", info?.modelName)
        // índice 1 é o bloco de sessão [cgi]1 no fixture — não deve ser interpretado como linha de dados válida
        assertEquals(emptyList<Map<String, String>>(), TpLinkLegacyCgiResponseParser.linesForIndex(body, 1))
    }
}
