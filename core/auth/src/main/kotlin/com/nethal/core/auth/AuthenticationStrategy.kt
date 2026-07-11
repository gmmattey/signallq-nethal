package com.nethal.core.auth

import java.io.IOException

/**
 * Contrato comum aos mecanismos de autenticação de Driver Family (ver
 * `docs/architecture/hal-layering-model.md` §5.4): buscar/validar credencial contra o equipamento e,
 * a partir da sessão resultante, produzir os headers autenticados usados em toda chamada de leitura
 * subsequente.
 *
 * [TSession] é o estado pós-login que cada implementação já mantém hoje internamente (cookies de
 * sessão do TP-Link C6, cookie `Authorization` do C20, `sid`/`lsid`/`lang` do Nokia) — a interface só
 * generaliza a forma comum já presente nos três clients (`login(username, password)` +
 * `fetchAuthenticated(...)`/headers de sessão), sem inventar um contrato novo.
 *
 * Deliberadamente **não** força um único formato de `login`/`fetchAuthenticated` como assinatura
 * exclusiva de cada implementação: os três clients (`TplinkAuthenticationClient`,
 * `TplinkC20AuthenticationClient`, `NokiaAuthenticationClient`) preservam suas próprias assinaturas
 * concretas (usadas pelos testes e pelos drivers existentes) e adicionalmente implementam esta
 * interface para expor [login] e [authenticatedHeaders] de forma uniforme.
 *
 * Por decisão explícita do Luiz (2026-07-06, ver §11 do doc de arquitetura), esta interface não é
 * usada para unificar os dois mecanismos RSA+AES (TP-Link C6 e Nokia) — cada um continua sua própria
 * implementação, sem lógica de criptografia/handshake compartilhada entre eles.
 */
interface AuthenticationStrategy<TSession> {
    /**
     * Executa o handshake/validação de credencial e devolve a sessão resultante. Lança em caso de
     * falha (cada implementação define seu próprio vocabulário de motivo de falha, ex.:
     * `TplinkLoginException`, `TplinkC20LoginException`, `NokiaLoginException`).
     */
    @Throws(IOException::class)
    fun login(username: String, password: String): TSession

    /**
     * Produz os headers/cookies autenticados a partir da sessão resultante de [login], usados em
     * toda chamada de leitura subsequente contra o equipamento.
     */
    fun authenticatedHeaders(session: TSession): Map<String, String>
}
