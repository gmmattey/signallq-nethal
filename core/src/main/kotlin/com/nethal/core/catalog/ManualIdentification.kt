package com.nethal.core.catalog

import kotlinx.coroutines.flow.Flow

/**
 * Correção manual de identificação (Tela 3, spec §10 item 6 / §11). Usuário informa
 * fabricante/modelo/firmware quando a confiança do Fingerprint Engine é baixa ou está errada.
 * Nunca promove nada a `STABLE` automaticamente — vira candidato `USER_SUBMITTED`, só
 * persistido localmente nesta entrega (sem telemetria/upload real ainda).
 */
data class ManualIdentificationCandidate(
    val targetIp: String,
    val vendor: String,
    val model: String,
    val firmware: String?,
    val submittedAtEpochMillis: Long,
    val origin: ManualIdentificationOrigin = ManualIdentificationOrigin.USER_SUBMITTED,
)

enum class ManualIdentificationOrigin {
    USER_SUBMITTED,
}

/**
 * Persistência local das correções manuais. Implementação concreta (DataStore) fica no
 * módulo app — mesmo padrão de `ConsentRepository`. Nenhuma implementação envia isso a um
 * servidor: "enviar ao catálogo" nesta entrega é só registrar localmente e preparar o dado.
 */
interface ManualIdentificationRepository {
    fun observeCandidates(): Flow<List<ManualIdentificationCandidate>>
    suspend fun submit(candidate: ManualIdentificationCandidate)
}
