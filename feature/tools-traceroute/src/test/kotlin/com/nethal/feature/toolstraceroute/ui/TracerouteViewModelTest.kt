package com.nethal.feature.toolstraceroute.ui

import com.nethal.feature.toolstraceroute.domain.TracerouteAvailability
import com.nethal.feature.toolstraceroute.domain.TracerouteEngine
import com.nethal.feature.toolstraceroute.domain.TracerouteHop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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
 * Cobre os critérios de aceite da #102 no nível de `ViewModel`: hop com IP+RTT chegando na lista,
 * hop sem resposta (timeout) não interrompe o traceroute inteiro, e o estado "indisponível" usa o
 * componente de recurso indisponível em vez de deixar a tela quebrada/muda.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TracerouteViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeEngine(
        private val availability: TracerouteAvailability = TracerouteAvailability.Available,
        private val hopsByTarget: Map<String, List<TracerouteHop>> = emptyMap(),
    ) : TracerouteEngine {
        var traceCallCount = 0
            private set

        override suspend fun checkAvailability(): TracerouteAvailability = availability

        override fun trace(target: String, maxHops: Int): Flow<TracerouteHop> = flow {
            traceCallCount++
            hopsByTarget[target].orEmpty().forEach { emit(it) }
        }
    }

    @Test
    fun `unavailable engine surfaces the reason instead of an editable target field`() = runTest {
        val engine = FakeEngine(availability = TracerouteAvailability.Unavailable("binário de ping não encontrado"))
        val viewModel = TracerouteViewModel(engine)

        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is TracerouteUiState.Unavailable)
        assertEquals("binário de ping não encontrado", (state as TracerouteUiState.Unavailable).reason)
    }

    @Test
    fun `available engine starts Ready with the default target and empty run state`() = runTest {
        val viewModel = TracerouteViewModel(FakeEngine())

        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is TracerouteUiState.Ready)
        state as TracerouteUiState.Ready
        assertEquals("8.8.8.8", state.target)
        assertTrue(state.runState is TracerouteRunState.Empty)
        assertFalse(state.isRunning)
    }

    @Test
    fun `execute reports hop IP and RTT as they arrive from the engine`() = runTest {
        val hop1 = TracerouteHop.Responded(ttl = 1, address = "192.168.1.1", rttMillis = 2)
        val hop2 = TracerouteHop.Responded(ttl = 2, address = "8.8.8.8", rttMillis = 18, isTarget = true)
        val engine = FakeEngine(hopsByTarget = mapOf("8.8.8.8" to listOf(hop1, hop2)))
        val viewModel = TracerouteViewModel(engine)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.execute()
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value as TracerouteUiState.Ready
        val completed = state.runState as TracerouteRunState.Completed
        assertEquals(listOf(hop1, hop2), completed.hops)
        assertTrue(completed.reachedTarget)
        assertFalse(state.isRunning)
    }

    @Test
    fun `a timed out hop does not break the rest of the traceroute`() = runTest {
        val respondingHop = TracerouteHop.Responded(ttl = 1, address = "192.168.1.1", rttMillis = 2)
        val timedOutHop = TracerouteHop.TimedOut(ttl = 2)
        val finalHop = TracerouteHop.Responded(ttl = 3, address = "8.8.8.8", rttMillis = 14, isTarget = true)
        val engine = FakeEngine(
            hopsByTarget = mapOf("8.8.8.8" to listOf(respondingHop, timedOutHop, finalHop)),
        )
        val viewModel = TracerouteViewModel(engine)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.execute()
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value as TracerouteUiState.Ready
        val completed = state.runState as TracerouteRunState.Completed
        assertEquals(3, completed.hops.size)
        assertTrue(completed.hops[1] is TracerouteHop.TimedOut)
        assertTrue(completed.reachedTarget)
    }

    @Test
    fun `blank target does not trigger a trace`() = runTest {
        val engine = FakeEngine()
        val viewModel = TracerouteViewModel(engine)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.onTargetChanged("   ")
        viewModel.execute()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(0, engine.traceCallCount)
        val state = viewModel.uiState.value as TracerouteUiState.Ready
        assertTrue(state.runState is TracerouteRunState.Empty)
    }

    @Test
    fun `executing again cancels the in-flight trace instead of stacking results`() = runTest {
        val hop = TracerouteHop.Responded(ttl = 1, address = "1.1.1.1", rttMillis = 5, isTarget = true)
        val engine = FakeEngine(hopsByTarget = mapOf("1.1.1.1" to listOf(hop)))
        val viewModel = TracerouteViewModel(engine)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.onTargetChanged("1.1.1.1")
        viewModel.execute()
        viewModel.execute()
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value as TracerouteUiState.Ready
        val completed = state.runState as TracerouteRunState.Completed
        assertEquals(listOf(hop), completed.hops)
    }
}
