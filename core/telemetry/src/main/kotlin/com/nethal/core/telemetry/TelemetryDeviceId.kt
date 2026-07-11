package com.nethal.core.telemetry

import java.util.UUID

/**
 * Identificador de instalação do NetHAL — UUID v4 puro (`java.util.UUID`, `SecureRandom` por baixo),
 * nunca derivado de ANDROID_ID/IMEI/MAC/serial ou qualquer identificador real de hardware. Independente
 * e nunca compartilhado com o `device_id` do SignallQ (apps diferentes, mesmo espírito do que já é
 * praticado lá — ver `PreferenciasAppRepository.kt:474-481` no repo `linka-android`).
 */
object TelemetryDeviceId {
    fun generate(): String = UUID.randomUUID().toString()
}

/**
 * Contrato de persistência do device_id. `core:telemetry` é JVM puro (mesmo padrão de
 * `ConsentRepository`/`core:consent`) — a implementação real (DataStore Preferences, mesmo mecanismo
 * já usado por `ConsentDataStore`/`ManualIdentificationDataStore`) fica no módulo `app`, que é quem
 * conhece Android. Gera uma vez via [TelemetryDeviceId.generate] e persiste; chamadas subsequentes
 * devolvem sempre o mesmo valor.
 */
interface TelemetryDeviceIdRepository {
    suspend fun getOrCreateDeviceId(): String
}
