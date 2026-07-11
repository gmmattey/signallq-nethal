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
    /**
     * Threshold inferior de RX configurado pela operadora/fabricante no próprio transceptor óptico
     * (issue #28) — abaixo deste valor o firmware considera o sinal em falha. Extensão de
     * `READ_SIGNAL` (decisão registrada na issue: reaproveitar este modelo em vez de criar
     * capability nova, já que é o mesmo conceito — potência óptica — só com contexto de limite).
     */
    val rxPowerLowerThresholdDbm: Double? = null,
    /** Threshold superior de RX — acima deste valor o transceptor considera excesso de sinal (satura o receptor). */
    val rxPowerUpperThresholdDbm: Double? = null,
    /**
     * Margem entre o RX atual e [rxPowerLowerThresholdDbm] (`rxPowerDbm - rxPowerLowerThresholdDbm`),
     * já calculada pelo driver — não fica a cargo do app repetir essa conta (issue #28, critério de
     * aceite explícito). `null` se `rxPowerDbm` ou o threshold inferior não estiverem disponíveis.
     */
    val rxPowerMarginToLowerThresholdDb: Double? = null,
)
