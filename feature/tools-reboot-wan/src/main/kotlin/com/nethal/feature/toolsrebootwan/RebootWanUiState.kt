package com.nethal.feature.toolsrebootwan

/**
 * Estado da tela "Reiniciar interface WAN" (issues #95/#103, protótipo `4h`). Diferente da maioria
 * das telas de leitura do NetHAL Lab (`WifiNetworkUiState`, `CapabilitiesUiState`), esta tela nunca
 * lê nada do equipamento antes de agir — a confirmação é o próprio primeiro passo, exibida assim
 * que uma sessão está disponível ([ConfirmationPending]).
 *
 * `REBOOT_DEVICE` (`/seguranca-nethal`) exige confirmação explícita do usuário **sem exceção, sem
 * atalho** — por isso não existe nenhum caminho neste sealed interface que pule
 * [ConfirmationPending] direto para [InProgress]; só [RebootWanViewModel.confirmReboot] (disparado
 * pelo toque explícito em "Reiniciar" do diálogo) faz essa transição.
 */
sealed interface RebootWanUiState {

    /**
     * Nenhuma sessão autenticada disponível para esta tela (`CapabilityEngine` nulo) — mesmo
     * significado de `WifiNetworkUiState.SessionUnavailable`. Sem sessão, não há como sequer
     * perguntar ao usuário se ele quer reiniciar (a ação exige o equipamento identificado/
     * autenticado), então esta tela nunca mostra o diálogo de confirmação neste estado.
     */
    data class SessionUnavailable(val reason: String) : RebootWanUiState

    /**
     * Sessão disponível, aguardando a decisão do usuário — o diálogo de confirmação (protótipo
     * `4h`) é exibido assim que a tela entra neste estado. Nenhuma chamada de rede foi feita ainda.
     */
    data object ConfirmationPending : RebootWanUiState

    /** Usuário confirmou — `CapabilityEngine.executeAction(REBOOT_DEVICE)` em andamento. */
    data object InProgress : RebootWanUiState

    /** O equipamento aceitou o comando de reinício (resposta HTTP bem-sucedida do write). */
    data object Success : RebootWanUiState

    /** Falha ao executar — motivo honesto do driver/Core, nunca inventado. */
    data class Failure(val reason: String) : RebootWanUiState
}
