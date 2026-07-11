package com.nethal.feature.toolsspeedtest.engine

import kotlin.random.Random

/**
 * Endpoint público (não-oficial) da Cloudflare usado pelo SignallQ para o mesmo propósito
 * (reaproveitamento confirmado na issue #98) — sem SDK/lib de terceiros da Cloudflare, só HTTP
 * puro. `_cb` (cache-bust) evita que qualquer CDN/proxy no caminho sirva uma resposta cacheada em
 * vez de gerar bytes de verdade a cada chamada.
 */
internal object CloudflareSpeedtestEndpoints {
    private const val BASE_URL = "https://speed.cloudflare.com"

    fun download(payloadBytes: Int): String = "$BASE_URL/__down?bytes=$payloadBytes&_cb=${cacheBust()}"
    fun upload(): String = "$BASE_URL/__up?_cb=${cacheBust()}"
    fun ping(): String = "$BASE_URL/__down?bytes=0&_cb=${cacheBust()}"

    private fun cacheBust(): String = "${System.currentTimeMillis()}_${Random.nextInt(10_000, 99_999)}"
}
