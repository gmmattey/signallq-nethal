package com.nethal.core.navigation

/**
 * Rotas dos destinos de "Ferramentas avançadas" (seção dentro de Configurações, issue #136,
 * protótipos `3c`/`3f`) — mesma razão de existir de [BottomNavDestination]: cada `:feature:tools-*`
 * que nasce declara sua rota aqui, e `:feature:settings` navega só pela string de rota, nunca por
 * import direto do módulo de feature (regra de dependência única, ADR 0002).
 *
 * A visibilidade real de cada entrada na lista de Configurações depende de a rota estar de fato
 * registrada no `NavHost` do composition root (`:app`, `BottomNavHost`) — `:feature:settings`
 * consulta `navController.graph` para decidir o que mostrar (ver `SettingsGraph.kt`), nunca lista
 * as 7 entradas do protótipo de uma vez (issue #136: "sem hardcode, sem link morto"). Cada entrada
 * nasce aqui só quando o módulo `:feature:tools-*` correspondente já existe de verdade — todas as 6
 * entradas hoje têm módulo pronto (issues #91/#99, #94/#100, #90/#98, #93/#101, #92/#102, #95/#103);
 * a consolidação final no `BottomNavHost` (registro real do `NavGraphBuilder` de cada módulo) é
 * escopo da issue #147.
 *
 * O `route` de cada entrada bate exatamente com a constante `ROOT`/`ROUTE` já declarada no módulo
 * de tool correspondente ([com.nethal.feature.toolsspeedtest.SpeedtestRoutes.ROOT],
 * [com.nethal.feature.toolsdns.ToolsDnsRoutes.ROOT], [com.nethal.feature.toolstraceroute.TracerouteRoutes.ROOT],
 * [com.nethal.feature.toolsrebootwan.RebootWanRoute.ROUTE]) — não redeclara rota nova, só espelha.
 */
enum class AdvancedToolDestination(val route: String, val label: String) {
    PING("home/settings/tools/ping", "Ping"),
    PORT_CHECK("home/settings/tools/port-check", "Verificação de porta"),
    SPEEDTEST("tools/speedtest", "Teste de velocidade"),
    DNS_LOOKUP("tools/dns", "DNS Lookup"),
    TRACEROUTE("tools/traceroute", "Traceroute"),
    REBOOT_WAN("tools/reboot-wan", "Reiniciar interface WAN"),
}
