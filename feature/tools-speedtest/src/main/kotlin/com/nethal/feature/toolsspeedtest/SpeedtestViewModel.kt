package com.nethal.feature.toolsspeedtest

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nethal.core.model.SpeedtestMode
import com.nethal.core.model.SpeedtestRunState
import com.nethal.core.model.SpeedtestSnapshot
import com.nethal.feature.toolsspeedtest.engine.SpeedtestEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Orquestra a tela "Teste de velocidade" (issue #90) sobre o motor real de #98. Nunca autentica,
 * nunca usa `CapabilityEngine`/`DriverFamily` — o teste roda direto do aparelho contra a internet,
 * independente do roteador pareado (ver KDoc de `com.nethal.core.model.SpeedtestResult`).
 *
 * A coleta de [SpeedtestEngine.snapshotFlow] só começa dentro de [startTest] (não no `init`) —
 * de propósito: um `StateFlow` reemite seu valor atual assim que alguém começa a coletar, e se essa
 * coleta começasse no `init`, a primeira emissão (o snapshot `IDLE` que o motor nunca rodou ainda)
 * sobrescreveria o guard de [SpeedtestUiState.NoConnectivity] assim que o dispatcher do coroutine
 * processasse a fila — apagando um estado que acabou de ser publicado por decisão explícita desta
 * classe. Sem teste rodando ainda, não há nada do motor para a tela observar mesmo.
 */
class SpeedtestViewModel(
    private val engine: SpeedtestEngine,
    private val hasNetworkConnectivity: () -> Boolean = { true },
) : ViewModel() {

    private val _uiState = MutableStateFlow<SpeedtestUiState>(SpeedtestUiState.Idle)
    val uiState: StateFlow<SpeedtestUiState> = _uiState.asStateFlow()

    private var collectJob: Job? = null

    /** Chamado pelo botão "Iniciar teste"/"Testar novamente" — [mode] fica `FAST` até a tela ganhar um seletor de modo (fora do escopo de #90). */
    fun startTest(mode: SpeedtestMode = SpeedtestMode.FAST) {
        if (!hasNetworkConnectivity()) {
            _uiState.value = SpeedtestUiState.NoConnectivity(NO_CONNECTIVITY_REASON)
            return
        }
        if (collectJob?.isActive != true) {
            collectJob = viewModelScope.launch {
                engine.snapshotFlow.collect { snapshot -> _uiState.value = snapshot.toUiState() }
            }
        }
        viewModelScope.launch { engine.run(mode) }
    }

    fun cancelTest() {
        engine.cancel()
    }

    override fun onCleared() {
        engine.cancel()
    }

    private fun SpeedtestSnapshot.toUiState(): SpeedtestUiState = when (runState) {
        SpeedtestRunState.IDLE -> SpeedtestUiState.Idle
        SpeedtestRunState.RUNNING -> SpeedtestUiState.Running(
            phase = phase,
            progressPercent = progressPercent,
            liveMbps = liveMbps,
            currentRound = currentRound,
        )
        SpeedtestRunState.DONE -> result?.let { SpeedtestUiState.Done(it) } ?: SpeedtestUiState.Idle
        SpeedtestRunState.ERROR -> SpeedtestUiState.Error(
            errorMessage ?: "Falha desconhecida ao testar a velocidade da conexão.",
        )
    }

    private companion object {
        const val NO_CONNECTIVITY_REASON =
            "Sem conexão à internet agora — conecte-se ao Wi-Fi ou aos dados móveis para medir a velocidade."
    }
}
