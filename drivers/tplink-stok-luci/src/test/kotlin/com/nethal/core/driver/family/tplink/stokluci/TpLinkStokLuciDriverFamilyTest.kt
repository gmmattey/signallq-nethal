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
        meshTopologyPath = "admin/onemesh_network",
        meshTopologyQuery = "form=mesh_topology&operation=read",
        dosSettingPath = "admin/security_settings",
        dosSettingQuery = "form=dos_setting&operation=read",
        diagPath = "admin/diag",
        diagQuery = "form=diag",
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
                com.nethal.core.model.CapabilityId.READ_WIFI_RADIOS,
                com.nethal.core.model.CapabilityId.READ_LAN_STATUS,
                com.nethal.core.model.CapabilityId.READ_WAN_STATUS,
                com.nethal.core.model.CapabilityId.READ_CONNECTED_CLIENTS,
                com.nethal.core.model.CapabilityId.READ_MESH_TOPOLOGY,
                com.nethal.core.model.CapabilityId.READ_DOS_PROTECTION_THRESHOLDS,
            ),
            TpLinkStokLuciDriverFamily.SUPPORTED_CAPABILITIES,
        )
    }

    @Test
    fun `readCapability routes READ_MESH_TOPOLOGY to the mesh topology endpoint, not the status endpoint`() = runTest {
        val transport = FakeTpLinkStokLuciHttpTransport(
            simulateRealServerStok = "tokABC",
            statusResponse = com.nethal.core.protocol.http.HttpTransportResponse(
                200,
                """{"success":true,"data":{"model":"Archer A6 v2","mesh_nclient_list":[]}}""",
                emptyMap(),
                emptyMap(),
            ),
        )
        val driver = TpLinkStokLuciDriverFamily("192.168.0.1", realProfileConfig(), transport, backoffMillis = { 0L })
        driver.authenticate("admin", "secret")

        val result = driver.readCapability(com.nethal.core.model.CapabilityId.READ_MESH_TOPOLOGY)

        assertTrue(result is com.nethal.core.catalog.CapabilityReadResult.Success)
        val payload = (result as com.nethal.core.catalog.CapabilityReadResult.Success).payload as com.nethal.core.model.CapabilityPayload.MeshTopology
        assertEquals("Archer A6 v2", payload.topology.routerModel)
        assertTrue(transport.postedUrls.last().contains("form=mesh_topology"))
    }

    @Test
    fun `readCapability routes READ_DOS_PROTECTION_THRESHOLDS to the security settings endpoint`() = runTest {
        val transport = FakeTpLinkStokLuciHttpTransport(
            simulateRealServerStok = "tokABC",
            statusResponse = com.nethal.core.protocol.http.HttpTransportResponse(
                200,
                """{"success":true,"data":{"icmp_low":100}}""",
                emptyMap(),
                emptyMap(),
            ),
        )
        val driver = TpLinkStokLuciDriverFamily("192.168.0.1", realProfileConfig(), transport, backoffMillis = { 0L })
        driver.authenticate("admin", "secret")

        val result = driver.readCapability(com.nethal.core.model.CapabilityId.READ_DOS_PROTECTION_THRESHOLDS)

        assertTrue(result is com.nethal.core.catalog.CapabilityReadResult.Success)
        val payload = (result as com.nethal.core.catalog.CapabilityReadResult.Success).payload as com.nethal.core.model.CapabilityPayload.DosProtectionThresholds
        assertEquals(100, payload.thresholds.icmp.low)
        assertTrue(transport.postedUrls.last().contains("form=dos_setting"))
    }

    @Test
    fun `readCapability READ_WIFI_RADIOS reuses the status endpoint and carries current channel plus tx power`() = runTest {
        val transport = FakeTpLinkStokLuciHttpTransport(
            simulateRealServerStok = "tokABC",
            statusResponse = com.nethal.core.protocol.http.HttpTransportResponse(
                200,
                """{"success":true,"data":{"wireless_2g_ssid":"CasaLuiz_2G","wireless_2g_current_channel":10,"wireless_2g_txpower":"high"}}""",
                emptyMap(),
                emptyMap(),
            ),
        )
        val driver = TpLinkStokLuciDriverFamily("192.168.0.1", realProfileConfig(), transport, backoffMillis = { 0L })
        driver.authenticate("admin", "secret")

        val result = driver.readCapability(com.nethal.core.model.CapabilityId.READ_WIFI_RADIOS)

        assertTrue(result is com.nethal.core.catalog.CapabilityReadResult.Success)
        val payload = (result as com.nethal.core.catalog.CapabilityReadResult.Success).payload as com.nethal.core.model.CapabilityPayload.Wifi
        val main2g = payload.status.radios.first { it.id == "main-2g" }
        assertEquals(10, main2g.currentChannel)
        assertEquals(com.nethal.core.model.WifiTxPower.HIGH, main2g.txPower)
        assertTrue(transport.postedUrls.last().contains("form=all"))
    }

    @Test
    fun `runNativeDiagnosticPing logs in, writes the diag request then reads back the parsed result`() = runTest {
        val resultText = "4 packets transmitted, 4 packets received, 0% packet loss\n" +
            "round-trip min/avg/max = 10.0/11.0/12.0 ms"
        val transport = FakeTpLinkStokLuciHttpTransport(
            simulateRealServerStok = "tokABC",
            statusResponse = com.nethal.core.protocol.http.HttpTransportResponse(
                200,
                """{"success":true,"data":{"result":${kotlinx.serialization.json.JsonPrimitive(resultText)}}}""",
                emptyMap(),
                emptyMap(),
            ),
        )
        val driver = TpLinkStokLuciDriverFamily("192.168.0.1", realProfileConfig(), transport, backoffMillis = { 0L })

        val outcome = driver.runNativeDiagnosticPing(
            "admin",
            "secret",
            com.nethal.core.model.NativeDiagnosticPingRequest(targetHost = "8.8.8.8", packetCount = 4),
        )

        assertTrue(outcome is TpLinkStokLuciPingOutcome.Success)
        val result = (outcome as TpLinkStokLuciPingOutcome.Success).result
        assertEquals(4, result.packetsSent)
        assertEquals(4, result.packetsReceived)
        assertEquals(0.0, result.packetLossPercent)

        // handshake (3) + write (1) + read (1) = 5.
        assertEquals(5, transport.postCallCount)
        assertEquals(2, transport.authenticatedRequestBodies.size)

        val aesKey = transport.lastCapturedAesKeyDigits!!.toByteArray(Charsets.US_ASCII)
        val aesIv = transport.lastCapturedAesIvDigits!!.toByteArray(Charsets.US_ASCII)
        val writePlaintext = decryptRequestBody(transport.authenticatedRequestBodies[0], aesKey, aesIv)
        assertTrue(writePlaintext.contains("operation=write"))
        assertTrue(writePlaintext.contains("ipaddr=8.8.8.8"))
        assertTrue(writePlaintext.contains("count=4"))

        val readPlaintext = decryptRequestBody(transport.authenticatedRequestBodies[1], aesKey, aesIv)
        assertEquals("operation=read", readPlaintext)
    }

    private fun decryptRequestBody(rawHttpBody: String, aesKey: ByteArray, aesIv: ByteArray): String {
        val dataBase64 = java.net.URLDecoder.decode(
            Regex("""data=([^&]+)""").find(rawHttpBody)!!.groupValues[1],
            "UTF-8",
        )
        val decrypted = TpLinkStokLuciCrypto.aesCbcDecrypt(aesKey, aesIv, TpLinkStokLuciCrypto.base64Decode(dataBase64))
        return String(decrypted, Charsets.UTF_8)
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
