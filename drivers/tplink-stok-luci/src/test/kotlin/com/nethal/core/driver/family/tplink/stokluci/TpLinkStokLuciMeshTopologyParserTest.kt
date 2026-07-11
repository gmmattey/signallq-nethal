package com.nethal.core.driver.family.tplink.stokluci

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Testes de [TpLinkStokLuciMeshTopologyParser] — puros, sem rede, cobrindo `admin/onemesh_network?form=mesh_topology`. */
class TpLinkStokLuciMeshTopologyParserTest {

    private fun sampleBody(): String = """
        {
          "success": true,
          "data": {
            "model": "Archer A6 v2",
            "name": "ArcherA6v2",
            "mac": "AA:BB:CC:DD:EE:FF",
            "mesh_nclient_list": [
              {"mac": "11:22:33:44:55:66", "hostname": "notebook-luiz", "ip": "192.168.0.100", "wire_type": "wire", "guest": false, "access_time": 1720000000}
            ],
            "mesh_sclient_list": []
          }
        }
    """.trimIndent()

    @Test
    fun `maps router identity fields`() {
        val topology = TpLinkStokLuciMeshTopologyParser.parse(sampleBody())

        assertEquals("Archer A6 v2", topology.routerModel)
        assertEquals("ArcherA6v2", topology.routerName)
        assertEquals("AA:BB:CC:DD:EE:FF", topology.routerMacAddress)
    }

    @Test
    fun `maps mesh client list with raw MAC`() {
        val topology = TpLinkStokLuciMeshTopologyParser.parse(sampleBody())

        assertEquals(1, topology.clients.size)
        val client = topology.clients.first()
        assertEquals("11:22:33:44:55:66", client.macAddress)
        assertEquals("notebook-luiz", client.hostname)
        assertEquals("192.168.0.100", client.ipAddress)
        assertEquals("wire", client.wireType)
        assertEquals(false, client.guestNetwork)
        assertEquals(1720000000L, client.accessTimeEpochSeconds)
    }

    @Test
    fun `empty satellite list produces zero count, no equipment with paired OneMesh extender in evidence yet`() {
        val topology = TpLinkStokLuciMeshTopologyParser.parse(sampleBody())

        assertEquals(0, topology.satelliteNodeCount)
    }

    @Test
    fun `missing fields never throw and produce null or empty results`() {
        val topology = TpLinkStokLuciMeshTopologyParser.parse("""{"success":true,"data":{}}""")

        assertNull(topology.routerModel)
        assertNull(topology.routerName)
        assertNull(topology.routerMacAddress)
        assertTrue(topology.clients.isEmpty())
        assertEquals(0, topology.satelliteNodeCount)
    }

    @Test
    fun `malformed JSON never throws`() {
        val topology = TpLinkStokLuciMeshTopologyParser.parse("not a json at all")

        assertTrue(topology.clients.isEmpty())
        assertEquals(0, topology.satelliteNodeCount)
    }

    @Test
    fun `accepts flat body without the data envelope`() {
        val flatBody = """{"model":"Archer A6 v2","mesh_nclient_list":[{"mac":"AA:11:BB:22:CC:33"}]}"""

        val topology = TpLinkStokLuciMeshTopologyParser.parse(flatBody)

        assertEquals("Archer A6 v2", topology.routerModel)
        assertEquals(1, topology.clients.size)
    }

    @Test
    fun `accepts guest as decimal string fallback in addition to real JSON boolean`() {
        val body = """{"data":{"mesh_nclient_list":[{"mac":"11:22:33:44:55:66","guest":"1"}]}}"""

        val topology = TpLinkStokLuciMeshTopologyParser.parse(body)

        assertEquals(true, topology.clients.first().guestNetwork)
    }
}
