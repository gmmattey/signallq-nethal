package com.nethal.core.driver.family

import com.nethal.core.catalog.DriverFamilyRegistry
import com.nethal.core.driver.family.nokia.gpon.NokiaGponDriverFamilyFactory
import com.nethal.core.driver.family.tplink.gdprcgi.TpLinkGdprCgiDriverFamilyFactory
import com.nethal.core.driver.family.tplink.legacycgi.TpLinkLegacyCgiDriverFamilyFactory
import com.nethal.core.driver.family.tplink.stokluci.TpLinkStokLuciDriverFamilyFactory
import com.nethal.core.driver.family.tplink.xdrds.TpLinkXdrDsDriverFamilyFactory

/**
 * Ponto central de composição do [DriverFamilyRegistry] real do `core` — monta o mapa fixo
 * `driverFamilyId -> DriverFamilyFactory` uma única vez, nunca via reflection ou scan dinâmico
 * (`docs/architecture/hal-layering-model.md` §8/§10 passo 6).
 *
 * Vive em `core`, não em `app`, por decisão explícita deste passo (4 do plano de refatoração):
 * o único consumidor real de Driver Family hoje é `ManualCheckRunnerC20` (`core/driver/tplink/`,
 * roda como task Gradle `:core:tplinkC20ManualCheck`), não o módulo `app` — o app ainda não liga
 * nenhum driver ao Capability Engine (que nem existe em código ainda). Quando o app precisar
 * resolver Driver Families em runtime (Capability Engine real, fora de escopo deste passo), este
 * composition root pode migrar para lá ou ser exposto como API pública do `core` consumida pelo
 * `app` — decisão a revisitar no passo 8 do plano (`hal-layering-model.md` §10).
 *
 * Cada Driver Family nova registrada aqui deve ser somada a esta lista — nunca descoberta
 * automaticamente por classpath scanning.
 */
fun defaultDriverFamilyRegistry(): DriverFamilyRegistry = DriverFamilyRegistry(
    listOf(
        TpLinkLegacyCgiDriverFamilyFactory(),
        TpLinkStokLuciDriverFamilyFactory(),
        TpLinkGdprCgiDriverFamilyFactory(),
        TpLinkXdrDsDriverFamilyFactory(),
        NokiaGponDriverFamilyFactory(),
    ),
)
