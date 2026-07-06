package com.nethal.lab.data.consent

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nethal.core.consent.ConsentRecord
import com.nethal.core.consent.ConsentRepository
import com.nethal.core.consent.ConsentScope
import com.nethal.core.consent.ConsentState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.consentDataStore: DataStore<Preferences> by preferencesDataStore(name = "nethal_consent")

/**
 * Persistência local de consentimento por escopo. Nunca envia estes dados a um servidor —
 * não há backend nesta entrega, e mesmo quando houver, consentimento de escrita/reboot é
 * decisão local do usuário, não telemetria.
 */
class ConsentDataStoreRepository(
    private val dataStore: DataStore<Preferences>,
) : ConsentRepository {

    override fun observeState(): Flow<ConsentState> {
        return dataStore.data.map { preferences ->
            val records = ConsentScope.entries.associateWith { scope ->
                ConsentRecord(
                    scope = scope,
                    granted = preferences[grantedKey(scope)] ?: false,
                    grantedAtEpochMillis = preferences[timestampKey(scope)],
                )
            }
            ConsentState(records)
        }
    }

    override suspend fun grant(scope: ConsentScope, grantedAtEpochMillis: Long) {
        dataStore.edit { preferences ->
            preferences[grantedKey(scope)] = true
            preferences[timestampKey(scope)] = grantedAtEpochMillis
        }
    }

    override suspend fun revoke(scope: ConsentScope) {
        dataStore.edit { preferences ->
            preferences[grantedKey(scope)] = false
            preferences.remove(timestampKey(scope))
        }
    }

    private fun grantedKey(scope: ConsentScope) =
        booleanPreferencesKey("consent_${scope.name}_granted")

    private fun timestampKey(scope: ConsentScope) =
        longPreferencesKey("consent_${scope.name}_granted_at")
}
