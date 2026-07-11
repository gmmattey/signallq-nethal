package com.nethal.lab.data.theme

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nethal.core.designsystem.theme.ThemeMode
import com.nethal.core.designsystem.theme.ThemeModeRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.themeDataStore: DataStore<Preferences> by preferencesDataStore(name = "nethal_theme")

private val THEME_MODE_KEY = stringPreferencesKey("theme_mode")

/**
 * Persistência local do modo de tema. Mesmo mecanismo (DataStore Preferences) e mesmo espírito de
 * `ConsentDataStoreRepository`/`TelemetryDeviceIdDataStoreRepository`: `core:designsystem` é agnóstico
 * de Android (só conhece o contrato [ThemeModeRepository]), então a implementação concreta fica aqui,
 * no módulo `app`. Preferência dedicada (`nethal_theme`), nunca compartilhada com `nethal_consent` ou
 * `nethal_telemetry`. Preferência de aparência não é dado sensível e nunca sai do dispositivo.
 *
 * Valor ausente ou inválido (ex.: enum renomeado numa versão futura) cai em [ThemeMode.SYSTEM] — o
 * padrão de produto, que respeita a preferência global do aparelho.
 */
class ThemeModeDataStoreRepository(
    private val dataStore: DataStore<Preferences>,
) : ThemeModeRepository {

    override fun observeThemeMode(): Flow<ThemeMode> =
        dataStore.data.map { preferences ->
            preferences[THEME_MODE_KEY]?.let { stored ->
                runCatching { ThemeMode.valueOf(stored) }.getOrNull()
            } ?: ThemeMode.SYSTEM
        }

    override suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { preferences ->
            preferences[THEME_MODE_KEY] = mode.name
        }
    }
}
