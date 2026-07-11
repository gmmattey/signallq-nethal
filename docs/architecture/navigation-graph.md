# Grafo de navegação do NetHAL Lab

> Fonte da verdade em código: `app/src/main/kotlin/com/nethal/lab/ui/navigation/NetHalNavHost.kt`.
> Este documento é o mapa de alto nível exigido como critério de aceite da issue #113. Sempre que o
> `NetHalNavHost` mudar de forma, atualizar aqui.

## Visão geral

O NetHAL Lab tem **um único `NavHost` raiz** (`NetHalNavHost`), montado pelo composition root
(`MainActivity`). Ele costura os três blocos do redesenho (issues #67-#96):

```
┌──────────── primeira instalação (onboarding ainda não concluído) ─────────────┐
│ onboardingGraph  (1a→1b→1c→1d→1e)              :feature:onboarding, #68-73     │
│   1a Boas-vindas ── "Ver dispositivos compatíveis" ──▶ 1f  (volta com back)     │
│   1e Resumo de permissões ── onPermissionsSummaryContinue ─────────┐            │
└─────────────────────────────────────────────────────────────────────┼──────────┘
                                                                        │
     já onboarded → startDestination pula direto para ─────────────────▼──────────┐
┌──────────────────────────── Pareamento ──────────────────────────────────────────┐
│ pairingDiscoveryGraph (2a/2b/2g/2h/2i)         :feature:pairing-discovery, #74-82 │
│   equipamento confirmado ─▶ pairingAuthGraph (2c/2d/2e/2f)   :feature:pairing-auth│
│   autenticado (onAuthenticated) ───────────────────────────────────┐             │
└─────────────────────────────────────────────────────────────────────┼────────────┘
                                                                        ▼
┌──────────────────────────── Uso diário ──────────────────────────────────────────┐
│ Routes.HOME → BottomNavHost (Status / Rede / Dispositivos / Configurações)   #67  │
└────────────────────────────────────────────────────────────────────────────────────┘
```

## Blocos e módulos

| Bloco | Grafo / destino | Módulo | Issues |
|-------|-----------------|--------|--------|
| Onboarding (1a→1f) | `onboardingGraph()` | `:feature:onboarding` | #68-73 |
| Pareamento por descoberta (2a/2b/2g/2h/2i) | `pairingDiscoveryGraph()` | `:feature:pairing-discovery` | #74, #75, #80, #81, #82 |
| Pareamento por autenticação (2c/2d/2e/2f) | `pairingAuthGraph()` | `:feature:pairing-auth` | #76-79 |
| Uso diário (bottom nav) | `Routes.HOME` → `BottomNavHost` | `:app` (monta `:feature:*` das abas e dos 5 grafos de Ferramentas avançadas) | #67, #83-88, #147 |

## Decisões da costura (AC da #113)

### 1. Onboarding roda uma vez (primeira instalação)

O marcador "onboarding concluído" é persistido em `OnboardingCompletionDataStoreRepository`
(DataStore `nethal_onboarding`, módulo `:app`, camada `data`).

- **Onde vive:** concreto no `:app`, **sem contrato em `core`**. Diferente de `ConsentRepository`
  (`:core:consent`, consumido por `:feature:onboarding`) e `ThemeModeRepository`
  (`:core:designsystem`, consumido por `:feature:settings`), este marcador tem **um único
  consumidor** — a própria navegação (`NetHalNavHost`), que decide o `startDestination`. Nenhuma
  `:feature:*` precisa lê-lo, então não há motivo para uma interface em `core` (sem abstração
  prematura).
- **Comportamento:** o `NetHalNavHost` observa o marcador e só compõe o `NavHost` depois do primeiro
  valor do DataStore (`startDestination` só é lido na primeira composição — compor com um palpite e
  "corrigir" depois não troca o destino inicial e faria o onboarding piscar para quem já concluiu).
  - Primeira instalação → `startDestination = OnboardingRoutes.WELCOME`.
  - Já onboarded → `startDestination = PairingDiscoveryRoutes.GRAPH`.
- **Marcação:** feita ao concluir a última tela do onboarding (`1e`, resumo de permissões), no
  callback `onPermissionsSummaryContinue`.

### 2. Handoff onboarding → pareamento

A tela **`1e` (resumo de permissões)** é a última do onboarding. Ao concluir, o `NetHalNavHost`
marca o onboarding como concluído e navega para `pairingDiscoveryGraph`, removendo o onboarding
inteiro da back stack (`popUpTo(OnboardingRoutes.WELCOME) { inclusive = true }`).

### 3. Handoff pareamento → uso diário

A tela **`2e` (Conectando)** é a última do pareamento antes do uso diário. Quando a autenticação tem
sucesso, `pairingAuthGraph` dispara `onAuthenticated`, e o `NetHalNavHost` navega **direto** para
`Routes.HOME` (bottom nav), removendo o funil de pareamento da back stack
(`popUpTo(PairingDiscoveryRoutes.GRAPH) { inclusive = true }`).

- **Sem Capabilities/Report:** as telas `CapabilitiesScreen`/`ReportScreen` (antigo `:app`) foram
  **descontinuadas** (decisão #66 / `docs/product/decisions/0001-telas-orfas-redesenho.md`). Seu
  conteúdo migra para os cards ao vivo da tela **Status** (`:feature:status`, #83). Os arquivos
  foram removidos nesta issue (sem caller restante).
- **Sessão autenticada (issue #147):** `:feature:status` (#83) e `:feature:wifi-network` (#84) são
  consumidores reais da sessão ao vivo desde a consolidação do `BottomNavHost` — o `onAuthenticated`
  **não fecha mais a sessão** no momento do handoff. Em vez disso, guarda o `CapabilityEngine` e o
  IP do equipamento pareado (`selectedTarget?.ip`, capturado antes de zerar `selectedTarget`) em
  estado no escopo do `NetHalNavHost` (`homeCapabilityEngine`/`homeDeviceIp`) e os repassa para
  `BottomNavHost`, que por sua vez os repassa para `statusGraph`/`wifiNetworkGraph`/os grafos de
  Ferramentas que precisam de sessão (`toolsPingGraph`, `rebootWanGraph`).
  - **Dono do ciclo de vida:** a composable de `Routes.HOME` — um `DisposableEffect` chama
    `engine?.closeSession()` no `onDispose`, alinhado ao não-negociável "sem credencial armazenada"
    (sessão só em memória, só enquanto o consumidor estiver vivo). Hoje não existe ação de "trocar
    equipamento"/logout que tire o usuário de Home de volta ao pareamento (gap conhecido, #85), então
    na prática o `onDispose` só dispara quando o processo/composable é destruído — a garantia
    estrutural já está correta para quando essa ação existir.

### 4. Trocar de equipamento a partir do uso diário

A ação "trocar de equipamento"/"parear outro dispositivo" **ainda não existe** (a issue #85 cortou
o item "EQUIPAMENTO" do protótipo de Configurações). A estrutura já está pronta para quando existir:
`pairingDiscoveryGraph` e `Routes.HOME` são **irmãos** no mesmo `NavHost`, então bastará
`navController.navigate(PairingDiscoveryRoutes.GRAPH)` a partir de Home para reabrir o pareamento —
**sem reexecutar o onboarding**, que fica atrás do marcador persistido.

### 5. Rotas órfãs

Após a costura, o `NetHalNavHost` raiz tem exatamente um destino próprio (`Routes.HOME`) mais os três
grafos de módulo. Não há mais:

- `Routes.WELCOME` / `Routes.BETA_OPT_IN` (telas antigas de onboarding em `:app`) — substituídas por
  `:feature:onboarding`; arquivos removidos.
- `Routes.CAPABILITIES` / `Routes.REPORT` — telas descontinuadas (#66); arquivos removidos.

As quatro abas de uso diário (`BottomNavDestination`) vivem no `BottomNavHost` (sub-`NavHost`
próprio), não neste grafo raiz.
