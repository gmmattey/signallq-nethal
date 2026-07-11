package com.nethal.feature.toolsspeedtest

import androidx.lifecycle.ViewModelStore
import com.nethal.core.model.BufferbloatSeverity
import com.nethal.core.model.SpeedtestBottleneck
import com.nethal.core.model.SpeedtestMode
import com.nethal.core.model.SpeedtestPhase
import com.nethal.core.model.SpeedtestQualityDiagnosis
import com.nethal.core.model.SpeedtestResult
import com.nethal.core.model.SpeedtestRunState
import com.nethal.core.model.SpeedtestSnapshot
import com.nethal.core.model.SpeedtestUsageVerdict
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
class SpeedtestViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun sampleResult() = SpeedtestResult(
        timestampEpochMs = 1_000L,
        mode = SpeedtestMode.FAST,
        downloadMbps = 120.0,
        uploadMbps = 40.0,
        latencyMs = 12.0,
        jitterMs = 2.0,
        packetLossPercent = 0.0,
        bufferbloatMs = 3.0,
        bufferbloatSeverity = BufferbloatSeverity.NONE,
        peakDownloadMbps = 130.0,
        peakUploadMbps = 45.0,
        qualityDiagnosis = SpeedtestQualityDiagnosis(
            streamingVerdict = SpeedtestUsageVerdict.GOOD,
            gamingVerdict = SpeedtestUsageVerdict.GOOD,
            videoCallVerdict = SpeedtestUsageVerdict.GOOD,
            primaryBottleneck = SpeedtestBottleneck.NONE,
        ),
    )

    @Test
    fun `startTest without connectivity surfaces NoConnectivity and never calls the engine`() = runTest {
        val engine = FakeSpeedtestEngine(SpeedtestSnapshot(runState = SpeedtestRunState.IDLE))
        val viewModel = SpeedtestViewModel(engine = engine, hasNetworkConnectivity = { false })

        viewModel.startTest()
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value is SpeedtestUiState.NoConnectivity)
        assertEquals(0, engine.runCallCount)
    }

    @Test
    fun `startTest with connectivity runs the engine in FAST mode by default`() = runTest {
        val engine = FakeSpeedtestEngine(SpeedtestSnapshot(runState = SpeedtestRunState.IDLE))
        val viewModel = SpeedtestViewModel(engine = engine, hasNetworkConnectivity = { true })

        viewModel.startTest()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, engine.runCallCount)
        assertEquals(SpeedtestMode.FAST, engine.lastModeRequested)
    }

    @Test
    fun `running snapshot maps to Running ui state with the same phase, progress and live speed`() = runTest {
        val engine = FakeSpeedtestEngine(SpeedtestSnapshot(runState = SpeedtestRunState.IDLE))
        val viewModel = SpeedtestViewModel(engine = engine, hasNetworkConnectivity = { true })
        viewModel.startTest()
        dispatcher.scheduler.advanceUntilIdle()

        engine.publish(
            SpeedtestSnapshot(
                runState = SpeedtestRunState.RUNNING,
                phase = SpeedtestPhase.DOWNLOAD,
                progressPercent = 42,
                liveMbps = 184.0,
                currentRound = 0,
            ),
        )
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is SpeedtestUiState.Running)
        state as SpeedtestUiState.Running
        assertEquals(SpeedtestPhase.DOWNLOAD, state.phase)
        assertEquals(42, state.progressPercent)
        assertEquals(184.0, state.liveMbps, 0.001)
    }

    @Test
    fun `done snapshot with a real result maps to Done, never inventing a value`() = runTest {
        val engine = FakeSpeedtestEngine(SpeedtestSnapshot(runState = SpeedtestRunState.IDLE))
        val viewModel = SpeedtestViewModel(engine = engine, hasNetworkConnectivity = { true })
        val result = sampleResult()
        viewModel.startTest()
        dispatcher.scheduler.advanceUntilIdle()

        engine.publish(SpeedtestSnapshot(runState = SpeedtestRunState.DONE, phase = SpeedtestPhase.DONE, progressPercent = 100, result = result))
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is SpeedtestUiState.Done)
        assertEquals(result, (state as SpeedtestUiState.Done).result)
    }

    @Test
    fun `error snapshot surfaces the real error message instead of a generic one when present`() = runTest {
        val engine = FakeSpeedtestEngine(SpeedtestSnapshot(runState = SpeedtestRunState.IDLE))
        val viewModel = SpeedtestViewModel(engine = engine, hasNetworkConnectivity = { true })
        viewModel.startTest()
        dispatcher.scheduler.advanceUntilIdle()

        engine.publish(SpeedtestSnapshot(runState = SpeedtestRunState.ERROR, errorMessage = "download_failed:HttpStatus:429"))
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is SpeedtestUiState.Error)
        assertEquals("download_failed:HttpStatus:429", (state as SpeedtestUiState.Error).message)
    }

    @Test
    fun `cancelTest delegates to the engine`() = runTest {
        val engine = FakeSpeedtestEngine(SpeedtestSnapshot(runState = SpeedtestRunState.IDLE))
        val viewModel = SpeedtestViewModel(engine = engine, hasNetworkConnectivity = { true })

        viewModel.cancelTest()

        assertEquals(1, engine.cancelCallCount)
    }

    @Test
    fun `onCleared cancels the engine as a safety net`() = runTest {
        val engine = FakeSpeedtestEngine(SpeedtestSnapshot(runState = SpeedtestRunState.IDLE))
        val viewModel = SpeedtestViewModel(engine = engine, hasNetworkConnectivity = { true })

        val store = ViewModelStore()
        store.put("speedtest", viewModel)
        store.clear()

        assertEquals(1, engine.cancelCallCount)
    }
}
