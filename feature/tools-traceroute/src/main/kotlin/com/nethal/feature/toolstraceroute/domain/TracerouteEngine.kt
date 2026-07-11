package com.nethal.feature.toolstraceroute.domain

import kotlinx.coroutines.flow.Flow

/**
 * Fonte de dados do traceroute (issue #102). **Contrato local a este módulo, não em `core:*`** —
 * ver decisão de arquitetura registrada no PR (mesmo raciocínio da #105/`LanDeviceScanner`): a
 * técnica real (shell-out para `/system/bin/ping`, incremento de TTL) é estruturalmente
 * Android-only e tem um único consumidor ([com.nethal.feature.toolstraceroute.ui.TracerouteViewModel]).
 * Criar um ponto de abstração de plataforma em `core/` (como `HttpTransport`) só se justifica
 * quando existe mais de um consumidor real — não é o caso aqui. Este contrato existe só para o
 * `ViewModel` ficar testável com um fake, sem depender de `ProcessBuilder`/dispositivo real.
 */
interface TracerouteEngine {

    /**
     * Checagem rápida (ping em `127.0.0.1`, loopback — nunca sai da máquina) de que o binário de
     * ping do sistema está acessível neste aparelho. Existe porque nem todo Android permite o
     * shell-out: alguns fabricantes restringem `exec` de binários do sistema (SELinux/OEM),
     * cenário diferente de "hop sem resposta" (esperado durante um traceroute normal). Chamada uma
     * vez, antes de habilitar a tela — resultado negativo aciona o componente de "recurso
     * indisponível" (`UnavailableResourceState`, critério de aceite da #92), nunca uma tentativa
     * silenciosa de rodar mesmo assim.
     */
    suspend fun checkAvailability(): TracerouteAvailability

    /**
     * Executa o traceroute, emitindo um [TracerouteHop] por vez conforme cada TTL é resolvido —
     * permite a tela atualizar a lista em tempo real, em vez de esperar todos os até
     * [DEFAULT_MAX_HOPS] hops (até 30s no pior caso, um TTL por segundo) para mostrar qualquer
     * coisa. Encerra a emissão (via `return@flow`, não `throw`) assim que um hop com
     * [TracerouteHop.Responded.isTarget] = true é emitido, ou ao atingir [maxHops].
     */
    fun trace(target: String, maxHops: Int = DEFAULT_MAX_HOPS): Flow<TracerouteHop>

    companion object {
        const val DEFAULT_MAX_HOPS = 30
    }
}

/** Resultado da checagem de [TracerouteEngine.checkAvailability]. */
sealed interface TracerouteAvailability {
    data object Available : TracerouteAvailability

    /** [reason] é mostrado ao usuário via `UnavailableFeatureDialog` — nunca um erro técnico cru. */
    data class Unavailable(val reason: String) : TracerouteAvailability
}
