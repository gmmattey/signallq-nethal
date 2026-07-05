# NetHAL

Network Hardware Abstraction Layer. Produto experimental e independente do SignallQ, para descoberta, identificação e controle seguro de equipamentos de rede local (roteadores, ONTs, ONUs, APs, mesh).

Drivers estáveis poderão ser incorporados futuramente ao SignallQ como "SignallQ Router Intelligence powered by NetHAL". Até lá, o NetHAL é laboratório, não produto final.

## Status

Fase de fundação: só especificação e documentação. Nenhum código ainda (`src/`, app Android, etc. não existem). Antes de qualquer implementação, verificar se a estrutura de pastas do MVP já foi criada.

## Stack prevista

MVP Android-first (Kotlin/Compose), porque discovery de rede local e probing de dispositivos são muito restritos em browser/PWA. SDK ("NetHAL Core") desacoplado do app ("NetHAL Lab") para permitir reuso futuro pelo SignallQ.

## Princípios inegociáveis

- **Read-only primeiro.** Todo driver nasce lendo dados; ações de escrita vêm depois e exigem confirmação explícita do usuário.
- **Capability-based, não vendor-based.** Nunca `if (vendor == "TP-Link")` — sempre perguntar pela capability (`READ_WIFI_STATUS`, `SET_WIFI_CHANNEL`, etc.), ver `docs/drivers/driver-model.md`.
- **Sem senha armazenada.** Credenciais do roteador só existem na sessão local; nunca persistidas, nunca enviadas à nuvem, nunca logadas.
- **Falha segura.** Na dúvida, o sistema para e explica — nunca executa uma ação arriscada por padrão.
- **Nada de bypass de auth, exploit, brute-force ou uso automático de senha padrão.** Ver `SECURITY.md` e `CONTRIBUTING.md`.
- **Telemetria sanitizada.** Nunca coletar senha, SSID em claro, MAC completo ou IP público completo — sempre mascarar (ver seção 8.9 de `docs/product/specification.md`).

## Ciclo de vida de driver

`DRAFT → DISCOVERY_ONLY → READ_ONLY_ALPHA → READ_ONLY_BETA → WRITE_BETA → STABLE → DEPRECATED/BLOCKED`

Só entra no SignallQ o que estiver `STABLE`, com documentação de limitações e fallback seguro (critérios completos em `docs/product/specification.md` §16).

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
- `docs/design/` — brief de design e assets de marca (NetHAL tem marca própria, distinta do SignallQ)
- `CONTRIBUTING.md` / `SECURITY.md` — regras de contribuição e segurança

## Escopo fora do MVP

Acesso remoto fora da LAN, integração com operadoras, TR-069 ACS, TR-369 Controller, firmware upgrade, backup/restore de config, reset de fábrica, alteração automática sem confirmação. Ver lista completa em `docs/product/specification.md` §4.

## Squad do projeto

- **Rafael** — Diretor Técnico & Product Owner. Planeja, quebra tasks, prioriza, aprova promoção de estágio de driver. Não implementa.
- **Bruno** — Especialista Android/Kotlin. NetHAL Core (SDK) e NetHAL Lab (app Compose).
- **Diego** — Especialista em Drivers & Protocolos. Adaptadores por fabricante/protocolo, catálogo de compatibilidade, reuso open source.
- **Marisa** — Segurança, Privacidade & Telemetria. Gate obrigatório para autenticação, Safety Guard, telemetria e promoção de driver.

Fluxo padrão: Rafael refina e distribui → Bruno/Diego implementam → Marisa revisa segurança/telemetria → Rafael decide Done e eventual promoção de estágio.

## Skills do projeto

- `/modelo-capacidades` — vocabulário de capabilities e estados
- `/protocolos-locais` — protocolos candidatos, portas, heurísticas de fingerprint e priorização de drivers
- `/seguranca-nethal` — bloqueios do Safety Guard, regras de autenticação e sanitização de telemetria
- `/ciclo-vida-driver` — estágios de driver e critérios de promoção, incluindo entrada no SignallQ
- `/regras-android-nethal` — permissões, Wi-Fi e limites de background para discovery no Android

## Convenções

Seguem as convenções globais de `C:\Projetos\CLAUDE.md` (git, documentação, código, QA). Toda mudança de escopo, driver novo ou decisão de arquitetura vira atualização do `.md` correspondente em `docs/`.
