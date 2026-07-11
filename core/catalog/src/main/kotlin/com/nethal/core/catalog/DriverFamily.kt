package com.nethal.core.catalog

import com.nethal.core.model.Capability
import com.nethal.core.model.CapabilityId
import com.nethal.core.model.CapabilityPayload
import com.nethal.core.protocol.http.HttpTransport

/**
 * Resultado de leitura de uma capability por uma [DriverFamily]. Espelha o vocabulário já usado em
 * `Capability`/`CapabilityState` (`core/model/Capability.kt`) em vez de inventar um novo.
 *
 * [Success] carrega tanto a declaração de capability ([Capability], estado/confidence/reason) quanto
 * o dado real lido ([CapabilityPayload]) — até a issue #16 (Capability Engine com sessão real) este
 * tipo só existia em teoria, nunca tinha sido validado contra uma leitura real; `[payload]` foi
 * adicionado quando `TpLinkStokLuciDriverFamily` se tornou a primeira implementação real de
 * `readCapability(id)`, porque uma leitura sem dado nenhum (só "está disponível") não cumpre o que
 * `readCapability` promete.
 *
 * [SessionExpired] é distinto de [Failure]: sinaliza explicitamente que a sessão/token mantido pela
 * `DriverFamily` (ver [DriverFamily.authenticate]) não é mais válido — motivo comum o suficiente
 * entre protocolos (token com TTL, ex.: `stok` do TP-Link) para merecer um caso próprio em vez de
 * ficar escondido dentro de `Failure.reason` como texto livre. É o sinal que o Capability Engine usa
 * para decidir renovar a sessão automaticamente (`core/capability/CapabilityEngine.kt`) em vez de
 * simplesmente repassar a falha ao chamador.
 */
sealed interface CapabilityReadResult {
    data class Success(val capability: Capability, val payload: CapabilityPayload) : CapabilityReadResult
    data class Unavailable(val reason: String) : CapabilityReadResult
    data class Failure(val reason: String, val cause: Throwable? = null) : CapabilityReadResult
    data class SessionExpired(val reason: String) : CapabilityReadResult
}

/**
 * Resultado de [DriverFamily.authenticate] — mesmo espírito de granularidade de motivo de
 * [CapabilityReadResult], mas para o passo de autenticação em si (distinto de uma leitura de
 * capability).
 */
sealed interface DriverFamilyAuthResult {
    data object Success : DriverFamilyAuthResult
    data class InvalidCredentials(val reason: String) : DriverFamilyAuthResult
    data class Failure(val reason: String) : DriverFamilyAuthResult
}

/**
 * Toda a lógica de comunicação com o equipamento para uma plataforma tecnológica compartilhada
 * (ver `docs/architecture/hal-layering-model.md` §5.5) — o que hoje está mistura em
 * `TplinkOntDriver`/`TplinkC20OntDriver`/`NokiaOntDriver`, sem separação entre "protocolo/driver" e
 * "dado de modelo específico".
 *
 * Uma `DriverFamily` recebe o [CompatibilityProfile] correspondente como configuração (via
 * [DriverFamilyFactory.create]) e nunca tem endpoint, seção ou campo de modelo hardcoded no próprio
 * código — esse dado vive em `profile.driverConfig` (§5.6/§11.1 do doc de arquitetura).
 *
 * Só cobre leitura (`READ_ONLY`) nesta rodada: escrita (`SET_*`, `REBOOT_*`) entra no mesmo desenho
 * quando o produto avançar para essa fase, sempre gateada pelo Safety Guard (ver `/seguranca-nethal`)
 * — não faz parte do escopo deste passo.
 */
interface DriverFamily {
    /**
     * Lê o estado atual de uma capability específica no equipamento. Implementações decidem
     * internamente, a partir de `profile.driverConfig`, quais endpoints/seções consultar — nunca a
     * partir de `if (vendor == ...)`.
     *
     * Requer sessão autenticada quando a capability exige (a maioria) — chame [authenticate]
     * primeiro (diretamente, ou via `CapabilityEngine`, que já gerencia esse ciclo). Sem sessão
     * ativa, a resposta honesta é [CapabilityReadResult.Unavailable] com `reason` explicando o que
     * falta, nunca uma exceção nem um valor forjado.
     */
    suspend fun readCapability(id: CapabilityId): CapabilityReadResult

    /**
     * Autentica contra o equipamento e mantém a sessão resultante (token, cookie, chave de sessão —
     * o que for específico do protocolo) em memória, **dentro desta própria instância** de
     * [DriverFamily], para uso por chamadas subsequentes de [readCapability] sem exigir nova
     * credencial a cada leitura (ver `core/capability/CapabilityEngine.kt`, issue #16). Chamar de
     * novo substitui a sessão anterior por uma nova (relogin/renovação).
     *
     * A credencial (`username`/`password`) nunca deve ser retida por uma implementação além do
     * necessário para produzir a sessão — nunca persistida em disco, nunca logada, nunca exposta em
     * mensagem de exceção (`CLAUDE.md`, princípio "sem senha armazenada"; `/seguranca-nethal`).
     *
     * Implementação padrão honesta para Driver Families que ainda não implementam sessão real —
     * mesmo espírito de honestidade que já existia em `readCapability` antes desta issue (ver
     * `TpLinkLegacyCgiDriverFamily`/`TpLinkGdprCgiDriverFamily`/`TpLinkXdrDsDriverFamily`, que
     * continuam nesse estado até serem migradas).
     */
    suspend fun authenticate(username: String, password: String): DriverFamilyAuthResult =
        DriverFamilyAuthResult.Failure(
            reason = "Esta Driver Family ainda não implementa gerenciamento de sessão real (authenticate()).",
        )
}

/**
 * Fábrica de instâncias de [DriverFamily], uma por plataforma tecnológica (`driverFamilyId`).
 * Registrada uma única vez no [DriverFamilyRegistry], montado na inicialização do `core` — nunca via
 * reflection ou scan dinâmico (`hal-layering-model.md` §8, passo 4).
 */
interface DriverFamilyFactory {
    /** Chave estável usada para resolver esta factory a partir de `profile.driverFamilyId`. */
    val familyId: String

    /**
     * Constrói uma [DriverFamily] parametrizada por [profile] (fonte de `driverConfig` e demais
     * metadados de catálogo) e pelo [host]/[transport] já resolvidos pelo Discovery Engine.
     */
    fun create(profile: CompatibilityProfile, host: String, transport: HttpTransport): DriverFamily
}
