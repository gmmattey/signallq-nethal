package com.nethal.feature.toolstraceroute.ui

import com.nethal.feature.toolstraceroute.domain.TracerouteHop

/**
 * Estado da tela Traceroute (issue #92, protótipo `4d`). [CheckingAvailability] é sempre o
 * primeiro estado — a tela nunca mostra o campo de alvo/botão "Executar" antes de saber se o
 * traceroute é sequer possível neste aparelho.
 */
sealed interface TracerouteUiState {

    /** Checando se o binário de ping do sistema está acessível ([com.nethal.feature.toolstraceroute.domain.TracerouteEngine.checkAvailability]). */
    data object CheckingAvailability : TracerouteUiState

    /** Traceroute indisponível neste aparelho — a tela usa [com.nethal.feature.toolscommon.UnavailableResourceState] em vez de um campo mudo/quebrado (critério de aceite da #92). */
    data class Unavailable(val reason: String) : TracerouteUiState

    /** Pronto para uso: campo de alvo editável + execução em andamento ou concluída. */
    data class Ready(
        val target: String,
        val runState: TracerouteRunState,
    ) : TracerouteUiState {
        val isRunning: Boolean get() = runState is TracerouteRunState.Running
    }
}

/** Sub-estado da execução em si, dentro de [TracerouteUiState.Ready]. */
sealed interface TracerouteRunState {

    /** Nenhuma execução disparada ainda nesta composição da tela. */
    data object Empty : TracerouteRunState

    /** Hops chegando um a um — [hops] cresce a cada emissão do [com.nethal.feature.toolstraceroute.domain.TracerouteEngine], nunca é uma lista de exemplo hardcoded. */
    data class Running(val hops: List<TracerouteHop>) : TracerouteRunState

    data class Completed(val hops: List<TracerouteHop>, val reachedTarget: Boolean) : TracerouteRunState
}
