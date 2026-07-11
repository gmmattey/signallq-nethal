# Spec — Onboarding (1b/1c/1e/1f) e Pareamento manual (2g/2h/2i)

Autoria: Vera (UX/UI). Entrega para: Bruno (implementação Compose).
Issues cobertas: #69, #70, #72, #73 (Lote 1 — Onboarding) e #80, #81, #82 (Lote 2 — Pareamento manual).

Base visual: `docs/design/design-system.dc.html` (tokens) + `docs/design/prototypes.dc.html`
(telas `1b`, `1c`, `1e`, `1f`, `2g`, `2h`, `2i` — abrir no navegador para ver o protótipo
navegável original). Este documento é **fiel ao protótipo onde o protótipo está certo** e
**registra e corrige, de forma explícita, onde o protótipo diverge da implementação real**
(ver seção "Gaps encontrados" em cada tela). Bruno implementa a partir deste documento, não
do protótipo cru — em caso de conflito, este documento vence para estas 7 telas.

---

## 0. Fonte de dados real (catálogo)

Todas as telas que listam fabricante/modelo/estágio usam **só** o manifesto ativo carregado por
`loadEmbeddedCatalogResource()` (`core/src/main/kotlin/com/nethal/core/catalog/DriverRegistry.kt`
→ hoje `catalog/catalog-2026.07.26.json`). **Não usar** `tplink-mercusys-support-matrix-*.json`
— é levantamento de pesquisa (`confidenceLevel: FAMILY_INFERRED`, nenhuma unidade física
testada), não catálogo de compatibilidade confirmada. Se algum dia entrar como fonte de UI,
é decisão de produto do Rafael, não implícita nesta spec.

Perfis reais no manifesto ativo (6 profiles, deduplicados por vendor+model no que segue):

| Vendor | Model | Tipo | driverFamilyId | Estágio |
|---|---|---|---|---|
| Nokia | G-1425G-B | ONT | `nokia-ont-gpon-driver` | `READ_ONLY_ALPHA` |
| TP-Link | Archer C6 | ROUTER | `tplink-stok-luci-driver` (perfil ativo) | `READ_ONLY_ALPHA` |
| TP-Link | Archer C6 | ROUTER | `tplink-encrypted-web-driver` (perfil irmão, mecanismo antigo, sem unidade física confirmada) | `DRAFT` |
| TP-Link | Archer C20 | ROUTER | `tplink-legacy-cgi-driver` | `READ_ONLY_ALPHA` |
| TP-Link | Archer C50 v4 | ROUTER | `tplink-gdpr-cgi-driver` | `DRAFT` |
| TP-Link | TL-XDR3010 | ROUTER | `tplink-xdr-ds-driver` | `DRAFT` |

**Decisão de dedupe (Archer C6):** existem dois profiles para o mesmo modelo comercial
(mecanismos de firmware diferentes — `stok-luci` confirmado real vs. `encrypted-web` sem
unidade confirmada). Para toda superfície de UI (onboarding 1f, pareamento 2i), o Archer C6
aparece **uma única vez**, com o **melhor estágio entre os dois profiles** (`READ_ONLY_ALPHA`,
do perfil `stok-luci`). O Fingerprint Engine decide em runtime qual mecanismo usar — o usuário
nunca escolhe "C6 antigo" vs. "C6 novo" manualmente. Bruno: ao ler o catálogo para popular estas
telas, agrupar por `(vendor, model)` e usar `stage` de maior maturidade do grupo; não expor os
dois `driverFamilyId` ao usuário.

Vocabulário de estágio a exibir (nunca o enum cru em inglês para o usuário final):

| Estágio no catálogo | Rótulo de UI (PT-BR) | Onde aparece |
|---|---|---|
| `READ_ONLY_ALPHA` / `READ_ONLY_BETA` | "Leitura" | 1f (grupo "Compatível"), 2i (chip habilitado) |
| `DRAFT` / `DISCOVERY_ONLY` | "Em pesquisa" | 1f (grupo "Em pesquisa"), 2i (chip desabilitado) |
| `WRITE_BETA` / `STABLE` | "Leitura e escrita" | reservado — nenhum profile atinge isso hoje |

