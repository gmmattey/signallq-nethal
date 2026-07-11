package com.nethal.core.model

/**
 * Shape de request/resultado de `RUN_NATIVE_DIAGNOSTIC_PING` (issue #24, Feat #23) — desenhado de
 * forma agnóstica de vendor, cobrindo só os campos comuns às duas plataformas já mapeadas por
 * evidência de campo (TP-Link `admin/diag?form=diag`: `type`/`ipaddr`/`count`/`pktsize`/`timeout`/
 * `ttl`; Nokia `diag.cgi?ping`: IP Version/Interface/alvo/contagem/tamanho de pacote/TTL). Nenhum
 * campo específico de um único vendor (ex.: seleção de interface LAN/WAN do Nokia) entra neste
 * contrato genérico — fica em parâmetro próprio do driver que precisar dele, se/quando a
 * implementação Nokia (issue #25, hoje pausada) avançar.
 *
 * Esta é a **capability de ação** (`/seguranca-nethal`, `/modelo-capacidades`) — não flui pelo
 * `DriverFamily.readCapability(id)`/`CapabilityEngine` genérico, que hoje é estritamente
 * `READ_ONLY` e sem parâmetro de request. Cada Driver Family que implementar esta capability expõe
 * um método dedicado (ver `TpLinkStokLuciDriverFamily.runNativeDiagnosticPing`).
 */
data class NativeDiagnosticPingRequest(
    /** IP ou hostname alvo do ping, disparado a partir do próprio equipamento. */
    val targetHost: String,
    val packetCount: Int = 4,
    val packetSizeBytes: Int? = null,
    val timeoutMillis: Int? = null,
    val ttl: Int? = null,
)

/**
 * Resultado do ping nativo. Todos os campos numéricos são nullable/best-effort: o texto de
 * resultado devolvido pelo equipamento não tem formato confirmado por evidência ao vivo até a
 * primeira execução real contra hardware — [rawResultText] preserva o texto original sempre, mesmo
 * quando o parsing estruturado não consegue extrair um campo específico.
 */
data class NativeDiagnosticPingResult(
    val packetsSent: Int? = null,
    val packetsReceived: Int? = null,
    val packetLossPercent: Double? = null,
    val roundTripTimesMillis: List<Long> = emptyList(),
    val averageRoundTripMillis: Double? = null,
    val timedOut: Boolean = false,
    val rawResultText: String? = null,
)
