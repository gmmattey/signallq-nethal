package com.nethal.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nethal.core.consent.ConsentRepository
import com.nethal.core.consent.ConsentScope
import com.nethal.core.designsystem.theme.ThemeMode
import com.nethal.core.designsystem.theme.ThemeModeRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Movido de `com.nethal.lab.ui.settings` (issue #85, ADR 0002 Fase 2) — mesmo comportamento,
 * só de módulo novo. O opt-in de `TELEMETRY_BETA` em si é apresentado no onboarding "Notificações"
 * (#71, decisão #66); aqui só a saída (`leaveBetaProgram`), referenciando o mesmo
 * `ConsentRepository` — não duplica fonte da verdade.
 *
 * Também expõe o modo de tema (issue #132) para o seletor Claro/Escuro/Sistema. A escrita
 * (`setThemeMode`) só persiste — quem recompõe o tema é o composition root em `:app`, observando o
 * mesmo `ThemeModeRepository`; este ViewModel não guarda estado de tema além do que o repositório diz.
 */
class SettingsViewModel(
    private val consentRepository: ConsentRepository,
    private val themeModeRepository: ThemeModeRepository,
) : ViewModel() {

    val isBetaProgramActive: StateFlow<Boolean> = consentRepository.observeState()
        .map { it.isGranted(ConsentScope.TELEMETRY_BETA) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val themeMode: StateFlow<ThemeMode> = themeModeRepository.observeThemeMode()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeMode.SYSTEM)

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            themeModeRepository.setThemeMode(mode)
        }
    }

    /**
     * Opt-out apenas impede novo envio de telemetria (spec §10 "Saída"). Não promete,
     * e não pode prometer, remoção de relatórios já enviados — são anônimos.
     */
    fun leaveBetaProgram() {
        viewModelScope.launch {
            consentRepository.revoke(ConsentScope.TELEMETRY_BETA)
        }
    }
}
