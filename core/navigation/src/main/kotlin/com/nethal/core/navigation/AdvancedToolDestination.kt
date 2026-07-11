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
 * nasce aqui só quando o módulo `:feature:tools-*` correspondente já existe de verdade — hoje só
 * [PING] e [PORT_CHECK] (issues #91/#99, #94/#100).
 */
enum class AdvancedToolDestination(val route: String, val label: String) {
    PING("home/settings/tools/ping", "Ping"),
    PORT_CHECK("home/settings/tools/port-check", "Verificação de porta"),
}
