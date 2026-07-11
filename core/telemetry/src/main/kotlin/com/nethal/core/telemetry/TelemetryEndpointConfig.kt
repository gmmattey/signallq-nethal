package com.nethal.core.telemetry

/**
 * Configuração do endpoint de ingest `signallq-admin-worker` (rotas `/ingest/nethal/...`).
 *
 * Vazio por padrão de propósito: o endpoint real ainda não existe do lado do SignallQ — depende de
 * `linka-android#886` (migration `013_nethal_telemetry.sql`, rotas `/ingest/nethal/...` e a chave
 * `NETHAL_INGEST_KEY`) fechar antes de qualquer URL real ser hardcoded aqui. Enquanto
 * [isConfigured] for `false`, [HttpTelemetryCollector] vira no-op (mesmo padrão "ignorado" do
 * `AdminIngestRepository` do SignallQ quando `baseUrl`/`ingestKey` estão em branco).
 *
 * Quando `linka-android#886` fechar, quem injeta o valor real é o módulo `app` (ex.: via
 * `BuildConfig`), nunca um literal aqui.
 */
data class TelemetryEndpointConfig(
    val baseUrl: String = "",
    val ingestKey: String = "",
) {
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && ingestKey.isNotBlank()
}
