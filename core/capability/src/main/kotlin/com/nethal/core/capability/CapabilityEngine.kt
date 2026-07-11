package com.nethal.core.capability

import com.nethal.core.catalog.CapabilityActionResult
import com.nethal.core.catalog.CapabilityReadResult
import com.nethal.core.catalog.DriverFamily
import com.nethal.core.catalog.DriverFamilyAuthResult
import com.nethal.core.model.CapabilityId

/**
 * Resultado de uma tentativa de (re)autenticação do [CapabilityEngine] — usado tanto pelo caminho
 * interno de sessão lazy/renovação quanto por [CapabilityEngine.testCredentials], exposto para a
 * futura Tela 5 (autenticação, `docs/product/specification.md` §11) validar credenciais com feedback
 * imediato, sem precisar disfarçar isso de uma leitura de capability qualquer
 * (`/seguranca-nethal`: "sempre oferecer 'testar credenciais' antes de qualquer ação de escrita").
 */
sealed interface CapabilitySessionResult {
    data object Active : CapabilitySessionResult
    data class InvalidCredentials(val reason: String) : CapabilitySessionResult
    data class Failure(val reason: String) : CapabilitySessionResult
}

/**
 * Capability Engine (issue #16, `docs/architecture/hal-layering-model.md` §8 passo 5) — a peça que
 * faltava para `DriverFamily.readCapability(id)` funcionar de verdade sem que quem chama precise
 * gerenciar login a cada leitura.
 *
 * ## 1. Onde a sessão vive (decisão de arquitetura)
 *
 * Uma instância de [CapabilityEngine] envolve **uma única [DriverFamily] já resolvida** (via
 * `DriverFamilyRegistry.resolve(profile, host, transport)`) para um equipamento específico — ou
 * seja, 1 [CapabilityEngine] = 1 conexão ativa com 1 equipamento, mesmo ciclo de vida de
 * `RouterSession` na API conceitual do SDK (`docs/product/specification.md` §12).
 *
 * O material de sessão específico de protocolo (token `stok` do TP-Link, cookie `sysauth`, chave/IV
 * AES da sessão) **continua vivendo dentro da própria [DriverFamily]** (ex.: o client autenticado
 * cacheado internamente por `TpLinkStokLuciDriverFamily` depois de [DriverFamily.authenticate]),
 * nunca aqui — cada Driver Family já sabe cachear o que for específico do seu protocolo
 * (`TpLinkStokLuciAuthenticationClient` já fazia isso internamente antes desta peça existir, só não
 * tinha ninguém chamando `authenticate()` uma vez e reaproveitando a instância entre leituras). O
 * Capability Engine não entende esse material — ele só chama [DriverFamily.authenticate] quando
 * precisa (ver §3) e, depois, [DriverFamily.readCapability] quantas vezes for preciso, sem se
 * importar com o protocolo por trás.
 *
 * Esta classe, por sua vez, guarda **só a credencial crua** (`username`/`password`) recebida no
 * construtor, em memória, exclusivamente para poder autenticar na primeira leitura e reautenticar
 * automaticamente quando a Driver Family sinaliza sessão expirada
 * ([CapabilityReadResult.SessionExpired]) — nunca grava em disco, nunca loga, nunca serializa (ver
 * [InMemoryCredential.toString]). A credencial é descartada em [closeSession] e deve ser considerada
 * válida só enquanto o módulo/tela que abriu a sessão estiver em uso (mesma regra de
 * `/seguranca-nethal`: "expirar sessão ao fechar o módulo/app" — quem decide quando chamar
 * [closeSession] é o chamador, ex. o NetHAL Lab ao sair da tela de capabilities).
 *
 * ## 2. Por que a credencial não fica só dentro da DriverFamily
 *
 * Duas razões para o Capability Engine (e não cada `DriverFamily`) guardar a credencial crua e
 * decidir quando reautenticar:
 *
 * 1. Centraliza a política "quando/quantas vezes reautenticar automaticamente" num único lugar —
 *    sem isso, cada Driver Family reimplementaria essa decisão, o mesmo tipo de duplicação que
 *    `hal-layering-model.md` já eliminou para retry/backoff (`DriverRetryPolicy`).
 * 2. A regra de segurança "sem senha armazenada" fica mais fácil de auditar num único componente
 *    (`CapabilityEngine`) do que espalhada por N implementações de `DriverFamily` — qualquer revisão
 *    de segurança futura (Marisa) só precisa olhar este arquivo para confirmar que a credencial nunca
 *    é persistida, nunca é logada e é descartada em [closeSession].
 *
 * ## 3. Criação/renovação de sessão
 *
 * A sessão **não é aberta no construtor** — é criada de forma preguiçosa (lazy) na primeira chamada a
 * [readCapability] (ou explicitamente via [testCredentials]). Isso evita autenticar contra o
 * equipamento antes de o chamador realmente precisar de algum dado, e mantém [readCapability] como o
 * único ponto de entrada que o restante do NetHAL Lab precisa conhecer para ler qualquer capability.
 *
 * Uma leitura que devolve [CapabilityReadResult.SessionExpired] dispara **uma única** tentativa
 * automática de reautenticação com a credencial ainda em memória, seguida de uma única nova
 * tentativa de leitura — sem retry agressivo (mesmo raciocínio conservador do resto do NetHAL: uma
 * sessão que expira de novo logo em seguida é sinal de problema real — credencial revogada, equipamento
 * fora do ar — não de instabilidade passageira). Falha na renovação encerra a sessão local
 * ([isSessionActive] volta a `false`) e devolve um motivo explícito — nunca fica tentando
 * indefinidamente.
 *
 * Escopo original: só leitura (`READ_ONLY`). A partir da issue #103, [executeAction] cobre também
 * capability de ação (escrita) — mesma política de sessão lazy/renovação de [readCapability],
 * reaproveitada em vez de duplicada (`DriverFamily.executeAction`, com o mesmo default honesto
 * `Unavailable` de [DriverFamily.readCapability] para quem não implementa). Quem decide **quais**
 * ações cada equipamento suporta continua sendo a Driver Family concreta, nunca esta classe.
 */
