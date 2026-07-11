package com.nethal.lab.data.telemetry

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nethal.core.telemetry.TelemetryDeviceId
import com.nethal.core.telemetry.TelemetryDeviceIdRepository
import kotlinx.coroutines.flow.first

val Context.telemetryDataStore: DataStore<Preferences> by preferencesDataStore(name = "nethal_telemetry")

private val DEVICE_ID_KEY = stringPreferencesKey("telemetry_device_id")

/**
 * Persistência local do `device_id` de telemetria — mesmo mecanismo (DataStore Preferences) e mesmo
 * espírito de `ConsentDataStoreRepository`/`ManualIdentificationDataStoreRepository`: `core:telemetry`
 * é JVM puro (não conhece Android/DataStore), então a implementação concreta fica aqui, no módulo
 * `app`. Gera o UUID uma única vez (`TelemetryDeviceId.generate`, nunca derivado de identificador real
 * de hardware) e reaproveita o mesmo valor em toda leitura seguinte — preferência dedicada
 * (`nethal_telemetry`), nunca compartilhada com `nethal_consent`/`nethal_manual_identification`.
 */
class TelemetryDeviceIdDataStoreRepository(
    private val dataStore: DataStore<Preferences>,
) : TelemetryDeviceIdRepository {

    override suspend fun getOrCreateDeviceId(): String {
        val existing = dataStore.data.first()[DEVICE_ID_KEY]
        if (existing != null) return existing

        val generated = TelemetryDeviceId.generate()
        dataStore.edit { preferences ->
            // não sobrescreve se outra corrotina já persistiu entre a leitura e este ponto —
            // sempre vence o primeiro valor gravado, garante um único device_id por instalação.
            if (preferences[DEVICE_ID_KEY] == null) {
                preferences[DEVICE_ID_KEY] = generated
            }
        }
        return dataStore.data.first()[DEVICE_ID_KEY] ?: generated
    }
}
