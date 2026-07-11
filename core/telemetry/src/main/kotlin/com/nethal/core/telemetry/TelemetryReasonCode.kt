package com.nethal.core.telemetry

/**
 * Vocabulário fechado de motivo de falha/estado exportável por telemetria — nunca o texto livre
 * devolvido por um driver (`CapabilityReadResult.Failure.reason`, `DriverFamilyAuthResult.*.reason`).
 *
 * Motivo: mensagens de exceção/reason de driver frequentemente carregam dado bruto do equipamento
 * (IP, hostname, trecho de resposta HTTP) por serem escritas para debug local, não para exportação —
 * ver `docs/architecture/adr/0001-fronteira-sanitizacao-telemetria.md`. Repassar esse texto como está
 * para fora do device violaria a spec §8.9 mesmo sem nenhuma SSID/MAC explícito no payload. Este é o
 * ponto de montagem que aplica a fronteira: [classify] sempre devolve um valor deste enum fechado,
 * nunca a string de entrada.
 */
enum class TelemetryReasonCode {
    AUTH_REQUIRED,
    AUTH_INVALID,
    SESSION_EXPIRED,
    TIMEOUT,
    CONNECTION_REFUSED,
    UNSUPPORTED_CAPABILITY,
    PARSE_ERROR,
    UNKNOWN,
    ;

    companion object {
        /**
         * Classifica um motivo bruto de driver num código fechado, por heurística de palavra-chave.
         * Nunca devolve o [rawReason] original — só o rótulo. Qualquer motivo não reconhecido cai em
         * [UNKNOWN], falha segura (perde granularidade, nunca vaza dado).
         */
        fun classify(rawReason: String?): TelemetryReasonCode {
            if (rawReason.isNullOrBlank()) return UNKNOWN
            val normalized = rawReason.lowercase()
            return when {
                "sessionexpired" in normalized || "sessão expirada" in normalized || "sessao expirada" in normalized ->
                    SESSION_EXPIRED
                "credencial" in normalized || "invalid_credentials" in normalized || "senha" in normalized ||
                    "password" in normalized ->
                    AUTH_INVALID
                "autenticação" in normalized || "autenticacao" in normalized || "auth" in normalized ->
                    AUTH_REQUIRED
                "timeout" in normalized || "timed out" in normalized ->
                    TIMEOUT
                "connection refused" in normalized || "econnrefused" in normalized || "conexão recusada" in normalized ->
                    CONNECTION_REFUSED
                "não suportad" in normalized || "nao suportad" in normalized || "unsupported" in normalized ->
                    UNSUPPORTED_CAPABILITY
                "parse" in normalized || "json" in normalized || "malformed" in normalized ->
                    PARSE_ERROR
                else -> UNKNOWN
            }
        }
    }
}