class CapabilityEngine(
    private val driverFamily: DriverFamily,
    username: String,
    password: String,
) {

    private var credential: InMemoryCredential? = InMemoryCredential(username, password)

    /** `true` só depois de uma autenticação bem-sucedida (lazy, via [readCapability]/[testCredentials]) e antes de [closeSession]/falha de renovação. */
    var isSessionActive: Boolean = false
        private set

    /**
     * Autentica (ou reautentica) agora, com a credencial em memória desta instância, e devolve o
     * resultado imediatamente — para a UI oferecer "testar credenciais" com feedback rápido, sem
     * disfarçar isso de leitura de capability (`/seguranca-nethal`).
     */
    suspend fun testCredentials(): CapabilitySessionResult = ensureSession()

    /**
     * Lê uma capability, gerenciando sessão automaticamente: autentica na primeira chamada (lazy),
     * reaproveita a sessão em chamadas seguintes, e renova uma única vez se a Driver Family sinalizar
     * [CapabilityReadResult.SessionExpired].
     */
    suspend fun readCapability(id: CapabilityId): CapabilityReadResult {
        if (!isSessionActive) {
            val sessionResult = ensureSession()
            if (sessionResult !is CapabilitySessionResult.Active) {
                return CapabilityReadResult.Unavailable(
                    reason = "Não foi possível autenticar para ler $id (${sessionResult.describe()}).",
                )
            }
        }

        val firstAttempt = driverFamily.readCapability(id)
        if (firstAttempt !is CapabilityReadResult.SessionExpired) return firstAttempt

        val renewal = ensureSession()
        if (renewal !is CapabilitySessionResult.Active) {
            return CapabilityReadResult.Unavailable(
                reason = "Sessão expirou ao ler $id e a renovação automática falhou (${renewal.describe()}) " +
                    "— chame testCredentials()/readCapability novamente para reabrir a sessão.",
            )
        }

        return driverFamily.readCapability(id)
    }

    /**
     * Executa uma capability de ação (escrita) — `REBOOT_DEVICE`, `SET_*` etc. (issue #103). Mesma
     * política de sessão de [readCapability] (autentica lazy na primeira chamada, renova uma única
     * vez em [CapabilityActionResult.SessionExpired]) — nenhuma duplicação de lógica de sessão entre
     * os dois métodos, só o tipo de retorno e o método delegado em [DriverFamily] mudam.
     *
     * **Não decide, nem aqui nem em nenhum outro ponto do Core, quais equipamentos/ids são
     * seguros de executar** — essa decisão já está tomada na Driver Family concreta (só quem
     * implementa [DriverFamily.executeAction] para um `id` específico permite a execução; as demais
     * caem no default `Unavailable`). Confirmação explícita do usuário antes de chamar este método
     * é responsabilidade de quem chama (UI) — este método nunca pergunta, só executa
     * (`/seguranca-nethal`: a confirmação é responsabilidade da camada de apresentação, não do SDK).
     */
    suspend fun executeAction(id: CapabilityId): CapabilityActionResult {
        if (!isSessionActive) {
            val sessionResult = ensureSession()
            if (sessionResult !is CapabilitySessionResult.Active) {
                return CapabilityActionResult.Unavailable(
                    reason = "Não foi possível autenticar para executar $id (${sessionResult.describe()}).",
                )
            }
        }

        val firstAttempt = driverFamily.executeAction(id)
        if (firstAttempt !is CapabilityActionResult.SessionExpired) return firstAttempt

        val renewal = ensureSession()
        if (renewal !is CapabilitySessionResult.Active) {
            return CapabilityActionResult.Unavailable(
                reason = "Sessão expirou ao executar $id e a renovação automática falhou (${renewal.describe()}) " +
                    "— chame testCredentials()/executeAction novamente para reabrir a sessão.",
            )
        }

        return driverFamily.executeAction(id)
    }

    /** Encerra a sessão local e descarta a credencial em memória — chamar ao fechar o módulo/tela. */
    fun closeSession() {
        credential = null
        isSessionActive = false
    }

    /** Sempre reautentica de verdade (nunca decide "já autenticado, pula") — usado tanto para abrir quanto para renovar a sessão. */
    private suspend fun ensureSession(): CapabilitySessionResult {
        val activeCredential = credential
            ?: return CapabilitySessionResult.Failure("sessão encerrada — nenhuma credencial em memória, construa um novo CapabilityEngine")

        return when (val result = driverFamily.authenticate(activeCredential.username, activeCredential.password)) {
            is DriverFamilyAuthResult.Success -> {
                isSessionActive = true
                CapabilitySessionResult.Active
            }
            is DriverFamilyAuthResult.InvalidCredentials -> {
                isSessionActive = false
                CapabilitySessionResult.InvalidCredentials(result.reason)
            }
            is DriverFamilyAuthResult.Failure -> {
                isSessionActive = false
                CapabilitySessionResult.Failure(result.reason)
            }
        }
    }

    private fun CapabilitySessionResult.describe(): String = when (this) {
        is CapabilitySessionResult.Active -> "sessão ativa"
        is CapabilitySessionResult.InvalidCredentials -> "credenciais inválidas: $reason"
        is CapabilitySessionResult.Failure -> "falha ao autenticar: $reason"
    }

    /**
     * Credencial em memória, exclusiva desta instância de [CapabilityEngine]. [toString] nunca expõe
     * [password] — garante que um `println`/log/crash-report acidental de uma instância desta classe
     * (ex.: debugger, `data class` default) nunca vaza a senha do roteador.
     */
    private data class InMemoryCredential(val username: String, val password: String) {
        override fun toString(): String = "InMemoryCredential(username=$username, password=***)"
    }
}
