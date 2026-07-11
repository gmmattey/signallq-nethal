package com.nethal.feature.toolsspeedtest

import com.nethal.core.model.SpeedtestMode
import com.nethal.core.model.SpeedtestSnapshot
import com.nethal.feature.toolsspeedtest.engine.SpeedtestEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeSpeedtestEngine(initial: SpeedtestSnapshot) : SpeedtestEngine {
    private val mutableSnapshotFlow = MutableStateFlow(initial)
    override val snapshotFlow: StateFlow<SpeedtestSnapshot> = mutableSnapshotFlow.asStateFlow()

    var runCallCount = 0
        private set
    var lastModeRequested: SpeedtestMode? = null
    var cancelCallCount = 0
        private set

    /** Snapshot que [run] publica assim que chamado — simula o motor real avançando de fase. */
    var snapshotToPublishOnRun: SpeedtestSnapshot? = null

    override suspend fun run(mode: SpeedtestMode) {
        runCallCount++
        lastModeRequested = mode
        snapshotToPublishOnRun?.let { mutableSnapshotFlow.value = it }
    }

    override fun cancel() {
        cancelCallCount++
    }

    fun publish(snapshot: SpeedtestSnapshot) {
        mutableSnapshotFlow.value = snapshot
    }
}
