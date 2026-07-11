package com.nethal.feature.settings

import com.nethal.core.designsystem.theme.ThemeMode
import com.nethal.core.designsystem.theme.ThemeModeRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Fake in-memory de [ThemeModeRepository] para testes do `SettingsViewModel`. Guarda o modo num
 * `StateFlow` — o mesmo valor sobrevive a novas leituras, simulando a persistência do DataStore real.
 */
class FakeThemeModeRepository(
    initial: ThemeMode = ThemeMode.SYSTEM,
) : ThemeModeRepository {

    private val state = MutableStateFlow(initial)

    override fun observeThemeMode(): Flow<ThemeMode> = state

    override suspend fun setThemeMode(mode: ThemeMode) {
        state.value = mode
    }

    fun current(): ThemeMode = state.value
}