Nunca usar "Homologado"/"Suportado (Beta)" como no protótipo antigo — não é vocabulário de
`/ciclo-vida-driver` e sugere maturidade que os drivers reais não têm.

---

## Lote 1 — Onboarding

Ordem inalterada do protótipo: `1a` (boas-vindas, fora de escopo aqui) → **1b** → **1c** → `1d`
(notificações, fora de escopo aqui) → **1e** → fluxo de pareamento (`t2`). **1f** é acessível a
partir de `1a` (link "Ver dispositivos compatíveis"), não é um passo sequencial obrigatório —
mantido como no protótipo.

Progresso (dots) das 4 telas sequenciais do protótipo (`1b`→`1c`→`1d`→`1e`) permanece igual:
reaproveitar o componente de progress dots já usado (4 posições, pill ativo 20×6 accent, inativos
6×6 `#262F40`).

### 1b — Onboarding: Localização

**Gap encontrado:** o protótipo usa o botão primário "Permitir localização", sugerindo que esta
tela dispara o prompt real do Android. Isso conflita com `/regras-android-nethal` ("solicitação
feita em momento contextual — na tela de descoberta, nunca no cold start") e com o próprio
`AndroidManifest.xml` atual (permissão declarada, mas sem lógica de solicitação fora de
`DiscoveryScreen`).

**Decisão registrada:** 1b é **só preparatória/educativa**. Não chama
`requestPermissions(ACCESS_FINE_LOCATION)`. O prompt real do sistema continua disparando em
`DiscoveryScreen` (tela `2a`), no momento em que o scan de Wi-Fi de fato acontece — inclusive o
rationale exibido em negativa prévia é responsabilidade de `2a`, não desta tela.

**Mudança de conteúdo vs. protótipo:**
- Botão primário: `"Permitir localização"` → **`"Entendi, continuar"`** (não promete ação que não
  executa).
- Remover o link secundário `"Agora não"` — não há nada para recusar aqui; sem CTA duplo, sem
  fricção artificial.
- Corpo do texto mantido, mas reforçar honestidade sobre uso: **"O Android exige acesso à
  localização para ler o nome (SSID) da rede Wi-Fi conectada — é assim que identificamos seu
  roteador. O NetHAL não coleta nem usa sua localização geográfica."** (a frase final é nova —
  honestidade de risco: deixa explícito o que a permissão NÃO faz, já que "localização" é um nome
  de permissão Android historicamente mal entendido).
- Ícone/imagem, progress dots, layout, tipografia: mantidos como no protótipo (pin de localização
  com anéis concêntricos, título 24/700, corpo 13/400 `#8891A8`).

**Componentes:** reaproveitados 100% do design system (botão primário fill accent, ícone outline,
progress dots). Nenhum componente novo.

### 1c — Onboarding: Dispositivos próximos

**Gap encontrado — o mais sério dos dois lotes:** o protótipo usa o ícone/símbolo de Bluetooth
(o path SVG é literalmente o símbolo "B" do Bluetooth) e a copy **"Necessário para parear e
configurar novos pontos de acesso via Bluetooth."** O NetHAL não tem nenhuma permissão de
Bluetooth declarada no `AndroidManifest.xml` — discovery é 100% Wi-Fi/LAN (SSDP/mDNS/gateway via
`ConnectivityManager`/`LinkProperties`), nunca Bluetooth. Isso é uma violação direta do critério
de aceite da issue #70 ("copy honesta sobre o escopo real: descoberta é só na rede local — LAN —
do usuário") e do escopo documentado em `CLAUDE.md`.

**Decisão registrada:** reescrever a tela do zero em cima do mesmo layout (imagem central + título
+ corpo + CTA), sem herdar ícone nem copy do protótipo.
- Ícone: trocar o símbolo de Bluetooth pelo ícone `router` do set oficial
  (`docs/design/assets/icons/dark|light/router`), consistente com a tela `2a` (radar de busca de
  roteador) — reforça visualmente que é a MESMA busca, não uma tecnologia diferente.
