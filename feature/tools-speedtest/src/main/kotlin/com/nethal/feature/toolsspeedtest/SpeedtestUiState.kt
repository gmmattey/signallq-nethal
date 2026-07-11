package com.nethal.feature.toolsspeedtest

import com.nethal.core.model.SpeedtestPhase
import com.nethal.core.model.SpeedtestResult

/**
 * Estado da tela "Teste de velocidade" (issue #90, protótipos `4a`/`4b`). Sempre construído a
 * partir de [com.nethal.core.model.SpeedtestSnapshot] real, publicado pelo motor
 * ([com.nethal.feature.toolsspeedtest.engine.SpeedtestEngine]) — nunca um valor de exemplo
 * hardcoded como se fosse dado real (critério de aceite de #90).
 */
sealed interface SpeedtestUiState {

    /** Nenhum teste rodou ainda nesta instância de tela — mostra o convite a iniciar. */
    data object Idle : SpeedtestUiState

    /**
     * Sem conectividade de internet no aparelho agora (checagem real via `ConnectivityManager`,
     * não suposição) — usa o componente "Recurso indisponível" de `:feature:tools-common` em vez
     * de deixar o teste falhar silenciosamente ou mostrar dado zerado como se fosse medição.
     */
    data class NoConnectivity(val reason: String) : SpeedtestUiState

    data class Running(
        val phase: SpeedtestPhase,
        val progressPercent: Int,
        val liveMbps: Double,
        val currentRound: Int,
    ) : SpeedtestUiState

    data class Done(val result: SpeedtestResult) : SpeedtestUiState

    data class Error(val message: String) : SpeedtestUiState
}
