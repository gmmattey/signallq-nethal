---
name: nethal-design
description: Design system do NetHAL Lab — tokens de cor (dark/light), tipografia, espaçamento, ícones, componentes (navbar, topbar, sheets, diálogos, notificações), motion e acessibilidade. Consultar antes de desenhar ou implementar qualquer tela/componente do NetHAL Lab (Compose), ou ao tocar na marca própria do NetHAL.
---

Consulte o design system relevante para a tarefa abaixo:

$ARGUMENTS

Fonte completa: `docs/design/design-system.dc.html` (tokens/componentes) e `docs/design/prototypes.dc.html` (36 telas já desenhadas) — abrir no navegador. Assets em `docs/design/assets/brand/` (logo, favicon) e `docs/design/assets/icons/dark|light/` (SVG outline, mesmo nome nos dois temas). Ver `docs/design/README.md` para o mapa completo.

---

## Princípio central

Sistema único, dark cyber utilitário — sem fundos decorativos chapados, sem ícones em cápsula colorida. Cor é reservada a função: accent (#006FFF) = interativo/dado primário; verde/âmbar/vermelho = só estado (sucesso/aviso/erro), nunca decoração de categoria.

## Status de implementação (app, não design)

Tema claro **implementado** (issue #132, PR mergeado 2026-07-11). O app deixou de ser dark-only:
`NetHalLabTheme(themeMode)` resolve `LIGHT`/`DARK`/`SYSTEM` (`SYSTEM` segue `isSystemInDarkTheme()`),
e há seletor real Claro/Escuro/Sistema em Configurações (aplica na hora, sem reiniciar). A escolha
persiste em DataStore (`nethal_theme`).

Arquivos-chave:
- Tokens dark/light: `core/designsystem/src/main/kotlin/com/nethal/core/designsystem/theme/Color.kt`
- `ThemeMode` + contrato de persistência: `.../theme/ThemeMode.kt`, `.../theme/ThemeModeRepository.kt`
- Resolução de `ColorScheme` + `NetHalLabTheme`: `.../theme/Theme.kt`
- Cores sem slot M3 (sucesso/aviso/erro-de-chip/texto terciário) via `CompositionLocal`:
  `.../theme/ExtendedColors.kt` (`LocalNetHalExtendedColors`)
- Persistência real (DataStore): `app/src/main/kotlin/com/nethal/lab/data/theme/ThemeModeDataStore.kt`
- Seletor: `feature/settings/src/main/kotlin/com/nethal/feature/settings/SettingsScreen.kt`

Regra ao implementar tela nova: consumir **cor semântica** — `MaterialTheme.colorScheme.*` para os
slots M3 e `LocalNetHalExtendedColors.current.*` para sucesso/aviso/erro-de-chip/texto terciário.
Nunca referenciar token `*Dark`/`*Light` direto numa tela (não responde ao toggle). Border → `outline`,
Surface-2 → `surfaceVariant`, texto terciário → `LocalNetHalExtendedColors.current.onSurfaceTertiary`.

## Cores — tokens (dark / light)

| Token | Dark | Light |
|---|---|---|
| BG principal | #0B0F19 | #F4F6FB |
| Surface/Card | #161B26 | #FFFFFF |
| Surface-2 (elevação) | #1D2433 | — |
| Border | #262F40 | #DCE2ED |
| Accent — Electric Blue | #006FFF | #006FFF |
| Sucesso (neon) | #10B981 | #10B981 (chip: #0E9B70) |
| Aviso (neon) | #F59E0B | #F59E0B (chip: #B45309) |
| Erro (neon) | #EF4444 | #EF4444 (chip/texto: #DC2626) |
| Texto primário | #E8ECF5 | #10192B |
| Texto secundário | #8891A8 | #5B6478 |
| Texto terciário | #4C5567 | #9AA3B8 |

## Tipografia

Fonte única: **Google Sans Flex** (fallback Google Sans, Roboto, system-ui) — hierarquia vem de tamanho/peso, nunca de segunda fonte (nem para dado tabular/mono).

| Uso | Tamanho/altura | Peso |
|---|---|---|
| Título de tela | 30/38 | 700 |
| Headline | 24/30 | 700 |
| Title | 18/24 | 600 |
| Body | 14/22 | 400 |
| Body secundário | 13/20 | 400 |
| Label/botão | 14/20 | 600 |
| Overline | 11/16, uppercase, tracking 0.12em | 600 |
| Dado (tabular) | 13/20, `font-variant-numeric: tabular-nums` | 500 |

## Espaço, raio, grid

- Base grid: **4dp** — toda medida é múltiplo de 4; nunca "quase encaixa", arredondar pro degrau mais próximo.
- Margem de tela: 24dp · padding de card: 16–20dp · padding de linha de lista: 12–16dp · gap entre cards: 12–16dp · gap entre seções: 20–32dp · ícone↔texto: 10–12dp.
- Alvo mínimo de toque: 48×48dp.
- Raio: 8 / 14 / 20 / 26 / 999 (pill) — cards grandes usam 26dp, sheets 28dp (só cantos superiores), diálogos 24–28dp, chips/pills 999.
- Elevação é tonal (bg-0 → surface → surface-2), nunca sombra pesada em dark.

## Iconografia

Outline puro, stroke 1.8–2px, sem preenchimento (exceto indicador tipo dot). Nunca em cápsula/círculo colorido — ícone solto sobre a superfície. Abaixo de 32px o stroke engrossa (3–4px) pra manter o furo interno legível. Set oficial (mesmo nome em `docs/design/assets/icons/dark/` e `.../icons/light/`): actions, camera, channel, chevron-right, close, edit, firmware, iot-sensor, megaphone, overview, password, reboot, router, switch, warning, wifi.

## Componentes principais

**Navegação inferior** — Material 3 Navigation Bar, 4 destinos (Status, Rede, Dispositivos, Configurações). Altura 80dp, padding 12dp topo/16dp base, ícone 24×24dp, indicador ativo pill 64×32dp raio 16dp, rótulo 12sp (600 ativo / 400 inativo). Sempre visível, nunca some ao rolar. Tocar aba já ativa rola pro topo. Badge só para alerta real.

**Topbar** — grande (abas principais, ~96dp expandida, título 30/700, colapsa pra compacta ao rolar) e compacta (sub-telas/ferramentas, 64dp fixa, título 18/600, nunca colapsa). Botão voltar 32×32dp circular; no máx. 1 ação à direita (36×36dp); nunca ação destrutiva na topbar.

**Botões** — primário: fill accent #006FFF, texto branco, 600/13, raio 18dp, padding 12dp. Secundário: outline accent, texto accent, 500/13. Destrutivo: texto puro cor erro (#EF4444 dark / #DC2626 light), sem fill.

**Status chip** — outline colorido (não fill), 600/11, padding 4×10, raio 999 (pill). Cores conforme tokens de sucesso/aviso/erro.

**Bottom sheet** — raio 28dp só cantos superiores, alça 32×4dp centralizada a 12dp do topo, scrim rgba(0,0,0,.4), altura por conteúdo (peek) até 90% da tela, rola internamente além disso. Fecha por swipe down, tap no scrim, ou botão explícito — nunca só um "x" isolado.

**Diálogo** — 280–400dp largura, centralizado, raio 24–28dp. Título 15/700 → corpo 11.5–13/400 → ações à direita, máx. 2. Confirmação sempre à direita; cor de erro só se a ação apagar dados. Scrim rgba(0,0,0,.5); toque fora fecha só se a ação não for destrutiva.

**Snackbar** — raio 16dp, 1 mensagem + no máx. 1 ação de texto, margem 16dp da navbar/bordas, auto-some em 4–10s. Nunca empilhar mais de 1 — a nova substitui a anterior.

**Push (sistema)** — ícone 22–24dp outline, título 13/600, corpo 12/400 truncado em 2 linhas.

**Estado desabilitado** — opacidade 38–45% no elemento inteiro (ícone+texto+chevron; nunca recolorir pra cinza genérico). Permanece tocável e abre diálogo explicando o motivo e o caminho de desbloqueio (ex. link pra atualizar firmware). Nunca esconder a opção ou deixá-la muda ao toque.

**Unidade de anúncio (AdSense nativo)** — só 1 unidade in-feed, no fim do conteúdo rolável da aba Status, mesmo raio/borda/padding/superfície dos outros cards. Rótulo "ANÚNCIO" 9px/600 obrigatório, canto superior direito (política AdSense). Nunca em onboarding/pareamento/configurações/sheets/diálogos/erro. Distância mínima 24dp de qualquer botão primário/destrutivo. Máx. 1 por tela, nunca duas consecutivas.

## Motion

- Toda navegação é animada, sem cortes secos.
- Shared axis (X), 300ms — fluxo sequencial (onboarding, pareamento).
- Container transform, 350ms — cartão → detalhe (ex. dispositivo → detalhes).
- Fade through, 200ms — troca de aba na navegação inferior (fade out 90ms + fade in 110ms, scale 0.96→1).
- Easing: standard `cubic-bezier(.2,0,0,1)` (maioria); emphasized `cubic-bezier(.3,0,.8,.15)` (tela cheia). Nunca linear.
- Scroll do topbar: título grande e chip de status somem com opacidade + translateY(-8dp) a partir de ~24dp rolado, fundindo em barra compacta 56dp — nunca corte abrupto.
- Gestos: voltar por swipe da borda esquerda (zona 20dp, gesto preditivo Android); pull-to-refresh no topo de listas (limite elástico 72dp, resistência progressiva); swipe lateral em item de lista revela ação secundária a partir de 40% da largura.

## Contraste e acessibilidade (M3/WCAG AA)

Mínimos: 4.5:1 texto normal, 3:1 texto grande (≥18/700) e ícones/UI. Texto terciário (~2.5:1) e accent sobre fundo (~4.3:1) ficam abaixo do mínimo de corpo de texto — reservar terciário a overline/legenda curta, accent a label/botão ≥14px semibold. Nunca terciário em corpo de texto. Camadas de estado (hover/focus/pressed): overlay do on-surface a 8%/12%/12%; drag a 16%.

## Marca

Ícone: quadrado raio 8, stroke accent 2.6, quadrado interno 8×8 preenchido accent (viewBox 32×32, ver `docs/design/assets/brand/logo-icon.svg`). Lockup = ícone + wordmark "NETHAL" (700, tracking 0.03em). Clear space = 1× módulo do ícone. Tamanho mínimo: ícone isolado 16px; lockup 20px de altura de ícone (abaixo disso, só ícone). Cor sempre accent #006FFF sobre superfície escura, ou #10192B sobre branco — nunca preenchido com cor neon de estado. Favicon (`docs/design/assets/brand/favicon.svg`): fundo sempre #0B0F19 (nunca transparente/branco); abaixo de 32px o stroke engrossa. Tamanhos: favicon.ico 16/32/48, apple-touch-icon 180, android-chrome 192/512.

## Telas já desenhadas (Prototypes)

36 telas cobrindo 4 fluxos — checar antes de desenhar tela nova, pode já existir:
1. **Onboarding** (6): boas-vindas, localização, dispositivos próximos, notificações, permissões concedidas, dispositivos compatíveis.
2. **Pareamento** (9): buscando, dispositivo encontrado, login no modem, conectando, falha na conexão, onde encontrar senha, selecionar tipo/fabricante/modelo.
3. **Uso diário** (11, dark+light): Status, Wi-Fi & Rede, Configurações, Dispositivos, Status ONT, Status Mesh.
4. **Ferramentas avançadas** (9): Speedtest (testando/resultado), Ping, Traceroute, DNS Lookup, verificação de porta, recurso indisponível, reiniciar WAN, histórico de conexão.

## Limites

- Esta skill é referência de design, não implementa. Vera desenha/mantém o sistema (Claude Design — Artifacts + skills `frontend-design`/`impeccable`, nunca Figma); Bruno implementa em Compose a partir da spec da Vera.
- Fonte da verdade dos tokens é `docs/design/design-system.dc.html` — se este resumo divergir do arquivo fonte, o arquivo fonte vence. Atualizar este SKILL.md quando o design system mudar (a Vera edita o `.dc.html` original em `Claude Design/Nethal/` e recopia para `docs/design/`).
- Valores já estão em dp/sp (unidades Android/Compose) — tradução direta pra `Dp`/`TextUnit` em Compose, sem conversão de px.
