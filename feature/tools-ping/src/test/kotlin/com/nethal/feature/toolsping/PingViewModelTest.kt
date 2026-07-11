package com.nethal.feature.toolsping

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

/**
 * Cobertura de orquestração de estado da tela Ping (issue #91) — a mecânica real de TCP probe
 * (aberto/fechado/timeout, fallback entre portas) já é coberta em profundidade por
 * `LatencyMeasurerTest`/`TcpProbeTest` em `:core:protocol`; aqui o foco é a transição de estado
 * (Unavailable/Ready/isRunning/errorMessage), não reimplementar aquela cobertura.
 *
 * O mesmo [StandardTestDispatcher] é injetado tanto como `Main` (via [Dispatchers.setMain]) quanto
 * como `ioDispatcher` do [PingViewModel] — sem isso, `withContext(Dispatchers.IO)` escaparia para
 * um dispatcher real fora do controle de `advanceUntilIdle()`, e o teste ficaria flaky/lendo estado
 * antes de a corrotina de fato terminar.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PingViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(defaultTargetHost: String?) = PingViewModel(defaultTargetHost, ioDispatcher = dispatcher)

    @Test
    fun `no default target host surfaces Unavailable instead of an empty editable field`() {
        val viewModel = viewModel(defaultTargetHost = null)

        assertTrue(viewModel.uiState.value is PingUiState.Unavailable)
    }

    @Test
    fun `a known default target host starts Ready with that host pre-filled`() {
        val viewModel = viewModel(defaultTargetHost = "192.168.1.1")

        val state = viewModel.uiState.value
        assertTrue(state is PingUiState.Ready)
        assertEquals("192.168.1.1", (state as PingUiState.Ready).targetHost)
    }

    @Test
    fun `onTargetHostChanged updates the editable field and clears a previous error`() {
        val viewModel = viewModel(defaultTargetHost = "192.168.1.1")

        viewModel.onTargetHostChanged("10.0.0.5")

        val state = viewModel.uiState.value as PingUiState.Ready
        assertEquals("10.0.0.5", state.targetHost)
        assertNull(state.errorMessage)
    }

    @Test
    fun `run against loopback with nothing listening yields a real 100 percent loss result, not a fake success`() = runTest {
        val viewModel = viewModel(defaultTargetHost = "127.0.0.1")

        viewModel.run()
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value as PingUiState.Ready
        assertTrue("não deve ficar preso em isRunning", !state.isRunning)
        assertEquals(null, state.errorMessage)
        assertTrue("resultado real deve chegar, mesmo que 100% de perda", state.result != null)
        assertEquals(100.0, state.result!!.packetLossPercent, 0.001)
    }

    @Test
    fun `run against a public ip surfaces the guard rejection as an honest error, never a fabricated result`() = runTest {
        val viewModel = viewModel(defaultTargetHost = "8.8.8.8")

        viewModel.run()
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value as PingUiState.Ready
        assertTrue(!state.isRunning)
        assertTrue(state.errorMessage.orEmpty().contains("8.8.8.8"))
        assertNull(state.result)
    }

    @Test
    fun `run toggles isRunning while the probe is in flight`() = runTest {
        val viewModel = viewModel(defaultTargetHost = "127.0.0.1")

        viewModel.run()
        // Antes de o scheduler avançar, a corrotina de IO ainda não concluiu.
        assertTrue((viewModel.uiState.value as PingUiState.Ready).isRunning)

        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(!(viewModel.uiState.value as PingUiState.Ready).isRunning)
    }
}
