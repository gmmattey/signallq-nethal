# Decisão de produto — destino das 4 telas sem equivalente no redesenho

- **Data:** 2026-07-11
- **Decisor:** Rafael (Product Owner)
- **Issue:** [gmmattey/nethal#66](https://github.com/gmmattey/nethal/issues/66)
- **Status:** Definitiva

## Contexto

O redesenho do NetHAL Lab (`docs/design/prototypes.dc.html`, 29 telas — Onboarding, Pareamento, Uso diário, Ferramentas) não tem equivalente direto para 4 telas do fluxo linear atual: `PrivacyScreen.kt`, `BetaOptInScreen.kt`, `CapabilitiesScreen.kt`, `ReportScreen.kt`. Levantamento técnico completo na issue #66. Esta decisão fecha o que migra e o que é descontinuado.

## Decisões

### `PrivacyScreen` — descontinuada como tela dedicada

Conteúdo (nenhuma senha armazenada, como a telemetria é sanitizada) migra para um **item dentro da tela Configurações** (protótipos `3c`/`3f`, issue #85) — é conteúdo de referência, consultável a qualquer momento, não algo que precise de um passo próprio no onboarding. O botão "Ver privacidade" em Boas-vindas (`1a`, issue #68) permanece funcionalmente presente, mas passa a navegar para esse item de Configurações em vez de uma tela própria do onboarding.

### `BetaOptInScreen` — fundida com "Notificações" (`1d`)

O opt-in de telemetria beta (`ConsentScope.TELEMETRY_BETA`) é apresentado dentro da tela "Notificações" do onboarding novo (`1d`, issue #71), junto com o pedido de permissão `POST_NOTIFICATIONS` — mesmo momento de coleta de consentimento no fluxo, evita um passo extra isolado. Todo o texto de coleta de dados hoje presente em `BetaOptInScreen.kt` (spec §8.9: fabricante/modelo/firmware, protocolo, capabilities detectadas, resultado de autenticação sem senha, tempo de resposta, hash anônimo) é preservado integralmente dentro da tela fundida. O toggle de saída do programa beta (`SettingsViewModel.leaveBetaProgram`) continua existindo em Configurações (issue #85), referenciando o mesmo estado — sem duplicar a fonte da verdade.

### `CapabilitiesScreen` — descontinuada, sem substituto de "lista bruta"

Não migra como lista de `CapabilityId`+estado. O valor real dela (mostrar o que foi lido do equipamento) é absorvido pelos cards de dado ao vivo da tela **Status** (issue #83) e suas variantes ONT (#87)/Mesh (#88), que já expõem os mesmos resultados de forma mais útil ao usuário final (dado vivo, não enumeração técnica). Não é criada nenhuma tela nova de "lista de capabilities" — mantém o MVP enxuto. Se uma necessidade de debug/suporte técnico aparecer depois, é feature nova a justificar com demanda real, não herança automática desta tela.

### `ReportScreen` — descontinuada

O modelo de "relatório final de uma sessão" não existe mais no paradigma de app sempre ligado (uso diário com bottom nav). A função que ela cumpria é substituída por dois mecanismos que já existem no redesenho:

1. A tela **Status** mostra dado ao vivo, elimina a necessidade de "resumo pós-sessão".
2. O envio de relatório anônimo é substituído pela **Telemetry Lane A** (sessão de diagnóstico + resultado por capability, issue #97), automática (fire-and-forget, gate de consentimento) — não é mais uma ação manual "enviar relatório" disparada pelo usuário.

## Impacto em outras issues

- **#68** (Boas-vindas): "Ver privacidade" aponta para o item de Privacidade dentro de Configurações, não para uma tela de onboarding.
- **#71** (Notificações): confirma a fusão com `BetaOptInScreen` prevista condicionalmente no critério de aceite original — é essa a decisão.
- **#83/#107** (Status): não herda "lista de capabilities" como conteúdo — só os cards ao vivo já especificados nos protótipos `3a`/`3d`.
- **#85** (Configurações): ganha item de Privacidade; mantém o toggle de beta já existente (referenciando o mesmo estado usado em #71, se a fusão acontecer lá).
- **#97** (Telemetria): a Lane B (eventos de produto / `screen_view`) pode ser implementada com a taxonomia final de telas — onboarding (`1a`-`1f`), pareamento (`2a`-`2i`), uso diário (Status/Rede/Dispositivos/Configurações), ferramentas (`4a`-`4i`). Não existe mais `screen_view` para "capabilities" nem "report" — são eventos do fluxo antigo, removidos definitivamente da taxonomia.

## Escopo fechado

Esta decisão cobre só o destino — a implementação de cada mudança fica nas issues de UI já abertas (#68, #71, #83, #85, #97), cada uma referenciando este documento.
