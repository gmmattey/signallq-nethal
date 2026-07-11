package com.nethal.feature.toolsping

import com.nethal.core.model.PortCheckStatus
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
import java.net.InetAddress
import java.net.ServerSocket

/**
 * Cobertura de orquestração de estado da tela Verificação de porta (issue #94) — a mecânica real de
 * TCP probe já é coberta em profundidade por `PortCheckerTest`/`TcpProbeTest` em `:core:protocol`;
 * aqui o foco é validação de entrada + transição de estado. Mesma técnica de dispatcher único
 * documentada em `PingViewModelTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PortCheckViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private var server: ServerSocket? = null

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        server?.close()
    }

    private fun viewModel(defaultTargetHost: String?) =
        PortCheckViewModel(defaultTargetHost, ioDispatcher = dispatcher)

    @Test
    fun `no default target host surfaces Unavailable`() {
        val viewModel = viewModel(defaultTargetHost = null)

        assertTrue(viewModel.uiState.value is PortCheckUiState.Unavailable)
    }

    @Test
    fun `a known default target host starts Ready with port 443 suggested`() {
        val viewModel = viewModel(defaultTargetHost = "192.168.1.1")

        val state = viewModel.uiState.value as PortCheckUiState.Ready
        assertEquals("192.168.1.1", state.targetHost)
        assertEquals("443", state.port)
    }

    @Test
    fun `invalid port surfaces an inline error without attempting any connection`() = runTest {
        val viewModel = viewModel(defaultTargetHost = "127.0.0.1")
        viewModel.onPortChanged("not-a-port")

        viewModel.run()
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value as PortCheckUiState.Ready
        assertTrue(!state.isRunning)
        assertNull(state.result)
        assertTrue(state.errorMessage.orEmpty().contains("inválida") || state.errorMessage.orEmpty().contains("Porta"))
    }

    @Test
    fun `port out of the 1-65535 range surfaces an inline error`() = runTest {
        val viewModel = viewModel(defaultTargetHost = "127.0.0.1")
        viewModel.onPortChanged("70000")

        viewModel.run()
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value as PortCheckUiState.Ready
        assertNull(state.result)
        assertTrue(state.errorMessage != null)
    }

    @Test
    fun `run against a real open loopback port reports OPEN`() = runTest {
        val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        server = listener
        val viewModel = viewModel(defaultTargetHost = "127.0.0.1")
        viewModel.onPortChanged(listener.localPort.toString())

        viewModel.run()
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value as PortCheckUiState.Ready
        assertTrue(!state.isRunning)
        assertEquals(PortCheckStatus.OPEN, state.result?.status)
    }

    @Test
    fun `run against a closed loopback port reports CLOSED`() = runTest {
        val closedPort = ServerSocket(0).use { it.localPort }
        val viewModel = viewModel(defaultTargetHost = "127.0.0.1")
        viewModel.onPortChanged(closedPort.toString())

        viewModel.run()
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value as PortCheckUiState.Ready
        assertEquals(PortCheckStatus.CLOSED, state.result?.status)
    }

    @Test
    fun `run against a public ip surfaces the guard rejection, never a fabricated result`() = runTest {
        val viewModel = viewModel(defaultTargetHost = "8.8.8.8")

        viewModel.run()
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value as PortCheckUiState.Ready
        assertNull(state.result)
        assertTrue(state.errorMessage.orEmpty().contains("8.8.8.8"))
    }
}
