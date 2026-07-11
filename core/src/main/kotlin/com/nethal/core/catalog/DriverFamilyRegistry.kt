package com.nethal.core.catalog

import com.nethal.core.protocol.http.HttpTransport

/**
 * Lançada quando `profile.driverFamilyId` não tem nenhuma [DriverFamilyFactory] registrada.
 *
 * Tratado como erro de integridade de catálogo, não como ausência esperada: diferente de
 * `DriverRegistry.findProfile` (onde `null` é uma resposta legítima — "este vendor/modelo não está no
 * catálogo"), aqui o profile já foi resolvido e declara um `driverFamilyId` que deveria apontar para
 * uma Driver Family existente. Se isso falhar, é sinal de catálogo publicado com `driverFamilyId`
 * incorreto ou de uma Driver Family ainda não registrada no `core` — falha silenciosa (devolver `null`
 * e deixar o chamador decidir) esconderia esse bug até o Capability Engine tentar ler alguma
 * capability e não entender por que nada funciona. Falhar alto e cedo aqui é a opção mais segura.
 */
class UnknownDriverFamilyException(val driverFamilyId: String) :
    IllegalStateException("nenhuma DriverFamilyFactory registrada para driverFamilyId=\"$driverFamilyId\"")

/**
 * Resolve a [DriverFamily] correspondente a um [CompatibilityProfile], a partir de um mapa fixo
 * `driverFamilyId -> DriverFamilyFactory` montado uma única vez na inicialização do `core`
 * (`hal-layering-model.md` §8, passo 4 e §10, passo 6) — nunca via reflection ou scan dinâmico.
 *
 * Nenhuma factory real (TP-Link, Nokia) é registrada aqui: isso é o passo 4 do plano de refatoração,
 * quando `TplinkOntDriver`/`TplinkC20OntDriver`/`NokiaOntDriver` forem migrados para implementar
 * [DriverFamily] de verdade.
 */
class DriverFamilyRegistry(private val factories: Map<String, DriverFamilyFactory>) {

    constructor(factories: List<DriverFamilyFactory>) : this(factories.associateBy { it.familyId })

    /**
     * Resolve e instancia a [DriverFamily] para [profile], usando `profile.driverFamilyId` como
     * chave de busca.
     *
     * @throws UnknownDriverFamilyException se nenhuma factory estiver registrada para o
     *   `driverFamilyId` declarado no profile — ver motivo na doc da exceção.
     */
    fun resolve(profile: CompatibilityProfile, host: String, transport: HttpTransport): DriverFamily {
        val factory = factories[profile.driverFamilyId]
            ?: throw UnknownDriverFamilyException(profile.driverFamilyId)
        return factory.create(profile, host, transport)
    }

    /**
     * Todos os `driverFamilyId` com factory registrada. Usado por testes de integridade
     * (`DriverFamilyCatalogIntegrityTest`) para checar o invariante inverso: toda família
     * registrada deve ser referenciada por ao menos um profile do catálogo (issue #42).
     */
    fun registeredFamilyIds(): Set<String> = factories.keys
}
