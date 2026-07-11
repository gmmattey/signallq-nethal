package com.nethal.core.model

/**
 * Modelo de domínio de DNS Lookup (issues #93/#101) — ferramenta local do NetHAL Lab, **não** uma
 * capability de equipamento. A resolução acontece a partir do próprio celular, contra um resolvedor
 * DNS público (DNS-over-HTTPS), nunca através de um `DriverFamily`/`CapabilityEngine` de roteador
 * pareado.
 *
 * Decisão de arquitetura registrada aqui (PR #93/#101): **não entra no vocabulário de
 * `CapabilityId`** (`/modelo-capacidades`). Aquele vocabulário é estritamente para o que um driver
 * de fabricante declara suportar sobre o equipamento pareado (`READ_WIFI_STATUS` etc.), sempre atrás
 * de sessão administrativa autenticada contra aquele equipamento específico. DNS Lookup não tem
 * driver, não tem fabricante, não tem sessão de equipamento — é uma consulta de rede pública,
 * disponível independente de qualquer equipamento pareado (mesma categoria arquitetural que Ping
 * TCP/Traceroute/Verificação de porta, issues #99/#100/#102, todas fora do fluxo
 * `DriverFamily.readCapability`). Só o *shape* de domínio mora aqui (`:core:model`, "modelos de
 * domínio compartilhados", ADR 0002); a implementação de rede (`:feature:tools-dns`) decide como
 * preenchê-lo.
 */
enum class DnsRecordType(val wireTypeCode: Int, val label: String) {
    A(wireTypeCode = 1, label = "Tipo A"),
    AAAA(wireTypeCode = 28, label = "Tipo AAAA"),
}

/** Registro(s) resolvido(s) para um [DnsRecordType] — [values] vazio significa NODATA (sem esse tipo de registro), não erro. */
data class DnsRecordAnswer(
    val type: DnsRecordType,
    val values: List<String>,
)

/** Resultado bem-sucedido de uma consulta DNS Lookup — nunca construído com dado de exemplo, só com resposta real do resolvedor. */
data class DnsLookupResult(
    val hostname: String,
    val answers: List<DnsRecordAnswer>,
    val serverLabel: String,
    val elapsedMillis: Long,
)

/** Resultado de uma tentativa de DNS Lookup — [Failure] cobre tanto falha de rede quanto NXDOMAIN honesto, nunca disfarçado de sucesso vazio. */
sealed interface DnsLookupOutcome {
    data class Success(val result: DnsLookupResult) : DnsLookupOutcome
    data class Failure(val hostname: String, val reason: String) : DnsLookupOutcome
}
