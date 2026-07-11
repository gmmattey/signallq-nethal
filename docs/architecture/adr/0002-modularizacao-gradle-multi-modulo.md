# ADR 0002 — Modularização Gradle: de 2 módulos monolíticos para módulos por feature/driver, conectados só por contrato

Status: Aceita (2026-07-11, diretiva direta de Luiz — "não quero um sistema monolítico, quero tudo
modular, inclusive as telas independentes; funcionalidades construídas de forma independente e
conectáveis"). Detalhamento técnico: Rafael/Bruno.

## Contexto

O projeto tem hoje 2 módulos Gradle: `:core` (JVM puro — auth, capability, catalog, consent,
discovery, driver, fingerprint, model, protocol, tooling, todos no mesmo módulo) e `:app` (Android —
toda a UI num módulo só: authentication, capabilities, common, discovery, equipment, navigation,
onboarding, privacy, report, settings, theme).

O épico de redesenho em andamento (#66-#107, 29 telas novas + ~10 capabilities de backend novas +
telemetria) ia entrar inteiro dentro desses 2 módulos, aprofundando o monolito bem no momento em
que mais código está para ser escrito. Luiz pediu para inverter isso agora, antes do grosso do
épico avançar: cada tela/feature em módulo próprio, independente, conectável — e qualquer coisa já
monolítica também migra.

Não há framework de DI no projeto hoje (sem Hilt/Koin) — composição é manual (`ViewModelFactory`,
com duplicação já registrada em #56). Esta ADR não introduz DI framework novo — mantém composição
manual, só move a montagem para um composition root fino em `:app`.

## Decisão

Migrar para módulos por responsabilidade, com uma regra de dependência única e não-negociável:

**Módulo de feature nunca depende de outro módulo de feature. Toda comunicação entre telas passa
por contrato em `:core:navigation`. Todo driver se registra no Driver Family Registry via SPI —
nenhum outro módulo importa um driver diretamente.**

### `:core:*` — SDK, split do `:core` monolítico atual

| Módulo | Conteúdo | Depende de |
|---|---|---|
| `:core:model` | Modelos de domínio compartilhados (`CapabilityId`, `CapabilityState`, `DeviceInfo` etc.) | nenhum |
| `:core:protocol` | HTTP transport, primitivas de protocolo (inclui o guard de IP privado, #55) | `:core:model` |
| `:core:discovery` | Discovery Engine (SSDP/mDNS/gateway) | `:core:model`, `:core:protocol` |
| `:core:fingerprint` | Fingerprint Engine | `:core:model`, `:core:catalog` |
| `:core:catalog` | Manifestos de catálogo + Driver Family Registry (contrato/SPI) | `:core:model` |
| `:core:capability` | Capability Engine, sessão | `:core:model`, `:core:protocol`, `:core:catalog` |
| `:core:auth` | Sessão de autenticação | `:core:model`, `:core:protocol` |
| `:core:consent` | Escopos de consentimento (LGPD) | `:core:model` |
| `:core:telemetry` **(novo, #97)** | Telemetry Collector | `:core:consent`, `:core:model` |
| `:core:navigation` **(novo)** | Contratos de rota (route objects/interfaces), sem lógica de tela | nenhum (só tipos + Navigation Compose) |
| `:core:designsystem` **(novo)** | Tokens de cor/tipografia/espaçamento, componentes reutilizáveis (navbar, topbar, sheets, diálogos, componente "Recurso indisponível" #89) — extraído de `app/ui/theme` e `app/ui/common` | Compose only |

### `:driver:*` — cada Driver Family em módulo próprio, plugável

Extraído de `core/driver/family/{nokia,tplink}/...`:

- `:driver:tplink-stok-luci` (Archer C6, `READ_ONLY_ALPHA`)
- `:driver:tplink-legacy-cgi` (Archer C20, `READ_ONLY_ALPHA`)
- `:driver:nokia-gpon` (G-1425G-B, `READ_ONLY_ALPHA`)
- `:driver:tplink-experimental` (gdpr-cgi + xdr-ds, `DRAFT`/`EXPERIMENTAL`)

Cada um depende só de `:core:model`, `:core:protocol`, `:core:catalog` (contrato SPI). Adicionar
driver novo = adicionar módulo novo + registrar no composition root — nunca editar outro driver.
Nenhum destes é "driver novo" no sentido da restrição de escopo desta missão (equipamento ainda não
integrado) — são os 4 já em produção, só fisicamente movidos de lugar.

### `:feature:*` — uma tela/fluxo coeso por módulo

| Módulo | Cobre |
|---|---|
| `:feature:onboarding` | #68-73 |
| `:feature:pairing-discovery` | #74, #75, #80-82 (buscando, encontrado, seleção manual — mesmo fluxo de descoberta, sem credencial) |
| `:feature:pairing-auth` | #76-79 (login, onde achar senha, conectando, falha — cluster com estado de sessão compartilhado) |
| `:feature:status` | #83, #87, #88 (Status + variantes ONT/Mesh) |
| `:feature:wifi-network` | #84 |
| `:feature:settings` | #85 (reaproveita `SettingsScreen` já existente, hoje pendurada numa rota órfã) |
| `:feature:devices` | #86 |
| `:feature:tools-common` **(novo)** | Hub de Ferramentas + componente "Recurso indisponível" (#89) |
| `:feature:tools-speedtest` | #90 |
| `:feature:tools-ping` | #91 |
| `:feature:tools-portcheck` | #94 |
| `:feature:tools-dns` | #93 |
| `:feature:tools-traceroute` | #92 |
| `:feature:tools-reboot-wan` | #95 |
| `:feature:tools-history` | #96 |

Cada `:feature:*` depende só de `:core:model`, `:core:navigation`, `:core:designsystem`, e do(s)
módulo(s) `:core:*` da capability que consome (nunca de outro `:feature:*`, nunca de `:driver:*`
diretamente). Exposição padrão: `fun NavGraphBuilder.xyzGraph(nav: NavHostController)` — o módulo
não sabe quem o chama.

### `:app` — composition root fino

Depois da migração, `:app` só contém: `Application`/`Activity`, manifest, registro dos drivers no
Driver Family Registry, montagem do `NavHost`/bottom nav host (#67, retomado na Fase 2) chamando os
`*Graph()` de cada `:feature:*`, e o `ViewModelFactory` consolidado (resolve #56 de graça, já que a
duplicação hoje é justamente entre `ViewModelFactory` e `NetHalApplication`).

## Consequências

- `:core` (monolítico) e a estrutura atual de `app/ui/*` deixam de existir como estão — vira migração
  mecânica de arquivos + ajuste de imports + `build.gradle.kts` por módulo nascente. Preservar
  histórico de git (`git mv`, não recriar do zero).
- Os 235+ cenários de teste existentes devem continuar passando após a migração — é refatoração
  estrutural, não deveria mudar comportamento.
- `:feature:*` que ainda não existe (todo o épico #66-107) nasce direto no módulo novo — não é
  criado dentro do monolito para depois migrar.
- Bug fixes já em andamento (#55, #59, #21) foram iniciados antes desta ADR, em cima da estrutura
  monolítica antiga — são pequenos e pontuais, mergeiam primeiro, e a migração desta ADR é
  rebaseada em cima do resultado (arquivos só mudam de módulo depois, conteúdo já corrigido).
- Sem framework de DI novo — composição continua manual, só centralizada no composition root de
  `:app` em vez de duplicada.
- Build fica mais lento para configurar (mais módulos), mas builds incrementais ficam mais rápidos
  (mudar uma feature não recompila as outras) — troca aceita dado o volume de features paralelas
  que o épico de redesenho vai gerar.

## Próximo passo

Bruno executa a Fase 1 (extração de `:core:*`, `:driver:*`, `:core:navigation`,
`:core:designsystem`, redução de `:app` ao composition root) como pré-requisito de todo o resto do
épico de redesenho. Fase 2 (cada `:feature:*` novo nasce já modular) começa assim que a Fase 1
mergear — inclusive a retomada de #67 (host de bottom nav), agora só como o composition root que
monta os `*Graph()` dos módulos de feature.
