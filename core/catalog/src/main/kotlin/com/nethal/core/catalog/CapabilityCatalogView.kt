package com.nethal.core.catalog

import com.nethal.core.model.Capability
import com.nethal.core.model.CapabilityId
import com.nethal.core.model.CapabilityState

/**
 * Projeta o que o catálogo (dado estático, `profile.capabilities[]`) já declara sobre um
 * equipamento para o vocabulário público de [Capability] — usado pela Tela 4 (spec §11) para
 * mostrar ao usuário o que esperar de capabilities ANTES de autenticar. A leitura ao vivo via
 * `CapabilityEngine.readCapability` (pós-login, Tela 6) é uma fonte diferente e mais confiável;
 * esta projeção nunca chama rede nem abre sessão — é síncrona, pura e não sobrescreve nada.
 */
object CapabilityCatalogView {

    /**
     * Confiança atribuída a toda capability declarada de um profile: reaproveita
     * `confidenceScoreOverall` do próprio [CompatibilityProfile] — o catálogo não guarda
     * confiança por capability individual ([CatalogCapabilityEntry] só tem `id`/`state`/`reason`),
     * então usar o score do profile inteiro é mais honesto do que inventar um valor por item.
     */
    fun declaredCapabilities(profile: CompatibilityProfile): List<Capability> =
        profile.capabilities.mapNotNull { entry -> entry.toDeclaredCapabilityOrNull(profile.confidenceScoreOverall) }

    /**
     * Decisões de honestidade sobre dado de catálogo malformado (issue #43):
     *
     * 1. `id` sem correspondência em [CapabilityId] (typo no manifesto, capability removida do
     *    vocabulário etc.): a entrada é descartada (retorna `null`). Não existe
     *    `CapabilityId.UNKNOWN` para representar "capability que o NetHAL não reconhece" —
     *    inventar um valor destino quebraria a garantia de tipo do enum para todo o resto do
     *    código que já consome `CapabilityId`. O catálogo é dado estático versionado: um `id` não
     *    reconhecido é bug do próprio manifesto a corrigir na fonte (idealmente pego por
     *    validação de schema em CI), não algo que a Tela 4 precisa exibir de alguma forma em
     *    runtime.
     * 2. `state` sem correspondência em [CapabilityState]: mapeado para `CapabilityState.UNKNOWN`
     *    (o enum já tem exatamente esse valor para "não sabemos o estado real"), preservando o
     *    `reason` original do catálogo quando ele existir.
     * 3. Regra da spec §13 — `reason` obrigatório para todo estado != `AVAILABLE`: se o catálogo
     *    violar isso (entrada não-`AVAILABLE` sem `reason`), a projeção não descarta a capability
     *    nem inventa uma explicação plausível sobre o equipamento — sintetiza um `reason` que
     *    denuncia o próprio problema de dado, para o gap ficar visível em vez de mascarado.
     */
    private fun CatalogCapabilityEntry.toDeclaredCapabilityOrNull(declaredConfidence: Double): Capability? {
        val capabilityId = CapabilityId.entries.find { it.name == id } ?: return null

        val parsedState = CapabilityState.entries.find { it.name == state }
        val resolvedState = parsedState ?: CapabilityState.UNKNOWN

        val resolvedReason = when {
            reason != null -> reason
            parsedState == null ->
                "Catálogo declara estado \"$state\" para $id, que não existe no vocabulário atual " +
                    "de CapabilityState — tratado como UNKNOWN até o manifesto ser corrigido."
            resolvedState != CapabilityState.AVAILABLE ->
                "Dado de catálogo malformado: $id está declarado como $resolvedState sem reason " +
                    "(obrigatório para todo estado != AVAILABLE, spec §13)."
            else -> null
        }

        return Capability(
            id = capabilityId,
            state = resolvedState,
            confidence = declaredConfidence,
            reason = resolvedReason,
        )
    }
}
