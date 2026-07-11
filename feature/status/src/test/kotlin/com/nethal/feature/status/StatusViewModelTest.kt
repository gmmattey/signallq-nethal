package com.nethal.feature.status

import androidx.lifecycle.ViewModelStore
import com.nethal.core.capability.CapabilityEngine
import com.nethal.core.catalog.CapabilityReadResult
import com.nethal.core.catalog.DriverFamily
import com.nethal.core.catalog.DriverFamilyAuthResult
import com.nethal.core.model.Capability
import com.nethal.core.model.CapabilityId
import com.nethal.core.model.CapabilityPayload
import com.nethal.core.model.CapabilityState
import com.nethal.core.model.DeviceInfo
import com.nethal.core.model.DeviceType
import com.nethal.core.model.GponErrorCounters
import com.nethal.core.model.LanPort
import com.nethal.core.model.LanPortStatusList
import com.nethal.core.model.MeshTopology
import com.nethal.core.model.MeshTopologyNode
import com.nethal.core.model.SignalStatus
import com.nethal.core.model.WanStatus
import com.nethal.core.model.WifiBand
import com.nethal.core.model.WifiRadio
import com.nethal.core.model.WifiStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Cobre os três pontos exigidos pela issue #107: mecanismo de refresh (poll em intervalo +
 * pull-to-refresh), sessão fechando ao sair da tela (nunca em background), e os estados
 * disponível/indisponível — mesmo padrão de fixture de `CapabilitiesViewModelTest`
 * (`FakeDriverFamily`/`CapabilityEngine` real, sem mockar o motor).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StatusViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Devolve resultados fixos por [CapabilityId] e conta quantas vezes cada um foi lido — usado para provar que o poll de fato repete leituras, e que parar o poll de fato as interrompe. */
    private class CountingDriverFamily(
        private val readResults: Map<CapabilityId, CapabilityReadResult>,
    ) : DriverFamily {
        val readCounts = mutableMapOf<CapabilityId, Int>()

        override suspend fun readCapability(id: CapabilityId): CapabilityReadResult {
            readCounts[id] = (readCounts[id] ?: 0) + 1
            return readResults[id] ?: CapabilityReadResult.Unavailable(reason = "fixture não cobre $id")
        }

        override suspend fun authenticate(username: String, password: String): DriverFamilyAuthResult =
            DriverFamilyAuthResult.Success
    }

    private suspend fun activeEngine(family: CountingDriverFamily): CapabilityEngine {
        val engine = CapabilityEngine(family, "admin", "secret")
        engine.testCredentials()
        return engine
    }

    private fun deviceInfoSuccess() = CapabilityReadResult.Success(
        capability = Capability(id = CapabilityId.READ_DEVICE_INFO, state = CapabilityState.AVAILABLE, confidence = 1.0),
        payload = CapabilityPayload.DeviceInfo(
            DeviceInfo(vendor = "Nokia", model = "G-1425-B", firmware = "v2.1.3", uptimeSeconds = 3_700),
        ),
    )

    private fun wifiSuccess() = CapabilityReadResult.Success(
        capability = Capability(id = CapabilityId.READ_WIFI_STATUS, state = CapabilityState.AVAILABLE, confidence = 1.0),
        payload = CapabilityPayload.Wifi(
            WifiStatus(
                radios = listOf(
                    WifiRadio(
                        id = "radio0",
                        band = WifiBand.GHZ_5,
                        enabled = true,
                        ssid = "NETHAL-Home",
                        channel = 44,
                        security = "WPA3",
                    ),
                ),
            ),
        ),
    )

    private fun wanSuccess() = CapabilityReadResult.Success(
        capability = Capability(id = CapabilityId.READ_WAN_STATUS, state = CapabilityState.AVAILABLE, confidence = 1.0),
        payload = CapabilityPayload.Wan(WanStatus(ipv4Address = "203.0.113.9")),
    )

    // --- Fixtures das variantes ONT (#87) e Mesh (#88+#106) -------------------------------------

    private fun deviceInfoOntSuccess() = CapabilityReadResult.Success(
        capability = Capability(id = CapabilityId.READ_DEVICE_INFO, state = CapabilityState.AVAILABLE, confidence = 1.0),
        payload = CapabilityPayload.DeviceInfo(
            DeviceInfo(vendor = "Nokia", model = "G-1425-B", deviceType = DeviceType.ONT),
        ),
    )

    private fun signalSuccess(
        rxPowerDbm: Double = -18.5,
        txPowerDbm: Double = 2.3,
        marginDb: Double? = 4.5,
    ) = CapabilityReadResult.Success(
        capability = Capability(id = CapabilityId.READ_SIGNAL, state = CapabilityState.AVAILABLE, confidence = 1.0),
        payload = CapabilityPayload.Signal(
            SignalStatus(
                rxPowerDbm = rxPowerDbm,
                txPowerDbm = txPowerDbm,
                rxPowerMarginToLowerThresholdDb = marginDb,
            ),
        ),
    )

    private fun gponErrorsSuccess() = CapabilityReadResult.Success(
        capability = Capability(id = CapabilityId.READ_GPON_ERROR_COUNTERS, state = CapabilityState.AVAILABLE, confidence = 1.0),
        payload = CapabilityPayload.GponErrorCounters(
            GponErrorCounters(fecErrorCount = 12, hecErrorCount = 3, dropPacketsCount = 0),
        ),
    )

    private fun lanPortsSuccess() = CapabilityReadResult.Success(
        capability = Capability(id = CapabilityId.READ_LAN_PORT_STATUS, state = CapabilityState.AVAILABLE, confidence = 1.0),
        payload = CapabilityPayload.LanPorts(
            LanPortStatusList(
                ports = listOf(
                    LanPort(portNumber = 1, isUp = true, linkSpeedMbps = "1000"),
                    LanPort(portNumber = 2, isUp = false),
                ),
            ),
        ),
    )

    private fun meshTopologySuccess(
        clients: List<MeshTopologyNode> = listOf(
            MeshTopologyNode(hostname = "notebook", macAddress = "11:22:33:44:55:66", ipAddress = "192.168.1.5", wireType = "wireless"),
        ),
        satelliteNodeCount: Int = 1,
    ) = CapabilityReadResult.Success(
        capability = Capability(id = CapabilityId.READ_MESH_TOPOLOGY, state = CapabilityState.AVAILABLE, confidence = 1.0),
        payload = CapabilityPayload.MeshTopology(
            MeshTopology(
                routerModel = "Archer C6",
                routerName = "NETHAL-Home",
                routerMacAddress = "AA:BB:CC:DD:EE:FF",
                clients = clients,
                satelliteNodeCount = satelliteNodeCount,
            ),
        ),
    )

    // --- Estados disponível/indisponível -------------------------------------------------------

    @Test
    fun `no capability engine surfaces SessionUnavailable instead of faking data`() = runTest {
        val viewModel = StatusViewModel(capabilityEngine = null)

        viewModel.onScreenStarted()
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value is StatusUiState.SessionUnavailable)
    }

    @Test
    fun `never-authenticated session surfaces SessionUnavailable and never touches the device`() = runTest {
        val family = CountingDriverFamily(emptyMap())
        val engine = CapabilityEngine(family, "admin", "secret") // isSessionActive continua false
        val viewModel = StatusViewModel(capabilityEngine = engine)

        viewModel.onScreenStarted()
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value is StatusUiState.SessionUnavailable)
        assertEquals(0, family.readCounts.values.sum())
    }

    @Test
    fun `active session loads equipment and wifi cards with real capability data`() = runTest {
        val family = CountingDriverFamily(
            mapOf(
                CapabilityId.READ_DEVICE_INFO to deviceInfoSuccess(),
                CapabilityId.READ_WIFI_STATUS to wifiSuccess(),
                CapabilityId.READ_WAN_STATUS to wanSuccess(),
            ),
        )
        val engine = activeEngine(family)
        val viewModel = StatusViewModel(capabilityEngine = engine)

        viewModel.onScreenStarted()
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is StatusUiState.Loaded)
        state as StatusUiState.Loaded
        assertEquals("Nokia G-1425-B", state.equipmentLabel)
        assertEquals(StatusDotLevel.OK, state.equipmentDot)
        assertEquals("203.0.113.9", state.publicIp)
        assertEquals("NETHAL-Home · 5 GHz", state.wifi?.label)
        assertEquals(StatusDotLevel.OK, state.wifi?.dot)
        // Sem CapabilityId de teste de velocidade hoje — nunca inventa uma amostra.
        assertEquals(null, state.speed)
    }

    @Test
    fun `wifi read failure surfaces ERROR dot with the honest reason instead of hiding the row`() = runTest {
        val family = CountingDriverFamily(
            mapOf(
                CapabilityId.READ_DEVICE_INFO to deviceInfoSuccess(),
                CapabilityId.READ_WIFI_STATUS to CapabilityReadResult.Failure(reason = "timeout ao ler Wi-Fi"),
            ),
        )
        val engine = activeEngine(family)
        val viewModel = StatusViewModel(capabilityEngine = engine)

        viewModel.onScreenStarted()
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value as StatusUiState.Loaded
        assertEquals(StatusDotLevel.ERROR, state.wifi?.dot)
        assertEquals("timeout ao ler Wi-Fi", state.wifi?.detail)
    }

    // --- Variante ONT (issue #87) e Mesh (issue #88+#106) ----------------------------------------

    @Test
    fun `ONT device type surfaces the Ont variant with real signal, gpon error and lan port data`() = runTest {
        val family = CountingDriverFamily(
            mapOf(
                CapabilityId.READ_DEVICE_INFO to deviceInfoOntSuccess(),
                CapabilityId.READ_SIGNAL to signalSuccess(),
                CapabilityId.READ_GPON_ERROR_COUNTERS to gponErrorsSuccess(),
                CapabilityId.READ_LAN_PORT_STATUS to lanPortsSuccess(),
            ),
        )
        val engine = activeEngine(family)
        val viewModel = StatusViewModel(capabilityEngine = engine)

        viewModel.onScreenStarted()
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value as StatusUiState.Loaded
        val variant = state.variant
        assertTrue(variant is StatusVariant.Ont)
        variant as StatusVariant.Ont
        assertEquals(-18.5, variant.signal.rxPowerDbm)
        assertEquals(2.3, variant.signal.txPowerDbm)
        assertEquals(4.5, variant.signal.rxPowerMarginToLowerThresholdDb)
        assertEquals(StatusDotLevel.OK, variant.signal.dot)
        assertEquals(null, variant.signal.unavailableReason)
        assertEquals(12L, variant.gponErrors.fecErrorCount)
        assertEquals(3L, variant.gponErrors.hecErrorCount)
        assertEquals(2, variant.lanPorts.ports.size)
        assertTrue(variant.lanPorts.ports[0].isUp)
        assertFalse(variant.lanPorts.ports[1].isUp)
        // Equipamento ONT nunca dispara leitura de topologia mesh (resolveVariant, passo 3).
        assertEquals(0, family.readCounts[CapabilityId.READ_MESH_TOPOLOGY] ?: 0)
    }

    @Test
    fun `successful READ_MESH_TOPOLOGY without a declared device type surfaces the Mesh variant with real topology`() = runTest {
        val family = CountingDriverFamily(mapOf(CapabilityId.READ_MESH_TOPOLOGY to meshTopologySuccess()))
        val engine = activeEngine(family)
        val viewModel = StatusViewModel(capabilityEngine = engine)

        viewModel.onScreenStarted()
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value as StatusUiState.Loaded
        val variant = state.variant
        assertTrue(variant is StatusVariant.Mesh)
        variant as StatusVariant.Mesh
        assertEquals("Archer C6 · NETHAL-Home", variant.topology.routerLabel)
        assertEquals(1, variant.topology.satelliteNodeCount)
        assertEquals(1, variant.topology.clients.size)
        assertEquals("notebook", variant.topology.clients.first().hostname)
        assertEquals(null, variant.topology.unavailableReason)
        // Equipamento mesh (sem READ_DEVICE_INFO/deviceType) nunca dispara leitura de sinal óptico.
        assertEquals(0, family.readCounts[CapabilityId.READ_SIGNAL] ?: 0)
    }

    @Test
    fun `mesh topology with zero satellites and no clients is real data, not an unavailable state`() = runTest {
        val family = CountingDriverFamily(
            mapOf(CapabilityId.READ_MESH_TOPOLOGY to meshTopologySuccess(clients = emptyList(), satelliteNodeCount = 0)),
        )
        val engine = activeEngine(family)
        val viewModel = StatusViewModel(capabilityEngine = engine)

        viewModel.onScreenStarted()
        dispatcher.scheduler.advanceUntilIdle()

        val variant = (viewModel.uiState.value as StatusUiState.Loaded).variant as StatusVariant.Mesh
        assertEquals(null, variant.topology.unavailableReason)
        assertEquals(0, variant.topology.satelliteNodeCount)
        assertTrue(variant.topology.clients.isEmpty())
    }

    @Test
    fun `ONT device with a transient signal read failure keeps the Ont variant with an honest reason, never fake data`() = runTest {
        val family = CountingDriverFamily(
            mapOf(
                CapabilityId.READ_DEVICE_INFO to deviceInfoOntSuccess(),
                CapabilityId.READ_SIGNAL to CapabilityReadResult.Failure(reason = "timeout ao ler sinal óptico"),
            ),
        )
        val engine = activeEngine(family)
        val viewModel = StatusViewModel(capabilityEngine = engine)

        viewModel.onScreenStarted()
        dispatcher.scheduler.advanceUntilIdle()

        // O cast abaixo já prova que a variante continua Ont mesmo com a leitura de sinal falhando
        // neste tick — nunca cai silenciosamente para a variante genérica só porque um sub-bloco falhou.
        val variant = (viewModel.uiState.value as StatusUiState.Loaded).variant as StatusVariant.Ont
        assertEquals("timeout ao ler sinal óptico", variant.signal.unavailableReason)
        assertEquals(StatusDotLevel.ERROR, variant.signal.dot)
        assertEquals(null, variant.signal.rxPowerDbm)
    }

    @Test
    fun `router without ONT device type or mesh topology keeps the generic variant, never inventing a specialized card`() = runTest {
        val family = CountingDriverFamily(
            mapOf(
                CapabilityId.READ_DEVICE_INFO to deviceInfoSuccess(),
                CapabilityId.READ_WIFI_STATUS to wifiSuccess(),
                CapabilityId.READ_WAN_STATUS to wanSuccess(),
            ),
        )
        val engine = activeEngine(family)
        val viewModel = StatusViewModel(capabilityEngine = engine)

        viewModel.onScreenStarted()
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value as StatusUiState.Loaded
        assertEquals(null, state.variant)
        // Tentou detectar (mesh primeiro, sinal depois) mas nenhuma capability respondeu com sucesso.
        assertEquals(1, family.readCounts[CapabilityId.READ_MESH_TOPOLOGY] ?: 0)
        assertEquals(1, family.readCounts[CapabilityId.READ_SIGNAL] ?: 0)
    }

    // --- Mecanismo de atualização (poll em intervalo + pull-to-refresh) -------------------------

    @Test
    fun `onScreenStarted polls again after the interval elapses`() = runTest {
        val family = CountingDriverFamily(mapOf(CapabilityId.READ_DEVICE_INFO to deviceInfoSuccess()))
        val engine = activeEngine(family)
        val viewModel = StatusViewModel(capabilityEngine = engine)

        viewModel.onScreenStarted()
        dispatcher.scheduler.advanceUntilIdle()
        val firstReadCount = family.readCounts[CapabilityId.READ_DEVICE_INFO] ?: 0
        assertTrue(firstReadCount >= 1)

        dispatcher.scheduler.advanceTimeBy(20_000)
        dispatcher.scheduler.advanceUntilIdle()

        val secondReadCount = family.readCounts[CapabilityId.READ_DEVICE_INFO] ?: 0
        assertTrue(
            "esperava novo poll após o intervalo, contagem ficou em $secondReadCount",
            secondReadCount > firstReadCount,
        )
    }

    @Test
    fun `refreshNow forces an immediate read and toggles isRefreshing while it is in flight`() = runTest {
        val family = CountingDriverFamily(mapOf(CapabilityId.READ_DEVICE_INFO to deviceInfoSuccess()))
        val engine = activeEngine(family)
        val viewModel = StatusViewModel(capabilityEngine = engine)

        viewModel.onScreenStarted()
        dispatcher.scheduler.advanceUntilIdle()
        assertFalse(viewModel.isRefreshing.value)
        val countBeforeRefresh = family.readCounts[CapabilityId.READ_DEVICE_INFO] ?: 0

        viewModel.refreshNow()
        dispatcher.scheduler.advanceUntilIdle()

        assertFalse("isRefreshing deve voltar a false ao concluir a leitura", viewModel.isRefreshing.value)
        val countAfterRefresh = family.readCounts[CapabilityId.READ_DEVICE_INFO] ?: 0
        assertTrue(countAfterRefresh > countBeforeRefresh)
    }

    // --- Sessão nunca fica aberta em background --------------------------------------------------

    @Test
    fun `onScreenStopped cancels polling and closes the administrative session`() = runTest {
        val family = CountingDriverFamily(mapOf(CapabilityId.READ_DEVICE_INFO to deviceInfoSuccess()))
        val engine = activeEngine(family)
        val viewModel = StatusViewModel(capabilityEngine = engine)

        viewModel.onScreenStarted()
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(engine.isSessionActive)

        viewModel.onScreenStopped()
        assertFalse(engine.isSessionActive)

        val countAtStop = family.readCounts[CapabilityId.READ_DEVICE_INFO] ?: 0
        dispatcher.scheduler.advanceTimeBy(60_000)
        dispatcher.scheduler.advanceUntilIdle()
        val countAfterWaiting = family.readCounts[CapabilityId.READ_DEVICE_INFO] ?: 0

        assertEquals(
            "não pode continuar lendo o equipamento com a tela fora de composição (sessão em background)",
            countAtStop,
            countAfterWaiting,
        )
    }

    @Test
    fun `re-entering the screen after onScreenStopped surfaces SessionUnavailable instead of reauthenticating silently`() = runTest {
        val family = CountingDriverFamily(mapOf(CapabilityId.READ_DEVICE_INFO to deviceInfoSuccess()))
        val engine = activeEngine(family)
        val viewModel = StatusViewModel(capabilityEngine = engine)

        viewModel.onScreenStarted()
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.onScreenStopped()

        // Reentrada (troca de aba e volta) — a credencial já foi descartada por `closeSession`,
        // então não há como reautenticar sozinho aqui (nenhuma senha chega a este módulo).
        viewModel.onScreenStarted()
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value is StatusUiState.SessionUnavailable)
    }

    @Test
    fun `onCleared closes the session as a safety net even if the screen never calls onScreenStopped`() = runTest {
        val family = CountingDriverFamily(mapOf(CapabilityId.READ_DEVICE_INFO to deviceInfoSuccess()))
        val engine = activeEngine(family)
        val viewModel = StatusViewModel(capabilityEngine = engine)

        viewModel.onScreenStarted()
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(engine.isSessionActive)

        // `ViewModel.onCleared()` é `protected` — o gatilho de teste padrão é limpar o
        // `ViewModelStore` que o contém, mesma técnica usada pelo framework em runtime.
        val store = ViewModelStore()
        store.put("status", viewModel)
        store.clear()

        assertFalse(engine.isSessionActive)
    }
}
