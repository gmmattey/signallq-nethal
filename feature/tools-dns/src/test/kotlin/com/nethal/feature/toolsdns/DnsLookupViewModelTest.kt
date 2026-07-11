package com.nethal.feature.toolsdns

import com.nethal.core.model.DnsLookupOutcome
import com.nethal.core.model.DnsLookupResult
import com.nethal.core.model.DnsRecordAnswer
import com.nethal.core.model.DnsRecordType
import com.nethal.feature.toolsdns.data.DnsLookupClient
import com.nethal.feature.toolsdns.data.NetworkConnectivityChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DnsLookupViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `execute sem hostname retorna erro sem chamar client`() = runTest {
        val client = FakeDnsLookupClient(outcome = null)
        val viewModel = DnsLookupViewModel(client, FakeConnectivityChecker(hasInternet = true))

        viewModel.execute()

        assertTrue(viewModel.uiState.value is DnsLookupUiState.Error)
        assertEquals(0, client.callCount)
    }

    @Test
    fun `execute sem rede vai para NoNetwork sem chamar client`() = runTest {
        val client = FakeDnsLookupClient(outcome = null)
        val viewModel = DnsLookupViewModel(client, FakeConnectivityChecker(hasInternet = false))
        viewModel.onHostnameChanged("nethal.com.br")

        viewModel.execute()

        assertEquals(DnsLookupUiState.NoNetwork, viewModel.uiState.value)
        assertEquals(0, client.callCount)
    }

    @Test
    fun `execute com sucesso real do client publica Success`() = runTest {
        val result = DnsLookupResult(
            hostname = "nethal.com.br",
            answers = listOf(DnsRecordAnswer(DnsRecordType.A, listOf("187.45.12.90"))),
            serverLabel = "Cloudflare (1.1.1.1)",
            elapsedMillis = 24,
        )
        val client = FakeDnsLookupClient(outcome = DnsLookupOutcome.Success(result))
        val viewModel = DnsLookupViewModel(client, FakeConnectivityChecker(hasInternet = true))
        viewModel.onHostnameChanged("nethal.com.br")

        viewModel.execute()
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value as DnsLookupUiState.Success
        assertEquals(result, state.result)
        assertEquals(1, client.callCount)
    }

    @Test
    fun `execute com falha do client publica Error honesto`() = runTest {
        val client = FakeDnsLookupClient(
            outcome = DnsLookupOutcome.Failure("host-invalido.invalid", "Domínio não encontrado (NXDOMAIN)."),
        )
        val viewModel = DnsLookupViewModel(client, FakeConnectivityChecker(hasInternet = true))
        viewModel.onHostnameChanged("host-invalido.invalid")

        viewModel.execute()
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value as DnsLookupUiState.Error
        assertTrue(state.reason.contains("NXDOMAIN"))
    }

    private class FakeDnsLookupClient(private val outcome: DnsLookupOutcome?) : DnsLookupClient {
        var callCount = 0
            private set

        override suspend fun lookup(hostname: String): DnsLookupOutcome {
            callCount++
            return outcome ?: error("FakeDnsLookupClient chamado sem outcome configurado")
        }
    }

    private class FakeConnectivityChecker(private val hasInternet: Boolean) : NetworkConnectivityChecker {
        override fun hasInternet(): Boolean = hasInternet
    }
}
