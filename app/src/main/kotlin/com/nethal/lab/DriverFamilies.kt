package com.nethal.lab

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
 * Vive em `:app` (composition root fino), por decisão da ADR 0002: cada `:drivers:*` expõe sua
 * `DriverFamilyFactory` pública via SPI e o `:app` é o único lugar que conhece todas elas e as
 * registra. `NetHalApplication` monta o `DriverFamilyRegistry` a partir daqui e o injeta no grafo de
 * pareamento por autenticação (`:feature:pairing-auth`), que constrói o `CapabilityEngine` real da
 * sessão. Este arquivo é a única fonte de verdade de quais Driver
 * Families existem — os diagnósticos manuais por driver (`:drivers:*:xxxManualCheck`) montam
 * registries locais de fábrica única e não dependem deste agregador.
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
