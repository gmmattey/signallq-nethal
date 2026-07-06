package com.nethal.core.consent

import kotlinx.coroutines.flow.Flow

/**
 * Contrato de persistência de consentimento. Implementação concreta (DataStore Preferences)
 * fica no módulo app — o core não conhece Android.
 */
interface ConsentRepository {
    fun observeState(): Flow<ConsentState>
    suspend fun grant(scope: ConsentScope, grantedAtEpochMillis: Long)
    suspend fun revoke(scope: ConsentScope)
}
