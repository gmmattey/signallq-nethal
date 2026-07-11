package com.nethal.core.navigation

/*
 * Casca do módulo de contratos de navegação (ADR 0002).
 *
 * Fica intencionalmente vazio na Fase 1 (extração/split). Aqui vão morar, na Fase 2, os contratos
 * de rota entre features — route objects/interfaces e as assinaturas `NavGraphBuilder.xyzGraph(...)`
 * que cada `:feature:*` implementa — para que nenhuma feature dependa diretamente de outra: toda
 * comunicação entre telas passa por este módulo. Sem lógica de tela, sem @Composable ainda.
 * Preenchido quando o composition root (host de bottom nav, #67) for retomado.
 */
