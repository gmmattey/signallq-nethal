package com.nethal.feature.toolsrebootwan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nethal.core.capability.CapabilityEngine
import com.nethal.core.catalog.CapabilityActionResult
import com.nethal.core.model.CapabilityId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Orquestra a tela "Reiniciar interface WAN" (issues #95/#103) — usa a MESMA sessão
 * (`CapabilityEngine`) que a tela recebe já resolvida, mesmo padrão de `WifiNetworkViewModel`.
 *
 * Nunca chama [CapabilityEngine.executeAction] sozinho: a única porta de entrada é
 * [confirmReboot], disparado exclusivamente pelo toque explícito em "Reiniciar" do diálogo de
 * confirmação (`/seguranca-nethal` — confirmação explícita do usuário, sem exceção, sem atalho
 * silencioso). [cancel] existe só para deixar a intenção de "usuário cancelou" explícita no
 * chamador (a tela navega de volta) — nunca toca em [capabilityEngine].
 */
class RebootWanViewModel(
    private val capabilityEngine: CapabilityEngine?,
) : ViewModel() {

    private val _uiState = MutableStateFlow(initialState())
    val uiState: StateFlow<RebootWanUiState> = _uiState.asStateFlow()

    private fun initialState(): RebootWanUiState {
        val engine = capabilityEngine
        return if (engine == null) {
            RebootWanUiState.SessionUnavailable(
                reason = "Nenhuma sessão autenticada chegou até esta tela. Volte e conecte-se a um " +
                    "equipamento antes de reiniciar a interface WAN.",
            )
        } else {
            RebootWanUiState.ConfirmationPending
        }
    }

    /**
     * Único caminho de execução real — chamar apenas em resposta ao toque em "Reiniciar" do
     * diálogo de confirmação, nunca automaticamente. Idempotente contra toque duplo: só age se o
     * estado atual for [RebootWanUiState.ConfirmationPending] (evita disparar dois reboots reais se
     * o usuário tocar duas vezes antes do primeiro `InProgress` renderizar).
     */
    fun confirmReboot() {
        val engine = capabilityEngine ?: return
        if (_uiState.value !is RebootWanUiState.ConfirmationPending) return

        _uiState.value = RebootWanUiState.InProgress
        viewModelScope.launch {
            val result = engine.executeAction(CapabilityId.REBOOT_DEVICE)
            _uiState.value = result.toUiState()
        }
    }

    /**
     * Usuário cancelou explicitamente — nunca chama [CapabilityEngine.executeAction]. Existe para o
     * chamador (tela) ter um ponto único a invocar antes de navegar de volta; o estado em si não
     * precisa mudar (a tela sai de composição de qualquer forma).
     */
    fun cancel() {
        // Intencionalmente vazio - ver KDoc da classe. Mantido como método nomeado (em vez de a UI
        // chamar só onCancelled diretamente) para deixar auditável, no code review, que "cancelar"
        // é um caminho que nunca toca no CapabilityEngine.
    }

    /** Encerra a sessão em uso — chamar de `DisposableEffect`/`onDispose` da tela ao sair de composição, mesmo padrão de `WifiNetworkViewModel.closeSession`. */
    fun closeSession() {
        capabilityEngine?.closeSession()
    }
}

private fun CapabilityActionResult.toUiState(): RebootWanUiState = when (this) {
    is CapabilityActionResult.Success -> RebootWanUiState.Success
    is CapabilityActionResult.Unavailable -> RebootWanUiState.Failure(reason)
    is CapabilityActionResult.Failure -> RebootWanUiState.Failure("Falha ao reiniciar o equipamento: $reason")
    is CapabilityActionResult.SessionExpired -> RebootWanUiState.Failure("Sessão expirou ao tentar reiniciar: $reason")
}

class RebootWanViewModelFactory(
    private val capabilityEngine: CapabilityEngine?,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(RebootWanViewModel::class.java)) {
            "RebootWanViewModelFactory só constrói RebootWanViewModel, recebido: $modelClass"
        }
        return RebootWanViewModel(capabilityEngine) as T
    }
}
