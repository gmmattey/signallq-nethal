package com.nethal.core.discovery

import com.nethal.core.model.TargetRole
import com.nethal.core.model.TargetSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultDiscoveryEngineTest {

    @Test
    fun `returns empty candidate list when there is no wifi gateway`() = runTest {
        val engine = DefaultDiscoveryEngine(
            networkEnvironmentReader = FakeNetworkEnvironmentReader(environment = null),
            ssdpDiscoverer = FakeSsdpDiscoverer(emptyList()),
            upnpIgdProbe = NeverRespondingUpnpIgdProbe(),
        )

        val result = engine.discover()

        assertTrue(result.devices.isEmpty())
        assertFalse(result.possibleDoubleNat)
    }

    @Test
    fun `returns empty candidate list when connected network is not wifi`() = runTest {
        val engine = DefaultDiscoveryEngine(
            networkEnvironmentReader = FakeNetworkEnvironmentReader(
                environment = NetworkEnvironment(
                    localIp = "192.168.1.50",
                    gatewayIp = "192.168.1.1",
                    subnetPrefixLength = 24,
                    dnsServers = listOf("8.8.8.8"),
                    isWifi = false,
                ),
            ),
            ssdpDiscoverer = FakeSsdpDiscoverer(emptyList()),
            upnpIgdProbe = NeverRespondingUpnpIgdProbe(),
        )

        val result = engine.discover()

        assertTrue(result.devices.isEmpty())
    }

    @Test
    fun `single gateway with no ssdp responses yields one primary gateway candidate`() = runTest {
        val engine = DefaultDiscoveryEngine(
            networkEnvironmentReader = FakeNetworkEnvironmentReader(
                environment = wifiEnvironment(gatewayIp = "192.168.1.1"),
            ),
            ssdpDiscoverer = FakeSsdpDiscoverer(emptyList()),
            upnpIgdProbe = NeverRespondingUpnpIgdProbe(),
        )

        val result = engine.discover()

        assertEquals(1, result.devices.size)
        assertEquals("192.168.1.1", result.devices.first().ip)
        assertEquals(TargetRole.PRIMARY_GATEWAY, result.devices.first().role)
        assertEquals(TargetSource.GATEWAY, result.devices.first().source)
        assertFalse(result.possibleDoubleNat)
    }

    @Test
    fun `ssdp responses from other hosts become mesh node candidates`() = runTest {
        val engine = DefaultDiscoveryEngine(
            networkEnvironmentReader = FakeNetworkEnvironmentReader(
                environment = wifiEnvironment(gatewayIp = "192.168.1.1"),
            ),
            ssdpDiscoverer = FakeSsdpDiscoverer(
                listOf(
                    SsdpResponse(
                        sourceIp = "192.168.1.20",
                        location = "http://192.168.1.20:1900/desc.xml",
                        server = "MeshNode/1.0",
                        searchTarget = "ssdp:all",
                        usn = "uuid:mesh-node",
                    ),
                ),
            ),
            upnpIgdProbe = NeverRespondingUpnpIgdProbe(),
        )

        val result = engine.discover()

        assertEquals(2, result.devices.size)
        val meshNode = result.devices.first { it.ip == "192.168.1.20" }
        assertEquals(TargetRole.MESH_NODE, meshNode.role)
        assertEquals(TargetSource.SSDP, meshNode.source)
    }

    @Test
    fun `flags possible double nat when gateway external ip is also private`() = runTest {
        val engine = DefaultDiscoveryEngine(
            networkEnvironmentReader = FakeNetworkEnvironmentReader(
                environment = wifiEnvironment(gatewayIp = "192.168.1.1"),
            ),
            ssdpDiscoverer = FakeSsdpDiscoverer(
                listOf(
                    SsdpResponse(
                        sourceIp = "192.168.1.1",
                        location = "http://192.168.1.1:1900/rootDesc.xml",
                        server = "GenericRouter/1.0",
                        searchTarget = "urn:schemas-upnp-org:device:InternetGatewayDevice:1",
                        usn = "uuid:gateway",
                    ),
                ),
            ),
            upnpIgdProbe = FixedExternalIpUpnpIgdProbe(externalIp = "10.0.0.5"),
        )

        val result = engine.discover()

        assertTrue(result.possibleDoubleNat)
    }

    @Test
    fun `does not flag double nat when upnp probe fails`() = runTest {
        val engine = DefaultDiscoveryEngine(
            networkEnvironmentReader = FakeNetworkEnvironmentReader(
                environment = wifiEnvironment(gatewayIp = "192.168.1.1"),
            ),
            ssdpDiscoverer = FakeSsdpDiscoverer(
                listOf(
                    SsdpResponse(
                        sourceIp = "192.168.1.1",
                        location = "http://192.168.1.1:1900/rootDesc.xml",
                        server = "GenericRouter/1.0",
                        searchTarget = "urn:schemas-upnp-org:device:InternetGatewayDevice:1",
                        usn = "uuid:gateway",
                    ),
                ),
            ),
            upnpIgdProbe = NeverRespondingUpnpIgdProbe(),
        )

        val result = engine.discover()

        assertFalse(result.possibleDoubleNat)
        assertEquals(1, result.devices.size)
    }

    @Test
    fun `flags possible double nat when gateway external ip is cgnat`() = runTest {
        // 100.64.0.235 — faixa CGNAT real (RFC 6598) capturada do NokiaOntDriver contra a
        // unidade física (SIG-333). Não é RFC 1918, mas é efetivamente uma camada de NAT
        // adicional operada pela operadora e deve ser tratada como sinal de duplo NAT.
        val engine = DefaultDiscoveryEngine(
            networkEnvironmentReader = FakeNetworkEnvironmentReader(
                environment = wifiEnvironment(gatewayIp = "192.168.1.1"),
            ),
            ssdpDiscoverer = FakeSsdpDiscoverer(
                listOf(
                    SsdpResponse(
                        sourceIp = "192.168.1.1",
                        location = "http://192.168.1.1:1900/rootDesc.xml",
                        server = "GenericRouter/1.0",
                        searchTarget = "urn:schemas-upnp-org:device:InternetGatewayDevice:1",
                        usn = "uuid:gateway",
                    ),
                ),
            ),
            upnpIgdProbe = FixedExternalIpUpnpIgdProbe(externalIp = "100.64.0.235"),
        )

        val result = engine.discover()

        assertTrue(result.possibleDoubleNat)
    }

    @Test
    fun `does not flag double nat when external ip is outside cgnat range`() = runTest {
        // 100.63.x.x e 100.128.x.x estão fora de 100.64.0.0/10 (segundo octeto 64-127) —
        // guarda contra falso positivo por engano de limite de faixa.
        val engineBelowRange = DefaultDiscoveryEngine(
            networkEnvironmentReader = FakeNetworkEnvironmentReader(
                environment = wifiEnvironment(gatewayIp = "192.168.1.1"),
            ),
            ssdpDiscoverer = FakeSsdpDiscoverer(
                listOf(
                    SsdpResponse(
                        sourceIp = "192.168.1.1",
                        location = "http://192.168.1.1:1900/rootDesc.xml",
                        server = "GenericRouter/1.0",
                        searchTarget = "urn:schemas-upnp-org:device:InternetGatewayDevice:1",
                        usn = "uuid:gateway",
                    ),
                ),
            ),
            upnpIgdProbe = FixedExternalIpUpnpIgdProbe(externalIp = "100.63.0.5"),
        )

        assertFalse(engineBelowRange.discover().possibleDoubleNat)
    }

    @Test
    fun `does not flag double nat when external ip is public`() = runTest {
        val engine = DefaultDiscoveryEngine(
            networkEnvironmentReader = FakeNetworkEnvironmentReader(
                environment = wifiEnvironment(gatewayIp = "192.168.1.1"),
            ),
            ssdpDiscoverer = FakeSsdpDiscoverer(
                listOf(
                    SsdpResponse(
                        sourceIp = "192.168.1.1",
                        location = "http://192.168.1.1:1900/rootDesc.xml",
                        server = "GenericRouter/1.0",
                        searchTarget = "urn:schemas-upnp-org:device:InternetGatewayDevice:1",
                        usn = "uuid:gateway",
                    ),
                ),
            ),
            upnpIgdProbe = FixedExternalIpUpnpIgdProbe(externalIp = "201.17.45.90"),
        )

        val result = engine.discover()

        assertFalse(result.possibleDoubleNat)
    }

    private fun wifiEnvironment(gatewayIp: String) = NetworkEnvironment(
        localIp = "192.168.1.50",
        gatewayIp = gatewayIp,
        subnetPrefixLength = 24,
        dnsServers = listOf("8.8.8.8"),
        isWifi = true,
    )
}

private class FakeNetworkEnvironmentReader(
    private val environment: NetworkEnvironment?,
) : NetworkEnvironmentReader {
    override suspend fun read(): NetworkEnvironment? = environment
}

private class FakeSsdpDiscoverer(
    private val responses: List<SsdpResponse>,
) : SsdpDiscoverer {
    override suspend fun discover(): List<SsdpResponse> = responses
}

private class NeverRespondingUpnpIgdProbe : UpnpIgdProbe {
    override suspend fun probeExternalIp(descriptorUrl: String): String? = null
}

private class FixedExternalIpUpnpIgdProbe(
    private val externalIp: String,
) : UpnpIgdProbe {
    override suspend fun probeExternalIp(descriptorUrl: String): String? = externalIp
}
