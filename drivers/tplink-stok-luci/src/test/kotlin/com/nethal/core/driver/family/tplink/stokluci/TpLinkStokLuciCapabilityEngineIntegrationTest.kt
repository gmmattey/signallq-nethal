package com.nethal.core.driver.family.tplink.stokluci

import com.nethal.core.capability.CapabilityEngine
import com.nethal.core.catalog.CapabilityReadResult
import com.nethal.core.model.CapabilityId
import com.nethal.core.model.CapabilityPayload
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ponta a ponta real (issue #16): [CapabilityEngine] gerenciando sessão de verdade contra
 * [TpLinkStokLuciDriverFamily] com o mesmo protocolo/criptografia simulados por
 * [FakeTpLinkStokLuciHttpTransport] usados no resto da suíte desta Driver Family — sem mockar
 * `authenticate`/`readCapability`, diferente de `CapabilityEngineTest` (que testa só a política
 * genérica de sessão com uma `DriverFamily` fake).
 */
class TpLinkStokLuciCapabilityEngineIntegrationTest {

    private fun realProfileConfig(): TpLinkStokLuciDriverConfig = TpLinkStokLuciDriverConfig(
        statusReadPath = "admin/status",
        statusReadQuery = "form=all&operation=read",
    )

    private fun statusBody() = """{"success":true,"data":{"wireless_2g_ssid":"CasaLuiz_2G","wan_ipv4_ipaddr":"201.17.45.90","lan_macaddr":"AA:BB:CC:DD:EE:FF","lan_ipv4_ipaddr":"192.168.0.1"}}"""

    @Test
    fun `readCapability authenticates on first call and reuses the session on subsequent calls`() = runTest {
        val transport = FakeTpLinkStokLuciHttpTransport(
            simulateRealServerStok = "tokABC",
            statusResponse = com.nethal.core.protocol.http.HttpTransportResponse(200, statusBody(), emptyMap(), emptyMap()),
        )
        val driver = TpLinkStokLuciDriverFamily("192.168.0.1", realProfileConfig(), transport, backoffMillis = { 0L })
        val engine = CapabilityEngine(driver, username = "admin", password = "secret")

        assertEquals(0, transport.postCallCount)

        val first = engine.readCapability(CapabilityId.READ_WIFI_STATUS)
        assertTrue(first is CapabilityReadResult.Success)
        val wifiPayload = (first as CapabilityReadResult.Success).payload as CapabilityPayload.Wifi
        assertEquals("CasaLuiz_2G", wifiPayload.status.radios.first().ssid)

        // 3 chamadas do handshake (form=keys, form=auth, form=login) + 1 leitura autenticada.
        assertEquals(4, transport.postCallCount)

        val second = engine.readCapability(CapabilityId.READ_WAN_STATUS)
        assertTrue(second is CapabilityReadResult.Success)
        val wanPayload = (second as CapabilityReadResult.Success).payload as CapabilityPayload.Wan
        assertEquals("201.17.45.90", wanPayload.status.ipv4Address)

        // Segunda leitura não faz novo login - só mais uma chamada autenticada (total 5, não 8).
        assertEquals(5, transport.postCallCount)
    }

    @Test
    fun `readCapability before any authentication never touches the transport`() = runTest {
        val transport = FakeTpLinkStokLuciHttpTransport()
        val driver = TpLinkStokLuciDriverFamily("192.168.0.1", realProfileConfig(), transport)

        // Chamando readCapability diretamente na DriverFamily (sem authenticate() antes, sem
        // CapabilityEngine) precisa continuar honesto - nunca lança, nunca inventa dado.
        val result = driver.readCapability(CapabilityId.READ_WIFI_STATUS)

        assertTrue(result is CapabilityReadResult.Unavailable)
        assertEquals(0, transport.postCallCount)
    }

    @Test
    fun `session expiry mid-flow is renewed automatically by the CapabilityEngine, transparently to the caller`() = runTest {
        val transport = FakeTpLinkStokLuciHttpTransport(
            simulateRealServerStok = "tokABC",
            statusResponse = com.nethal.core.protocol.http.HttpTransportResponse(200, statusBody(), emptyMap(), emptyMap()),
            expireAuthenticatedReadsAfter = 1, // a 1a leitura autenticada de cada login funciona, a 2a expira (401)
        )
        val driver = TpLinkStokLuciDriverFamily("192.168.0.1", realProfileConfig(), transport, backoffMillis = { 0L })
        val engine = CapabilityEngine(driver, username = "admin", password = "secret")

        val first = engine.readCapability(CapabilityId.READ_WIFI_STATUS) // login + 1a leitura (ok)
        assertTrue(first is CapabilityReadResult.Success)

        val second = engine.readCapability(CapabilityId.READ_WAN_STATUS) // 2a leitura sob o mesmo login -> 401 -> renova -> 1a leitura do novo login (ok)
        assertTrue(second is CapabilityReadResult.Success)
        assertTrue(engine.isSessionActive)

        // 2 handshakes completos (3 chamadas cada) + 2 leituras que retornaram sucesso (a que expirou
        // consumiu uma chamada de rede também, mesmo devolvendo 401) = 3 + 1 + 1(expirada) + 3 + 1 = 9.
        assertEquals(9, transport.postCallCount)
    }

    @Test
    fun `a raw SessionExpired straight from the DriverFamily is distinguishable from a generic Failure`() = runTest {
        val transport = FakeTpLinkStokLuciHttpTransport(
            simulateRealServerStok = "tokABC",
            statusResponse = com.nethal.core.protocol.http.HttpTransportResponse(200, statusBody(), emptyMap(), emptyMap()),
            expireAuthenticatedReadsAfter = 0, // já expira na 1a leitura autenticada
        )
        val driver = TpLinkStokLuciDriverFamily("192.168.0.1", realProfileConfig(), transport, backoffMillis = { 0L })

        val authResult = driver.authenticate("admin", "secret")
        assertTrue(authResult is com.nethal.core.catalog.DriverFamilyAuthResult.Success)

        val readResult = driver.readCapability(CapabilityId.READ_WIFI_STATUS)
        assertTrue(readResult is CapabilityReadResult.SessionExpired)
    }

    @Test
    fun `credentials are never present in any failure reason produced along the authenticated read path`() = runTest {
        val secretPassword = "Sup3rS3nhaDoRoteador"
        val transport = FakeTpLinkStokLuciHttpTransport(keysResponse = null, authResponse = null, loginResponse = null)
        val driver = TpLinkStokLuciDriverFamily("192.168.0.1", realProfileConfig(), transport, backoffMillis = { 0L })
        val engine = CapabilityEngine(driver, username = "admin", password = secretPassword)

        val result = engine.readCapability(CapabilityId.READ_WIFI_STATUS)

        assertTrue(result is CapabilityReadResult.Unavailable)
        assertFalse((result as CapabilityReadResult.Unavailable).reason.contains(secretPassword))
        assertFalse(engine.toString().contains(secretPassword))
    }
}
