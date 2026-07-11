package com.nethal.core.driver.family.tplink.stokluci

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testes de [TpLinkStokLuciDriverFamily] — orquestração (retry, guarda RFC 1918, classificação de
 * falha), com fake de transporte. Não substituem a validação ao vivo já registrada para login +
 * `readStatusRaw`; cobrem só a lógica determinística desta classe.
 */
class TpLinkStokLuciDriverFamilyTest {

    private fun realProfileConfig(): TpLinkStokLuciDriverConfig = TpLinkStokLuciDriverConfig(
        statusReadPath = "admin/status",
        statusReadQuery = "form=all&operation=read",
    )

    @Test
    fun `rejects public host at construction - never sends credentials outside the LAN`() {
        val transport = FakeTpLinkStokLuciHttpTransport()

        val exception = assertThrows(IllegalArgumentException::class.java) {
            TpLinkStokLuciDriverFamily("8.8.8.8", realProfileConfig(), transport)
        }
        assertTrue(exception.message!!.contains("8.8.8.8"))
    }

    @Test
    fun `accepts RFC1918 private host at construction`() {
        val transport = FakeTpLinkStokLuciHttpTransport()

        listOf("192.168.0.1", "10.0.0.1", "172.16.5.5").forEach { privateHost ->
            TpLinkStokLuciDriverFamily(privateHost, realProfileConfig(), transport) // não deve lançar
        }
    }

    @Test
    fun `login succeeds on first attempt against a well-formed fake response`() = runTest {
        val transport = FakeTpLinkStokLuciHttpTransport(
            simulateRealServerStok = "tokABC",
        )
        val driver = TpLinkStokLuciDriverFamily("192.168.0.1", realProfileConfig(), transport, backoffMillis = { 0L })

        val result = driver.login("admin", "secret")

        assertTrue(result is TpLinkStokLuciLoginOutcome.Success)
        assertEquals("tokABC", (result as TpLinkStokLuciLoginOutcome.Success).session.stok)
    }

    @Test
    fun `login fails fast on invalid credentials without exhausting retries`() = runTest {
        val transport = FakeTpLinkStokLuciHttpTransport(
            keysResponse = passwordKeySuccessResponse(),
            authResponse = authSuccessResponse(),
            loginResponse = loginFailureResponse(),
        )
        val driver = TpLinkStokLuciDriverFamily("192.168.0.1", realProfileConfig(), transport, maxAttempts = 2, backoffMillis = { 0L })

        val result = driver.login("admin", "wrong")

        assertTrue(result is TpLinkStokLuciLoginOutcome.Failure)
        assertEquals(TpLinkStokLuciFailureReason.INVALID_CREDENTIALS, (result as TpLinkStokLuciLoginOutcome.Failure).reason)
        // sem retry para credencial invalida: so uma rodada completa de keys+auth+login = 3 chamadas
        assertEquals(3, transport.postCallCount)
    }

    @Test
    fun `login respects conservative max attempts default of two on persistent network failure`() = runTest {
        val transport = FakeTpLinkStokLuciHttpTransport(keysResponse = null)
        var backoffCalls = 0
        val driver = TpLinkStokLuciDriverFamily(
            "192.168.0.1",
            realProfileConfig(),
            transport,
            backoffMillis = { backoffCalls++; 0L },
        )

        val result = driver.login("admin", "secret")

        assertTrue(result is TpLinkStokLuciLoginOutcome.Failure)
        assertEquals(1, backoffCalls) // só 1 backoff entre as 2 tentativas (default maxAttempts=2)
    }

    @Test
    fun `readStatusRaw returns the raw body after a successful login`() = runTest {
        val transport = FakeTpLinkStokLuciHttpTransport(
            simulateRealServerStok = "tokXYZ",
            statusResponse = com.nethal.core.protocol.http.HttpTransportResponse(200, """{"status":"ok"}""", emptyMap(), emptyMap()),
        )
        val driver = TpLinkStokLuciDriverFamily("192.168.0.1", realProfileConfig(), transport, backoffMillis = { 0L })

        val result = driver.readStatusRaw("admin", "secret")

        assertTrue(result is TpLinkStokLuciStatusOutcome.Success)
        assertEquals("""{"status":"ok"}""", (result as TpLinkStokLuciStatusOutcome.Success).rawBody)
    }

    @Test
    fun `readCapability without a prior authenticate() call always returns Unavailable`() = runTest {
        val transport = FakeTpLinkStokLuciHttpTransport()
        val driver = TpLinkStokLuciDriverFamily("192.168.0.1", realProfileConfig(), transport)

        val result = driver.readCapability(com.nethal.core.model.CapabilityId.READ_DEVICE_INFO)

        assertTrue(result is com.nethal.core.catalog.CapabilityReadResult.Unavailable)
    }

    @Test
    fun `readCapability distinguishes unsupported capability from supported-but-sessionless in the reason`() = runTest {
        val transport = FakeTpLinkStokLuciHttpTransport()
        val driver = TpLinkStokLuciDriverFamily("192.168.0.1", realProfileConfig(), transport)

        val unsupported = driver.readCapability(com.nethal.core.model.CapabilityId.READ_DEVICE_INFO)
            as com.nethal.core.catalog.CapabilityReadResult.Unavailable
        assertTrue(unsupported.reason.contains("não implementa parsing"))

        val supportedButSessionless = driver.readCapability(com.nethal.core.model.CapabilityId.READ_WIFI_STATUS)
            as com.nethal.core.catalog.CapabilityReadResult.Unavailable
        assertTrue(supportedButSessionless.reason.contains("authenticate"))
    }

    @Test
    fun `SUPPORTED_CAPABILITIES covers only capabilities with real structured parsing`() {
        assertEquals(
            setOf(
                com.nethal.core.model.CapabilityId.READ_WIFI_STATUS,
                com.nethal.core.model.CapabilityId.READ_LAN_STATUS,
                com.nethal.core.model.CapabilityId.READ_WAN_STATUS,
                com.nethal.core.model.CapabilityId.READ_CONNECTED_CLIENTS,
            ),
            TpLinkStokLuciDriverFamily.SUPPORTED_CAPABILITIES,
        )
    }

    @Test
    fun `readSnapshot parses the raw status body into structured capability data`() = runTest {
        val transport = FakeTpLinkStokLuciHttpTransport(
            simulateRealServerStok = "tokABC",
            statusResponse = com.nethal.core.protocol.http.HttpTransportResponse(
                200,
                """{"success":true,"data":{"wireless_2g_ssid":"CasaLuiz_2G","wan_ipv4_ipaddr":"201.17.45.90"}}""",
                emptyMap(),
                emptyMap(),
            ),
        )
        val driver = TpLinkStokLuciDriverFamily("192.168.0.1", realProfileConfig(), transport, backoffMillis = { 0L })

        val result = driver.readSnapshot("admin", "secret")

        assertTrue(result is TpLinkStokLuciSnapshotOutcome.Success)
        val snapshot = (result as TpLinkStokLuciSnapshotOutcome.Success).snapshot
        assertEquals(1, snapshot.wifi.size)
        assertEquals("CasaLuiz_2G", snapshot.wifi.first().ssid)
        assertEquals("201.17.45.90", snapshot.wan?.ipv4Address)
    }
}
