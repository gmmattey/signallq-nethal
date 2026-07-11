package com.nethal.core.model

/**
 * Status de sinal — cobre a capability `READ_SIGNAL`. Modelado a partir do primeiro caso real
 * (óptica GPON, Nokia G-1425G-B/`nokia-ont-gpon-driver`): potência óptica RX/TX, temperatura do
 * transceptor e tensão/corrente do laser. Campos nullable porque nem toda leitura real de sinal
 * preenche as mesmas métricas (ex.: um futuro driver de RSSI Wi-Fi teria só parte destes campos,
 * ou nenhum) — cresce por união de campos opcionais, não por caso de payload por fabricante, mesmo
 * espírito de `DeviceInfo`/`WifiRadio` (nenhum `if (vendor == ...)`).
 */
data class SignalStatus(
    val rxPowerDbm: Double? = null,
    val txPowerDbm: Double? = null,
    val transceiverTemperatureCelsius: Double? = null,
    val supplyVoltageVolts: Double? = null,
    val laserCurrentMilliAmps: Double? = null,
)
