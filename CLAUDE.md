# NetHAL

Network Hardware Abstraction Layer. Produto experimental e independente do SignallQ, para descoberta, identificação e controle seguro de equipamentos de rede local (roteadores, ONTs, ONUs, APs, mesh).

Drivers estáveis poderão ser incorporados futuramente ao SignallQ como "SignallQ Router Intelligence powered by NetHAL". Até lá, o NetHAL é laboratório, não produto final.

## Status

MVP implementado em produção. Componentes ativos:
- **SDK (NetHAL Core)**: `core/` — Discovery Engine, Fingerprint Engine, Driver Family Registry, Capability Engine com sessão gerenciada. Manifestos de catálogo versionados em `core/src/main/resources/catalog/`.
- **App (NetHAL Lab)**: `app/` — Telas de discovery (Tela 1: List, Tela 2: Detail), autenticação (Tela 5), listagem de capabilities (Tela 4), relatório (Tela 6). Implementadas com Jetpack Compose, consumindo Driver Family real via Capability Engine.
- **Drivers em produção**: 
  - TP-Link Archer C6 (`tplink-stok-luci-driver`, `READ_ONLY_ALPHA`) — protocolo `/cgi-bin/luci` + token `stok` + cookie, Capability Engine com sessão
  - TP-Link Archer C20 (`tplink-legacy-cgi-driver`, `READ_ONLY_ALPHA`) — protocolo dispatcher `/cgi?1&1&1&8` + HTTP Basic auth
  - Nokia G-1425G-B (`nokia-gpon-driver`, `READ_ONLY_ALPHA`) — protocolo RSA+AES + GPON specifics, sessão gerenciada
  - TP-Link gdpr-cgi e xdr-ds (experimental parser, `DRAFT`/`EXPERIMENTAL`, sem hardware confirmado)
- **Testes**: 235+ cenários de teste, discovery e capability engine validados contra hardware real (Archer C6, Archer C20, Nokia G-1425G-B)

## Stack prevista

MVP Android-first (Kotlin/Compose), porque discovery de rede local e probing de dispositivos são muito restritos em browser/PWA. SDK ("NetHAL Core") desacoplado do app ("NetHAL Lab") para permitir reuso futuro pelo SignallQ.

## Segurança (leve — padrão SignallQ)

Segurança aqui segue o mesmo peso do SignallQ: é parte da revisão normal (checklist + skill de
referência), **não** um gate humano bloqueando cada capability ou estágio de driver. O dev aplica; a
Marisa confere no gate de Done junto com o resto do QA. As regras foram enxugadas em 2026-07-10 — o
modelo anterior (sign-off obrigatório por capability e por estágio) era inviável de operar.

**Três não-negociáveis** (proteção real do equipamento do usuário, não fricção de processo):
- **Sem credencial armazenada.** Credenciais do roteador só existem na sessão local; nunca persistidas, nunca enviadas à nuvem, nunca logadas.
- **Nada de bypass de auth, exploit, brute-force ou uso automático de senha padrão.** Ver `SECURITY.md`.
- **Confirmação explícita do usuário antes de qualquer ação de escrita** (trocar canal/SSID/senha, reboot, etc.). Escrita nunca é automática nem silenciosa.

**Boas práticas (guia, não gate):**
- Drivers começam lendo (read-only) e ganham escrita depois — evolução natural, não estágio burocrático.
- Capability-based, não vendor-based: perguntar pela capability (`READ_WIFI_STATUS`, `SET_WIFI_CHANNEL`), não pelo fabricante — ver `docs/drivers/driver-model.md`.
- Telemetria sanitizada (LGPD): mascarar SSID/MAC/IP, nunca coletar senha — mesmo padrão de analytics do SignallQ.
- Referência técnica de ações destrutivas e sanitização: skill `/seguranca-nethal` (consulta, não sign-off obrigatório).

## Ciclo de vida de driver

`DRAFT → DISCOVERY_ONLY → READ_ONLY_ALPHA → READ_ONLY_BETA → WRITE_BETA → STABLE → DEPRECATED/BLOCKED`

Só entra no SignallQ o que estiver `STABLE`, com documentação de limitações e fallback seguro (critérios completos em `docs/product/specification.md` §16).

A promoção entre estágios é **decisão de produto do Rafael** (maturidade e cobertura do driver), não um sign-off de segurança obrigatório. Segurança entra na revisão normal da Marisa, como no SignallQ.

## Mapa de documentação

- `README.md` — visão geral e escopo do MVP
- `ROADMAP.md` — fases do produto
- `docs/product/specification.md` — especificação completa (arquitetura, componentes, modelo de dados, UX, riscos)
- `docs/architecture/overview.md` — visão resumida dos componentes do SDK
- `docs/architecture/driver-adoption-strategy.md` — pesquisa de protocolos/APIs por fabricante e priorização de drivers
- `docs/architecture/network-device-connectivity.md` — conectividade de dispositivos de rede
- `docs/drivers/driver-model.md` — capabilities e estados
- `docs/drivers/local-drivers-brazil.md` — drivers relevantes para o mercado brasileiro
- `docs/protocols/local-protocols.md` e `unified-management-brazil.md` — protocolos locais suportados
- `docs/design/` — design system completo (tokens, componentes, motion), protótipos navegáveis e assets de marca (NetHAL tem marca própria, distinta do SignallQ); resumo consultável via skill `/nethal-design`
- `CONTRIBUTING.md` / `SECURITY.md` — regras de contribuição e segurança