- Título: `"Dispositivos próximos"` → **`"Sua rede Wi-Fi local"`**.
- Corpo: **"O NetHAL procura equipamentos só dentro da sua rede Wi-Fi local (LAN) — nunca fora
  dela, nunca na internet. Nada é enviado para fora do seu roteador."**
- Sem permissão real associada a esta tela — é só educativa (a permissão de localização, única
  necessária para o scan de Wi-Fi, já foi coberta em `1b`). Botão primário: **`"Entendi,
  continuar"`**, sem CTA secundário "Agora não" (mesma razão de `1b` — nada a recusar).

**Componentes:** reaproveitados do design system. Zero componentes novos, mas ativo bloqueado de
reuso indevido do ícone Bluetooth do protótipo antigo (não existe no set oficial de ícones do
NetHAL — nem deveria passar a existir).

### 1e — Onboarding: Permissões concedidas

Fiel ao protótipo, sem gaps. Ajuste de conteúdo:
- Resumo textual deve refletir estado real (localização concedida ou não, notificações concedidas
  ou não) em vez do check genérico único do protótipo — trocar o ícone de check único central por
  **lista curta de até 2 itens** (localização, notificações), cada um com check verde (`#10B981`)
  se concedido ou um traço neutro `#4C5567` se negado — nunca ícone de erro/vermelho aqui, negar
  permissão não é erro.
