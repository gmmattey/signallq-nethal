package com.nethal.core.consent

/**
 * Escopo de consentimento do usuário, conforme spec §10-11: consentimento por escopo,
 * nunca um único aceite genérico cobrindo leitura, escrita e reboot.
 */
enum class ConsentScope {
    NETWORK_AUTHORIZATION,
    READ_STATUS,
    WRITE_CONFIGURATION,
    REBOOT_DEVICE,
    TELEMETRY_BETA,
}