## Escopo fora do MVP

Acesso remoto fora da LAN, integração com operadoras, TR-069 ACS, TR-369 Controller, firmware upgrade, backup/restore de config, reset de fábrica, alteração automática sem confirmação. Ver lista completa em `docs/product/specification.md` §4.

## Fonte da verdade de execução

Execução, backlog, prioridades e bugs vivem no **GitHub Issues** (`gmmattey/nethal`). Toda issue nova, prioridade ou status de trabalho vive lá — não em conversa, Slack ou chat.

**Convenção de issue:** título `Task - <descrição>` para trabalho planejado e `[BUG] <descrição>` para defeito, com label de tipo (`type:task`/`type:bug`) e prioridade (`P0`/`P1`/`P2`) quando fizer sentido. Ver skill global `/issue-conventions`. Templates em `.github/ISSUE_TEMPLATE/` (`task.yml`, `bug.yml`, `driver.yml`).

**Verificação real antes de declarar (regra transversal, todos os agentes):** nunca declarar "PR mergeada", "teste passou", "driver promovido" ou "publicado" sem verificação executada de fato — não por inferência, não por confiar no relato de outro agente:
- PR mergeada → `gh pr view <N> --repo gmmattey/nethal --json state,merged,mergedAt`
- CI/teste passou → `gh pr checks <N> --repo gmmattey/nethal` ou execução direta
- Validação de driver/discovery → device Android real e firmware alvo declarado, nunca só mock local

## Squad do projeto

**Sincronização com o portfólio (regra 2026-07-22):** qualquer mudança de composição de squad, nome
de produto ou repo deve ser propagada no mesmo commit/PR para `C:\Projetos\CLAUDE.md` (raiz do
workspace) — é o doc que o Marcos (VP) usa pra rotear entre squads, e fica errado silenciosamente
se ninguém atualizar de fora.

**Consolidação de squad (2026-07-23):** o squad próprio do NetHAL (Rafael, Caio, Vera, Marisa) foi
aposentado — personas arquivadas em `.claude/agents/_archive/*_2026-07-23_consolidado.md`, não
invocar mais. O NetHAL passa a ser atendido pelo quadro único da 7ALabs, de nível de usuário
(`~/.claude/agents/`), compartilhado com o SignallQ. Detalhe:
`docs/decisions/DECISAO_CONSOLIDACAO_SQUAD_7ALABS_2026-07-23.md`.

- **Claudete** (antes Rafael) — Diretora de Produto & Delivery. Refina demandas, quebra tasks, prioriza, controla WIP, aprova promoção de estágio de driver e decide Done/Not Done. Não implementa. Fonte da verdade de tarefas é o GitHub Issues.
- **Camilo** (antes Caio) — Dev de backend/Android. NetHAL Core (SDK), NetHAL Lab (app Compose) e drivers/protocolos por fabricante. Implementa a partir do design entregue pela Lia para telas do Lab.
- **Lia** (antes Vera) — UX & Design. UI do NetHAL Lab (Compose) e consistência da marca própria do NetHAL (`docs/design/`). Usa **Claude Design**, nunca Figma. Entrega protótipo/spec para o Camilo; não edita código de produto além de composição visual.
- **Rhodolfo** (antes Marisa) — QA, Testes, Release & Documentação. Gate de Done. Segurança entra na revisão normal dele (os três não-negociáveis acima), não como sign-off bloqueante por capability/estágio.

> **Diego consolidado em 2026-07-10** — papel de drivers/protocolos absorvido pelo Caio, e depois
> pelo Camilo na consolidação de 2026-07-23. Não invocar mais. Persona arquivada em
> `.claude/agents/_archive/diego_2026-07-10_consolidado.md`.

Fluxo padrão: Claudete refina e distribui → Lia desenha (se houver UI) → Camilo implementa (SDK/app/drivers) → Rhodolfo revisa segurança/telemetria/QA e decide o gate → Claudete decide Done e eventual promoção de estágio.

## Skills do projeto

- `/modelo-capacidades` — vocabulário de capabilities e estados
- `/protocolos-locais` — protocolos candidatos, portas, heurísticas de fingerprint e priorização de drivers
- `/seguranca-nethal` — bloqueios do Safety Guard, regras de autenticação e sanitização de telemetria
- `/ciclo-vida-driver` — estágios de driver e critérios de promoção, incluindo entrada no SignallQ
- `/regras-android-nethal` — permissões, Wi-Fi e limites de background para discovery no Android
- `/nethal-design` — design system do NetHAL Lab: tokens de cor, tipografia, espaçamento, componentes, motion e marca

## Convenções

Seguem as convenções globais de `C:\Projetos\CLAUDE.md` (git, documentação, código, QA). Toda mudança de escopo, driver novo ou decisão de arquitetura vira atualização do `.md` correspondente em `docs/`.
