package com.nethal.core.protocol

/**
 * RFC 1918 — usada para decidir `possibleDoubleNat` (SIG-317) e como guarda de SSRF em
 * qualquer probe de rede do NetHAL (`UpnpIgdProbe`, `HttpFingerprintProbe`, `NokiaOntDriver`) e
 * na validação de IP manual informado pelo usuário na Tela 2b/2c (`app`). Pública (não
 * `internal`) porque precisa ser reaproveitada por vários módulos (discovery, fingerprint,
 * drivers, app) — é utilitário puro, sem risco em expor. Vive em `:core:protocol` (guard de
 * protocolo, ADR 0002) para que os módulos `:drivers:*` a usem sem depender de `:core:discovery`.
 *
 * Cobertura deliberadamente restrita a RFC 1918 (`10.0.0.0/8`, `172.16.0.0/12`,
 * `192.168.0.0/16`) — não inclui loopback (`127.0.0.0/8`) nem link-local (`169.254.0.0/16`),
 * apontado por Marisa na revisão de segurança do driver Nokia (PR #6). Isso é **mais
 * restritivo, não uma brecha**: hoje só torna o guard mais rígido, já que nenhum equipamento de
 * rede legítimo do NetHAL vive em loopback/link-local. Não "corrija" isso adicionando essas
 * faixas sem entender a implicação — o objetivo aqui é recusar o máximo possível por padrão
 * (falha segura), não maximizar cobertura de "endereço tecnicamente local".
 */
object PrivateIpRanges {

    fun isPrivate(ip: String): Boolean {
        val octets = ip.trim().split(".").mapNotNull { it.toIntOrNull() }
        if (octets.size != 4 || octets.any { it !in 0..255 }) return false

        val a = octets[0]
        val b = octets[1]
        return when {
            a == 10 -> true
            a == 172 && b in 16..31 -> true
            a == 192 && b == 168 -> true
            else -> false
        }
    }
}
