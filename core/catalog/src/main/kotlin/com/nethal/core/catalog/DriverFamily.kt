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
 * Resultado de [DriverFamily.executeAction] (issue #103) — mesmo espírito de granularidade de
 * [CapabilityReadResult], mas para uma capability de **ação** (o equipamento muda de estado ao
 * executar, ex. `REBOOT_DEVICE`), distinta de uma leitura passiva. Mesmos 4 casos de
 * [CapabilityReadResult] por simetria (inclusive [SessionExpired], para o mesmo mecanismo de
 * renovação automática de sessão do [com.nethal.core.capability.CapabilityEngine] funcionar sem
 * duplicar política) — sem payload de dado lido, já que uma ação não devolve um snapshot de
 * capability.
 */
sealed interface CapabilityActionResult {
    data class Success(val capability: Capability) : CapabilityActionResult
    data class Unavailable(val reason: String) : CapabilityActionResult
    data class Failure(val reason: String, val cause: Throwable? = null) : CapabilityActionResult
    data class SessionExpired(val reason: String) : CapabilityActionResult
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
 * Cobria só leitura (`READ_ONLY`) originalmente — a partir da issue #103, [executeAction] cobre
 * também capability de **ação** (escrita, `REBOOT_DEVICE` primeiro; `SET_*` no mesmo desenho quando
 * o produto avançar para essas capabilities), sempre com confirmação explícita do usuário
 * responsabilidade da UI que chama, nunca deste componente (`/seguranca-nethal`).
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

    /**
     * Executa uma capability de **ação** (escrita) declarada — `REBOOT_DEVICE`, `SET_*`, etc.
     * (issue #103). Implementação padrão honesta: `Unavailable` para toda Driver Family que ainda
     * não implementa nenhuma ação — só quem realmente suporta uma ação específica sobrescreve este
     * método (ex. [com.nethal.core.driver.family.tplink.stokluci.TpLinkStokLuciDriverFamily] para
     * `REBOOT_DEVICE`). Restringir uma ação a um único driver é, portanto, decisão de cada Driver
     * Family concreta (qual `id` ela reconhece), nunca um `if (vendor == ...)` em quem chama —
     * mesmo raciocínio de [readCapability]/`CapabilityId` (`/modelo-capacidades`).
     *
     * Requer sessão autenticada, mesmo padrão de [readCapability] — sem sessão ativa, resposta
     * honesta é [CapabilityActionResult.Unavailable], nunca uma execução real. Chamar via
     * [com.nethal.core.capability.CapabilityEngine.executeAction], que gerencia sessão/renovação
     * automaticamente, é o caminho normal — chamar este método direto sem autenticar antes só é
     * seguro para os próprios testes desta Driver Family.
     */
    suspend fun executeAction(id: CapabilityId): CapabilityActionResult =
        CapabilityActionResult.Unavailable(
            reason = "Esta Driver Family não implementa nenhuma capability de ação nesta rodada.",
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
