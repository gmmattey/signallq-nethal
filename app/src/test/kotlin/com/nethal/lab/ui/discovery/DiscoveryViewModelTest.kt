package com.nethal.lab.ui.discovery

import com.nethal.core.discovery.DiscoveryEngine
import com.nethal.core.discovery.NetworkEnvironment
import com.nethal.core.discovery.NetworkEnvironmentReader
import com.nethal.core.model.DiscoveryResult
import com.nethal.core.model.TargetRole
import com.nethal.core.model.TargetSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiscoveryViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private val fakeEnvironment = NetworkEnvironment(
        localIp = "192.168.1.50",
        gatewayIp = "192.168.1.1",
        subnetPrefixLength = 24,
        dnsServers = listOf("8.8.8.8"),
        isWifi = true,
    )

    private val environmentReader = object : NetworkEnvironmentReader {
        override suspend fun read(): NetworkEnvironment = fakeEnvironment
    }

    private val emptyDiscoveryEngine = object : DiscoveryEngine {
        override suspend fun discover(): DiscoveryResult = DiscoveryResult(devices = emptyList(), possibleDoubleNat = false)
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `addManualTarget rejects public ip and never reaches discovery`() = runTest {
        val viewModel = DiscoveryViewModel(emptyDiscoveryEngine, environmentReader)

        viewModel.addManualTarget("8.8.8.8")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            "Esse IP não parece ser da sua rede local. O NetHAL só testa equipamentos na sua LAN.",
            viewModel.manualTargetError.value,
        )
        // Estado de discovery nunca chega a rodar - segue no que foi inicializado, não vira Failed/MultipleCandidates.
        assertTrue(viewModel.uiState.value is DiscoveryUiState.AwaitingLocationPermission)
    }

    @Test
    fun `addManualTarget accepts private ip and clears previous error`() = runTest {
        val viewModel = DiscoveryViewModel(emptyDiscoveryEngine, environmentReader)

        viewModel.addManualTarget("8.8.8.8")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(true, viewModel.manualTargetError.value != null)

        viewModel.addManualTarget("192.168.1.99")
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.manualTargetError.value)
        val state = viewModel.uiState.value
        assertTrue(state is DiscoveryUiState.SingleCandidateReady)
        assertEquals("192.168.1.99", (state as DiscoveryUiState.SingleCandidateReady).device.ip)
        assertEquals(TargetRole.MANUAL, state.device.role)
        assertEquals(TargetSource.USER_INPUT, state.device.source)
    }
}
