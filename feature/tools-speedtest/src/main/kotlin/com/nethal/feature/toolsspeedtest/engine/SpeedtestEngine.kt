package com.nethal.feature.toolsspeedtest.engine

import com.nethal.core.model.SpeedtestMode
import com.nethal.core.model.SpeedtestSnapshot
import kotlinx.coroutines.flow.StateFlow

/**
 * Motor de teste de velocidade (issue #98) — contrato independente da implementação de transporte
 * HTTP, para a ViewModel nunca depender de OkHttp diretamente.
 */
interface SpeedtestEngine {
    val snapshotFlow: StateFlow<SpeedtestSnapshot>

    /** Roda o teste no [mode] escolhido; publica progresso em [snapshotFlow] até chegar em `DONE`/`ERROR`. Idempotente enquanto já roda: nova chamada concorrente é ignorada. */
    suspend fun run(mode: SpeedtestMode)

    /** Cancela um teste em andamento; sem efeito se nenhum teste estiver rodando. */
    fun cancel()
}
