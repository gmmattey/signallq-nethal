package com.nethal.lab.data.onboarding

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.onboardingDataStore: DataStore<Preferences> by preferencesDataStore(name = "nethal_onboarding")

private val ONBOARDING_COMPLETED_KEY = booleanPreferencesKey("onboarding_completed")

/**
 * Persistência local do marcador "onboarding já concluído" (issue #113).
 *
 * Decisão de onde vive (registrada em `docs/architecture/navigation-graph.md`): concreto no módulo
 * `:app` (camada `data`), **sem contrato em `core`**. Diferente de `ConsentRepository`
 * (`:core:consent`, consumido por `:feature:onboarding`) e `ThemeModeRepository`
 * (`:core:designsystem`, consumido por `:feature:settings`), este marcador tem **um único
 * consumidor** — o composition root da navegação (`NetHalNavHost`), que decide o `startDestination`.
 * Nenhuma `:feature:*` precisa lê-lo, então não há motivo para uma interface em `core` (sem
 * abstração prematura). Preferência dedicada (`nethal_onboarding`), nunca compartilhada com
 * `nethal_consent`/`nethal_theme`/`nethal_telemetry`.
 *
 * Não é dado sensível e nunca sai do dispositivo — só governa se o fluxo de boas-vindas roda de novo
 * no próximo launch (roda uma vez, na primeira instalação; ver AC da #113).
 */
class OnboardingCompletionDataStoreRepository(
    private val dataStore: DataStore<Preferences>,
) {

    /** `true` depois que o usuário conclui a última tela do onboarding (`1e`, resumo de permissões). */
    fun observeCompleted(): Flow<Boolean> =
        dataStore.data.map { preferences -> preferences[ONBOARDING_COMPLETED_KEY] ?: false }

    suspend fun markCompleted() {
        dataStore.edit { preferences ->
            preferences[ONBOARDING_COMPLETED_KEY] = true
        }
    }
}
