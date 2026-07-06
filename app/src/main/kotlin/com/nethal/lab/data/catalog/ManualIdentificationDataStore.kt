package com.nethal.lab.data.catalog

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nethal.core.catalog.ManualIdentificationCandidate
import com.nethal.core.catalog.ManualIdentificationOrigin
import com.nethal.core.catalog.ManualIdentificationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val Context.manualIdentificationDataStore: DataStore<Preferences> by preferencesDataStore(name = "nethal_manual_identification")

private val CANDIDATES_KEY = stringSetPreferencesKey("manual_identification_candidates")

/**
 * Persistência local das correções manuais de identificação (Tela 3, SIG-320). Cada
 * candidato vira uma entrada JSON num `Set<String>` do DataStore — volume esperado é baixo
 * (uma correção por sessão de teste), não justifica um banco relacional nesta entrega.
 * Nunca envia isso a um servidor: não há telemetria real ainda, conforme escopo da Feat 3.
 */
class ManualIdentificationDataStoreRepository(
    private val dataStore: DataStore<Preferences>,
) : ManualIdentificationRepository {

    private val json = Json { ignoreUnknownKeys = true }

    override fun observeCandidates(): Flow<List<ManualIdentificationCandidate>> {
        return dataStore.data.map { preferences ->
            preferences[CANDIDATES_KEY].orEmpty()
                .mapNotNull { raw -> runCatching { json.decodeFromString(SerializableCandidate.serializer(), raw) }.getOrNull() }
                .map { it.toCandidate() }
                .sortedByDescending { it.submittedAtEpochMillis }
        }
    }

    override suspend fun submit(candidate: ManualIdentificationCandidate) {
        dataStore.edit { preferences ->
            val current = preferences[CANDIDATES_KEY].orEmpty()
            val serialized = json.encodeToString(SerializableCandidate.serializer(), SerializableCandidate.from(candidate))
            preferences[CANDIDATES_KEY] = current + serialized
        }
    }
}

@Serializable
private data class SerializableCandidate(
    val targetIp: String,
    val vendor: String,
    val model: String,
    val firmware: String?,
    val submittedAtEpochMillis: Long,
) {
    fun toCandidate() = ManualIdentificationCandidate(
        targetIp = targetIp,
        vendor = vendor,
        model = model,
        firmware = firmware,
        submittedAtEpochMillis = submittedAtEpochMillis,
        origin = ManualIdentificationOrigin.USER_SUBMITTED,
    )

    companion object {
        fun from(candidate: ManualIdentificationCandidate) = SerializableCandidate(
            targetIp = candidate.targetIp,
            vendor = candidate.vendor,
            model = candidate.model,
            firmware = candidate.firmware,
            submittedAtEpochMillis = candidate.submittedAtEpochMillis,
        )
    }
}
