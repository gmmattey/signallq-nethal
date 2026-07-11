package com.nethal.feature.toolstraceroute.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nethal.feature.toolstraceroute.domain.TracerouteAvailability
import com.nethal.feature.toolstraceroute.domain.TracerouteEngine
import com.nethal.feature.toolstraceroute.domain.TracerouteHop
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Orquestra a tela Traceroute (issue #92) sobre o [TracerouteEngine] real (issue #102,
 * [com.nethal.feature.toolstraceroute.data.AndroidTracerouteEngine] em produção, fake nos
 * testes deste arquivo). Checa disponibilidade uma vez ao entrar na tela ([init]) — nunca deixa o
 * usuário tocar em "Executar" sem antes saber se o traceroute é possível neste aparelho.
 */
class TracerouteViewModel(
    private val engine: TracerouteEngine,
) : ViewModel() {

    private val _uiState = MutableStateFlow<TracerouteUiState>(TracerouteUiState.CheckingAvailability)
    val uiState: StateFlow<TracerouteUiState> = _uiState.asStateFlow()

    private var traceJob: Job? = null

    init {
        viewModelScope.launch {
            _uiState.value = when (val availability = engine.checkAvailability()) {
                TracerouteAvailability.Available -> TracerouteUiState.Ready(
                    target = DEFAULT_TARGET,
                    runState = TracerouteRunState.Empty,
                )
                is TracerouteAvailability.Unavailable -> TracerouteUiState.Unavailable(availability.reason)
            }
        }
    }

    fun onTargetChanged(newTarget: String) {
        val current = _uiState.value as? TracerouteUiState.Ready ?: return
        _uiState.value = current.copy(target = newTarget)
    }

    /** Dispara uma nova execução, cancelando qualquer uma em andamento (novo toque em "Executar" reinicia, não empilha). */
    fun execute() {
        val current = _uiState.value as? TracerouteUiState.Ready ?: return
        val target = current.target.trim()
        if (target.isBlank()) return

        traceJob?.cancel()
        traceJob = viewModelScope.launch {
            _uiState.value = current.copy(target = target, runState = TracerouteRunState.Running(emptyList()))

            val hops = mutableListOf<TracerouteHop>()
            var reachedTarget = false
            engine.trace(target).collect { hop ->
                hops.add(hop)
                if (hop is TracerouteHop.Responded && hop.isTarget) reachedTarget = true
                updateRunState(TracerouteRunState.Running(hops.toList()))
            }
            updateRunState(TracerouteRunState.Completed(hops.toList(), reachedTarget = reachedTarget))
        }
    }

    override fun onCleared() {
        traceJob?.cancel()
    }

    private fun updateRunState(runState: TracerouteRunState) {
        val current = _uiState.value as? TracerouteUiState.Ready ?: return
        _uiState.value = current.copy(runState = runState)
    }

    private companion object {
        /** Alvo padrão do campo (DNS público conhecido) — só um valor inicial editável, nunca um resultado. */
        const val DEFAULT_TARGET = "8.8.8.8"
    }
}