- Copy adaptativa: se **tudo** concedido → `"Permissões concedidas"` / `"Agora vamos localizar e
  parear seu roteador na rede."` (igual ao protótipo). Se **alguma** negada → título
  `"Podemos continuar"` / corpo `"Sem [localização/notificações], [o que degrada — ex.: a busca
  automática do roteador não vai funcionar; você pode inserir o IP manualmente]."` — nunca trava
  o fluxo (`/regras-android-nethal`, AC da issue #72).
- CTA sempre disponível: `"Parear roteador →"`, igual ao protótipo, navega para `2a`.

**Componentes:** ícone de check reutilizado (mesmo estilo do protótipo, `#10B981`, stroke 2.2);
lista de status é composição nova, mas usa tokens existentes (nenhum componente do zero).

### 1f — Onboarding: Dispositivos compatíveis

**Gap encontrado:** o protótipo lista dispositivos fictícios (`Nokia G-140W-C`, `Nethal Mesh X2`,
`TP-Link Archer AX55`, `Intelbras Wynk 1200`, `Zyxel PMG3000`) sob rótulos "HOMOLOGADOS" /
"SUPORTADOS (BETA)" — nenhum desses modelos existe no catálogo real, e os rótulos não batem com
`/ciclo-vida-driver`. Isso é exatamente o risco que a issue #73 pede para evitar: lista estática
desatualizada, sem lastro no catálogo.

**Decisão registrada:** reescrever o conteúdo com dado real do catálogo (ver §0), mantendo o
layout de card agrupado do protótipo (header com voltar + título, dois grupos com overline +
card de lista).

- **Grupo 1 — overline `"LEITURA DE DADOS"`** (estágio `READ_ONLY_ALPHA`/`READ_ONLY_BETA`):
  Nokia G-1425G-B (ONT), TP-Link Archer C6 (Roteador), TP-Link Archer C20 (Roteador). Cada linha:
  nome do modelo + subtítulo com tipo (`"ONT"` / `"Roteador"`) — sem inventar imagem de produto
  quando não houver asset real (usar o `image-slot` placeholder já existente, não uma foto
  genérica que pareça o produto real).
- **Grupo 2 — overline `"EM PESQUISA — AINDA NÃO FUNCIONA"`** (estágio `DRAFT`/`DISCOVERY_ONLY`):
  TP-Link Archer C50 v4, TP-Link TL-XDR3010. Nota abaixo do grupo, tipografia terciária
  (`#4C5567`, 11/400): **"Estamos testando estes modelos — ainda não leem dados reais do
  equipamento. Podem entrar no grupo acima em atualizações futuras."** — nunca prometer prazo.
- **Aviso de escopo obrigatório** (novo, não existe no protótipo — exigido pela issue: "deixa
  visualmente claro que a lista é parcial/em expansão"): banner discreto no topo da lista,
  abaixo do header, texto secundário `#8891A8` 12/400: **"Lista parcial. O NetHAL não funciona
  com qualquer roteador — só com os modelos abaixo."** Sem ícone de alerta (não é erro, é
  transparência de escopo) — só texto, fundo transparente, sem card.
- CTA final `"Recomendar um modelo"` mantido do protótipo (mesmo estilo outline neutro) — mantém
  canal de feedback do usuário, sem promessa de prazo de atendimento.
- Remover completamente o asterisco `"* Podem apresentar falhas..."` do protótipo (rótulo
  "SUPORTADOS (BETA)" não existe mais) — a nota do Grupo 2 já cobre a mensagem de forma mais
  honesta (não é "falha ocasional", é "não funciona ainda").

**Componentes:** reaproveita card de lista agrupada, overline, `image-slot` — todos já existentes.
Banner de aviso de escopo é composição nova (texto simples, sem card/ícone), não um componente
com estado a manter.

---

## Lote 2 — Pareamento, cluster de seleção manual (2g → 2h → 2i)

Sequência estrita mantida do protótipo: `2g` (tipo) → `2h` (fabricante, filtrado por tipo) → `2i`
(modelo, filtrado por fabricante) → login (`2c`). Chip de trilha (breadcrumb) no topo de `2h`/`2i`
mantido do protótipo (pill preenchido para a etapa escolhida, texto neutro para a etapa atual).

### Decisão registrada — entrada manual por IP (pergunta aberta da issue #80)

A entrada de IP manual (hoje em `DiscoveryFailedScreen`/`MultipleCandidatesScreen`) **não pode
desaparecer**. Decisão: ela vive dentro do cluster, como a última opção de `2h`
("Selecionar fabricante"), reaproveitando o item `"Outro / não sei"` que já existe no protótipo
mas hoje não tem destino definido. Fluxo corrigido:

- Em `2h`, o item `"Outro / não sei"` deixa de navegar para `2i` (não existe "modelo" para
  fabricante desconhecido) e passa a navegar para uma tela de entrada manual de IP — reaproveita
  o campo de texto livre já implementado em `DiscoveryFailedScreen`/`MultipleCandidatesScreen`
  (Bruno: mover/extrair esse componente de campo de IP para reuso aqui, não duplicar código).
- `DiscoveryFailedScreen` (usado hoje quando a descoberta automática falha totalmente) passa a
  oferecer dois caminhos, não um campo solto: **"Selecionar equipamento"** (entra no cluster por
  `2g`) e **"Informar IP manualmente"** (vai direto para o mesmo campo de IP usado a partir de
  `"Outro / não sei"`). Isso substitui a experiência "só campo de texto cru" por um caminho guiado
  como caminho principal, com o IP manual como fallback explícito — não removido, rebaixado a
  opção avançada.
- `MultipleCandidatesScreen` (múltiplos candidatos achados) não muda — não é este cluster, é
  desambiguação entre candidatos já descobertos.

### 2g — Selecionar tipo

**Gap encontrado:** o protótipo lista 4 tipos (`Modem`, `Roteador`, `ONT`, `Mesh`) tratados como
igualmente disponíveis. `"Modem"` não existe no `DeviceType` real do domínio
(`core/.../model/DeviceInfo.kt`: `ROUTER`, `ONT`, `MESH`, `AP`) — é confusão coloquial PT-BR entre
modem/roteador. E nenhum dos 4 tipos tem cobertura real igual: só `ROUTER` e `ONT` têm profile no
catálogo hoje.

**Decisão registrada:**
- Trocar `"Modem"` por **`"Ponto de acesso"`** (rótulo de `AP`), alinhando com o enum real.
- 4 opções continuam visíveis (não esconder tipo sem driver — usuário pode ter o equipamento
  mesmo sem suporte ainda), mas **`Roteador`** e **`ONT`** ficam no estado padrão habilitado
  (batem com catálogo real) e **`Mesh`**/**`Ponto de acesso`** usam o **estado desabilitado**
  documentado no design system: opacidade 38–45% no item inteiro, continua tocável, abre diálogo
  explicando — texto do diálogo: **"Ainda não há driver para [Mesh/Ponto de acesso] no NetHAL.
  Hoje cobrimos Roteador e ONT."** com ação única "Entendi". Nunca remover a opção do toque nem
  deixá-la muda.
- Seleção de `Roteador` (destacado accent no protótipo) vira o estado padrão só quando o usuário
  toca — protótipo mostra "Roteador" pré-selecionado visualmente, o que é só um exemplo estático
  do Claude Design; no Compose real nenhuma opção começa pré-selecionada.

### 2h — Selecionar fabricante

**Gap encontrado:** o protótipo lista fabricantes fixos (`Nokia`, `TP-Link`, `Intelbras`,
`Huawei`, `Zyxel`, `Outro/não sei`) sem filtrar pelo tipo escolhido em `2g` — viola direto o
critério de aceite da issue #81 ("filtrada pelo tipo escolhido"). Além disso, `Intelbras`,
`Huawei` e `Zyxel` não têm nenhum profile no catálogo real — listá-los promete suporte que não
existe.

**Decisão registrada — filtro real por tipo:**

| Tipo escolhido em 2g | Fabricantes reais no catálogo |
|---|---|
| Roteador | TP-Link |
| ONT | Nokia |
| Mesh / Ponto de acesso | nenhum (tipo já chega desabilitado em `2g`, esta tela não é alcançada) |

- Lista final: fabricante(s) real(is) do tipo + **sempre** o item **`"Outro / não sei"`** por
  último (agora com destino real: entrada manual de IP, ver decisão acima).
- Com só 1 fabricante real por tipo hoje, a tela fica curta (1 item + "Outro/não sei") — isso é
  correto e honesto, não um bug de layout a "preencher" com fabricantes inventados. Se o usuário
  seleciona o único fabricante real, pode-se opcionalmente pular a etapa (ir direto para `2i`)
  como otimização — **decisão de produto do Rafael**, não assumida aqui; a spec cobre o caso
  geral (etapa sempre visível, navegação manual).
- Remover avatares de iniciais fictícios de fabricantes descartados (`I`, `H`, `Z`). Mantido:
  avatar de iniciais accent para o fabricante real (`N` Nokia, `T` TP-Link) e neutro para
  `"Outro / não sei"` (ícone `?`, já no protótipo).

### 2i — Selecionar modelo

**Gap encontrado:** o protótipo lista, sob o filtro "Roteador > Nokia", modelos que não são
roteadores nem existem (`G-140W-C` como "ONT", `XS-2426G-A` como "Mesh", `G-2425G-A` como
"Modem") — o próprio protótipo não respeita seu próprio filtro de tipo, e nenhum desses modelos
tem profile no catálogo.

**Decisão registrada:**
- Lista filtrada por `(tipo de 2g) × (fabricante de 2h)`, direto do catálogo real (ver tabela §0).
  Exemplos:
  - Roteador + TP-Link → Archer C6 (Leitura), Archer C20 (Leitura), Archer C50 v4 (Em pesquisa),
    TL-XDR3010 (Em pesquisa).
  - ONT + Nokia → G-1425G-B (Leitura). Lista de 1 item — layout de lista continua igual, sem
    necessidade de estado especial para lista de tamanho 1.
- Cada linha ganha **chip de estágio** (reaproveita o componente "Status chip" do design system —
  outline pill, 600/11): chip neutro/accent outline `"Leitura"` para `READ_ONLY_ALPHA`/`BETA`;
  chip outline `#4C5567` `"Em pesquisa"` para `DRAFT`/`DISCOVERY_ONLY`. Isto é o requisito
  explícito da issue #82 ("nunca dar a entender suporte pronto quando o driver está em estágio
  inicial").
- Modelos `"Em pesquisa"` usam o mesmo **estado desabilitado** do design system (opacidade
  38–45%, tocável, abre diálogo): **"[Modelo] ainda está em pesquisa — o NetHAL não consegue ler
  dados dele ainda. Você pode continuar mesmo assim, mas é bem provável que a conexão falhe."**
  com duas ações: `"Cancelar"` e `"Continuar mesmo assim"` (mesma filosofia de honestidade de
  risco da Vera — não bloqueia o usuário avançado que quer tentar, mas não deixa ninguém achar
  que vai funcionar). Se o usuário escolher `"Continuar mesmo assim"`, segue para `2c` normalmente.
- Modelos `"Leitura"` navegam direto para `2c` ao toque, sem diálogo intermediário — resposta à
  pergunta aberta da issue #82 ("2b ou 2c"): **`2c` (login), não `2b`**. `2b` ("Dispositivo
  encontrado") é o passo de confirmação da descoberta *automática*; na seleção manual, o ato de
  escolher o modelo na lista já é a confirmação — inserir `2b` depois seria um passo redundante
  que não agrega decisão nova ao usuário. Consistente com o próprio protótipo original (`2i`→`2c`
  no href).

**Componentes:** chip de estágio = "Status chip" já documentado, reaproveitado com paleta neutra
para "Em pesquisa" (não é erro nem aviso, então não usa vermelho/âmbar — usa `#4C5567` outline,
tom neutro reservado a "não disponível ainda", coerente com o uso de terciário do design system
para essa hierarquia). Diálogo de confirmação "continuar mesmo assim" reaproveita o componente
"Diálogo" documentado (título 15/700, corpo 11.5–13/400, 2 ações à direita).

---

## Resumo de decisões para o Bruno implementar

1. **1b/1c**: nenhuma das duas dispara permissão real — só a tela `2a` (Discovery) pede
   `ACCESS_FINE_LOCATION` de verdade. CTA único `"Entendi, continuar"` nas duas, sem "Agora não".
2. **1c**: remover toda referência/ícone de Bluetooth — não existe no NetHAL. Ícone `router`,
   copy sobre LAN.
3. **1e**: resumo adaptativo (concedido/negado), nunca trava o fluxo.
4. **1f**: ler catálogo real via `DriverRegistry`, deduplicar Archer C6 por `(vendor, model)`,
   dois grupos (`Leitura de dados` / `Em pesquisa — ainda não funciona`), banner de escopo parcial
   obrigatório.
5. **2g**: 4 tipos visíveis, `Roteador`/`ONT` habilitados, `Mesh`/`Ponto de acesso` desabilitados
   (estado desabilitado do design system, nunca escondidos). Trocar `"Modem"` por
   `"Ponto de acesso"`.
6. **2h**: fabricantes filtrados por tipo, só os reais do catálogo + `"Outro / não sei"` sempre
   por último — este item agora navega para a entrada manual de IP.
7. **2i**: modelos filtrados por `(tipo, fabricante)`, chip de estágio por modelo, modelos `DRAFT`
   usam estado desabilitado + diálogo "continuar mesmo assim"; confirmação navega para `2c`
   (login), nunca `2b`.
8. **Entrada de IP manual**: não removida — vive como destino do item `"Outro / não sei"` em `2h`
   e como ação explícita em `DiscoveryFailedScreen` ("Informar IP manualmente"), ao lado de
   "Selecionar equipamento" (entra no cluster por `2g`). Reaproveitar o campo já implementado em
   `DiscoveryFailedScreen`/`MultipleCandidatesScreen`, não duplicar.

## Revisão de risco pendente

Nenhuma destas 7 telas envolve ação de escrita (reboot, troca de senha/canal/SSID) — é descoberta
e seleção de equipamento, sem write capability em jogo. Não há copy de risco de escrita para a
Marisa validar neste lote. A única honestidade de risco aqui é de **escopo** (LAN-only, lista de
compatibilidade parcial, estágio de driver visível) — já incorporada nas decisões acima. Marisa
revisa como QA normal no gate de Done, sem necessidade de sign-off prévio.
