# Compatibility Catalog — formato do manifesto

Este documento descreve o schema real do catálogo de compatibilidade offline, versionado em
`core/src/main/resources/catalog/catalog-<YYYY.MM.DD>.json`, embarcado no NetHAL Core como
fallback local para o Driver Registry (§8.5 da spec de produto). O Driver Registry (implementação
do Bruno, Feat 3 / SIG-309) carrega este arquivo como base offline e sincroniza contra uma versão
remota mais nova quando houver conexão, sem nunca bloquear uso offline — mesma regra do §8.5.

## Por que Nokia G-1425G-B e TP-Link Archer C6 são os dois primeiros profiles

Critério único e explícito: são os dois únicos equipamentos com **acesso físico real para teste**,
citados no project charter. Toda promoção de estágio de driver exige teste real documentado
(modelo + firmware, ver `/ciclo-vida-driver`) — não faz sentido descrever profiles em detalhe para
equipamentos que o squad não consegue testar fisicamente ainda. Outros alvos da matriz de
priorização (`docs/architecture/driver-adoption-strategy.md`) continuam válidos como roadmap, mas
entram no catálogo quando houver acesso físico ou parceria de teste equivalente.

> **Correção de modelo (2026-07-07):** o manifesto `catalog-2026.07.06.json` pesquisou o Nokia
> como **G-1425G-A** por engano. O Luiz confirmou que a unidade física de teste do NetHAL é o
> **G-1425G-B** — mesmo modelo do driver Nokia já em produção no SignallQ (produto irmão). O
> profile foi recriado (`nokia_g1425ga_v1` → `nokia_g1425gb_v1`) no manifesto
> `catalog-2026.07.07.json`, com evidência de fingerprint agora vinda de código de produção real
> (ver seção "Fontes consultadas" abaixo), não mais de pesquisa documental de terceiros sobre o
> modelo errado.

## Localização e nomenclatura

```text
core/src/main/resources/catalog/catalog-YYYY.MM.DD.json
```

- Um arquivo por versão de manifesto — nunca sobrescrever a versão anterior, permitindo diff
  incremental e rollback (conforme "Catálogo offline e sincronização" em
  `driver-adoption-strategy.md`).
- `manifestVersion` dentro do JSON deve ser idêntico à data no nome do arquivo.
- `previousManifest` referencia o nome do arquivo anterior (ou `null` no primeiro manifesto),
  permitindo ao Driver Registry calcular o diff incremental sem precisar reprocessar tudo.

## Estrutura do manifesto

```jsonc
{
  "$schema": "https://nethal.dev/schema/compatibility-catalog-v1.json",
  "manifestVersion": "2026.07.06",
  "generatedAt": "2026-07-06T00:00:00Z",
  "generatedBy": "diego-drivers-protocolos",
  "previousManifest": null,          // nome do manifesto anterior, para diff incremental
  "profiles": [ /* ver abaixo */ ]
}
```

## Estrutura de cada `profile`

Um `profile` é a unidade de compatibilidade — mapeia 1:1 para um `vendor` + `model` (não
firmware individual; firmwares testados ficam em `firmwareKnown[]` dentro do profile). Campos:

| Campo | Tipo | Obrigatório | Descrição |
|---|---|---|---|
| `profileId` | string | sim | Identificador estável, `snake_case`, usado pelo Driver Registry para resolver o driver correspondente em `drivers/<vendor_family>/`. |
| `vendor` | string | sim | Nome do fabricante, forma canônica (ex.: `"Nokia"`, `"TP-Link"`). |
| `model` | string | sim | Modelo exato testado/pesquisado. Nunca genérico ("Archer" sozinho não vale). |
| `deviceType` | enum | sim | Mesmo vocabulário de `DeviceInfo.DeviceType` em `core` (`ROUTER`, `ONT`, `ONU`, `MESH`, `AP`, `REPEATER`, `UNKNOWN`). |
| `family` | string | sim | Descrição textual da família/linha do produto, para contexto humano. |
| `firmwareKnown` | string[] | sim (pode ser vazio) | Firmwares confirmados por teste real. Vazio até o primeiro teste documentado. |
| `stage` | enum | sim | Estágio do profile — mesmo vocabulário de `/ciclo-vida-driver`: `DRAFT`, `DISCOVERY_ONLY`, `READ_ONLY_ALPHA`, `READ_ONLY_BETA`, `WRITE_BETA`, `STABLE`, `DEPRECATED`, `BLOCKED`. |
| `stageReason` | string | sim | Por que o profile está neste estágio agora — obrigatório, nunca deixar implícito. |
| `physicalTestAccess` | boolean | sim | Se o squad tem a unidade física disponível para teste real. |
| `physicalTestAccessNote` | string | não | Contexto sobre o acesso físico (quando obtido, o que falta testar). |
| `managementDefaults` | object | sim | IPs candidatos, porta e nível de confiança de cada um (ver abaixo). |
| `credentialConvention` | object | sim | Convenção de credencial *documental* — nunca usada para tentativa automática de login (ver regra de segurança abaixo). |
| `fingerprintEvidence` | object[] | sim | Lista de evidências de fingerprint, cada uma com `type`, `value`, `confidence`, `confidenceLevel`, `source`, `note`. |
| `expectedProtocols` | object[] | sim | Protocolos esperados/detectados, com `protocol`, `detectionState` (vocabulário de `/protocolos-locais`: `SUPPORTED`/`DETECTED_BUT_UNSUPPORTED`/`REQUIRES_AUTH`/`BLOCKED`/`UNKNOWN`), `note`. |
| `capabilities` | object[] | sim | Capabilities candidatas (vocabulário `CapabilityId`), cada uma com `id`, `state` (vocabulário `CapabilityState`), `reason` obrigatório quando `state != AVAILABLE`. |
| `knownFirmwareBugs` | object[] | não | Bugs de firmware conhecidos, com `description`, `confidence`, `source`. |
| `operatorProvisioningRisk` | object | sim para CPE-ISP | `risk` (`LOW`/`MEDIUM`/`HIGH`) e `note` — sinaliza se o equipamento é gerenciado por ACS de operadora e pode ter configuração revertida. |
| `confidenceScoreOverall` | number (0–1) | sim | Score agregado do profile, calculado pela heurística de `/protocolos-locais` (ver seção abaixo). |
| `confidenceScoreOverallNote` | string | sim | Como o score foi calculado, item a item — auditável por Marisa/Rafael. |

### `managementDefaults`

```jsonc
{
  "candidateIps": ["192.168.0.1", "192.168.1.1", "tplinkwifi.net"],
  "ipConfidence": 0.85,
  "ipConfidenceNote": "por que esse número, com fonte",
  "managementPort": 80,
  "managementPortNote": "por que essa porta, com fonte"
}
```

### `credentialConvention`

```jsonc
{
  "defaultUser": "AdminGPON",
  "defaultPasswordPattern": "ALC#FGU (ou variante por operador)",
  "confidence": 0.4,
  "confidenceNote": "fonte e ressalvas",
  "policyNote": "NUNCA usar como tentativa automática de login — só fingerprint passivo ou sugestão manual ao usuário"
}
```

Este campo é **documental**, nunca operacional. Nenhum código do NetHAL deve ler
`credentialConvention` para preencher um formulário de login automaticamente — isso violaria a
regra de "nada de bypass de auth, brute-force ou uso automático de senha padrão" do `CLAUDE.md` e
do `SECURITY.md`. O único uso legítimo é: (a) fingerprint passivo (ex.: a string aparece em uma
página pública), ou (b) UI sugerindo ao usuário "experimente a senha da etiqueta do equipamento".

### `fingerprintEvidence[]`

```jsonc
{
  "type": "html_title" | "http_headers" | "management_protocol" | "webui_menu_structure" |
          "auth_mechanism" | "session_behavior" | "vendor_app_reference" | "product_documentation",
  "value": <string|string[]|null>,
  "confidence": 0.0,
  "confidenceLevel": "NONE_VERIFIED" | "LOW" | "MEDIUM" | "MEDIUM_HIGH" | "HIGH",
  "source": "URL ou documento exato consultado",
  "note": "ressalva, limitação, ou por que o valor é null"
}
```

Regra inegociável: se a evidência não foi capturada diretamente (probe real, HTML bruto, header
real), `value` fica `null` e `confidence` fica `0.0`. Nunca preencher com valor plausível "para não
deixar em branco". O campo `source` é sempre obrigatório mesmo quando `value` é `null` — documenta
o que foi tentado e por que falhou (ex.: HTTP 403 no fetch).

### Scoring de confiança (`confidenceScoreOverall`)

Segue a heurística de `/protocolos-locais` / `driver-adoption-strategy.md`, seção "Scoring de
confiança":

- 0,25 — match de headers/banners reais (Server, WWW-Authenticate, título HTML, marca em XML)
- 0,20 — match de descritor/endpoint canônico real
- 0,20 — autenticação bem-sucedida testada no modo esperado
- 0,15 — capability sanity check coerente (leitura real validada)
- 0,10 — firmware/modelo presente no catálogo offline (documentação oficial confirmada)
- 0,10 — evidência comunitária/histórico local bem-sucedido

Regras de decisão por faixa de score (aplicam-se quando o profile avançar de `DRAFT` e um probe
real começar a produzir score dinâmico, não ao score estático documental deste manifesto):

- `< 0,50` → só leitura passiva, sem autenticação adicional
- `0,50–0,75` → leitura autenticada permitida
- `0,75–0,90` → escrita não destrutiva permitida
- `> 0,90` → reboot e mudanças sensíveis só com consentimento explícito

Os dois profiles deste manifesto (`0.25` e `0.35`) refletem pesquisa documental, não probe real —
ambos abaixo de `0.50` porque nenhum header/título/autenticação foi capturado diretamente ainda.

## Ciclo de vida de um profile novo no catálogo

1. **Entrada em `DRAFT`**: profile criado com o máximo de evidência documental pública verificável
   (fabricante, comunidade, bibliotecas de reuso), mas sem nenhum probe real. É o estado deste
   manifesto para Nokia G-1425G-B e TP-Link Archer C6 — mesmo o Nokia tendo, nesta rodada, uma
   implementação de driver real em `core/driver/nokia` (ver nota abaixo), o `stage` permanece
   `DRAFT` porque nenhum probe/login foi executado pelo NetHAL contra a unidade física.
2. **`DRAFT → DISCOVERY_ONLY`**: primeiro probe passivo real contra a unidade física (GET simples
   na tela de login, captura de headers, sem autenticação) documentado com timestamp e output real.
   Atualiza `fingerprintEvidence[]` substituindo `value: null` por valores capturados e recalcula
   `confidenceScoreOverall`.
3. **`DISCOVERY_ONLY → READ_ONLY_ALPHA`**: pelo menos um teste real de leitura autenticada (modelo
   + firmware) documentado, conforme `/ciclo-vida-driver`.
4. Estágios seguintes seguem exatamente `/ciclo-vida-driver` — este documento não duplica aquela
   skill, apenas aponta onde o estágio do profile fica registrado (campo `stage` do manifesto).

## Fonte de evidência "driver de produção de produto irmão"

O profile `nokia_g1425gb_v1` (manifesto `2026.07.07`) introduz uma categoria de evidência mais
forte que "documentação pública de terceiros": código-fonte real de um driver de produção,
rodando em campo, do produto irmão SignallQ (`C:\Projetos\SignallQ\android\feature\fibra`), contra
o mesmo modelo exato de hardware. Isso não é probe do NetHAL — o `stage` do profile continua
`DRAFT` e a heurística de score trata essa fonte como "evidência comunitária/histórico local"
(0,10 do teto da categoria), não como "autenticação testada" ou "capability sanity check" (que só
pontuam quando o próprio NetHAL executa o teste). Ver `fingerprintEvidence[].source` do profile
para a citação exata de cada arquivo do SignallQ usado como referência.

O driver correspondente no NetHAL (`core/src/main/kotlin/com/nethal/core/driver/nokia/`) foi
implementado do zero para o vocabulário do NetHAL, usando o código do SignallQ como referência de
protocolo (handshake RSA+AES, endpoints, conversões de unidade, aliases de campo incluindo o typo
conhecido de firmware `SupplyVottage`) — não é uma cópia literal de arquivo.

## Limitação conhecida — TOFU no handshake do driver Nokia

O login do `NokiaOntDriver` busca a chave pública RSA na própria página de login do equipamento
(`GET /`), sem certificado nem pinagem — é *trust on first use* (TOFU), inerente ao protocolo do
firmware Nokia, não uma escolha do NetHAL nem algo evitável sem quebrar compatibilidade com o
equipamento real. Isso foi levantado por Marisa na revisão de segurança do PR #6 (driver Nokia).

Mitigação já implementada: `NokiaOntDriver` recusa construir contra qualquer host que não seja IP
privado (RFC 1918) — ver `PrivateIpRanges` em `core/src/main/kotlin/com/nethal/core/discovery/`.
Isso reduz o risco a "host malicioso dentro da própria LAN do usuário", não elimina o TOFU em si.

**Pendência antes de `READ_ONLY_BETA`**: quando a Tela 5 (Autenticação, spec §11) for implementada,
ela precisa avisar explicitamente o usuário sobre essa limitação antes do primeiro login — algo
como "este equipamento não permite verificar a autenticidade do host antes de enviar sua senha;
use apenas na sua própria rede confiável". Não é bloqueante para o driver continuar em `DRAFT`.

## Consumo pelo Driver Registry (fora de escopo desta entrega)

O parsing/deserialização deste JSON em Kotlin, a lógica de diff incremental entre manifestos e a
integração com o `DriverRegistry` real são responsabilidade do Bruno na Feat 3 (SIG-309). Este
documento e o `catalog-2026.07.07.json` são o insumo de dados — não incluem implementação de
parser, cliente HTTP de sincronização ou testes de unidade Kotlin (o `DriverRegistry` em si já
existe e está coberto por testes próprios em `core/src/test/kotlin/com/nethal/core/catalog/`,
mas a lógica de diff incremental entre manifestos permanece não implementada).

## Fontes consultadas — manifesto `2026.07.07` (2026-07-07)

- **Nokia G-1425G-B**: código-fonte real do driver Nokia de produção do SignallQ
  (`C:\Projetos\SignallQ\android\feature\fibra\...\NokiaModemClient.kt`,
  `NokiaModemCrypto.kt`, `NokiaModemParser.kt`, `ExecutorFibra.kt`) — driver autenticado rodando em
  campo contra este exato modelo de hardware. Fonte de confiança muito mais alta que pesquisa
  documental de terceiros, mas ainda não é um teste do próprio NetHAL (ver seção "Fonte de
  evidência 'driver de produção de produto irmão'" acima).
- **TP-Link Archer C6**: inalterado nesta rodada — mesmas fontes do manifesto anterior (emulador
  oficial TP-Link, SetupRouter.com, GitHub `AlexandrErohin/TP-Link-Archer-C6U`).

## Fontes consultadas — manifesto `2026.07.06` (histórico, modelo Nokia incorreto)

Ver `fingerprintEvidence[].source` de cada profile no próprio JSON (`catalog-2026.07.06.json`)
para a citação exata por evidência. Resumo das fontes de maior peso:

- **Nokia G-1425G-A (modelo incorreto — corrigido para G-1425G-B no manifesto `2026.07.07`)**:
  manuals.plus (identificação do documento oficial "Nokia ONT G-1425G-A Product Guide",
  3FE-77771-AAAA-TCZZA Issue 5, Nov/2021 — conteúdo bloqueado por HTTP 403 no fetch direto);
  made4it.com.br (TR-069/DHCP Option 43 para G-1425-GA); ManualsLib (G-1425G-E, variante irmã,
  estrutura de menu e TR-369); router-network.com e knowledgebase.bison.co.in (convenção
  AdminGPON/ALC#FGU e IP 192.168.1.254 para a família Nokia GPON, não confirmado especificamente
  para o G-1425G-A); hack-gpon.org (G-010G-Q, explicitamente tratado como *não* extrapolável ao
  G-1425G-A por ser hardware/chipset diferente).
- **TP-Link Archer C6**: emulador oficial do fabricante
  (https://emulator.tp-link.com/c6-us-v2/index.html — fonte primária para estrutura de menu e
  confirmação de app companion "TP-Link Tether"); documentação oficial TP-Link (IP padrão, guia de
  habilitação de HTTPS local); SetupRouter.com (IP/porta/comportamento de primeiro login por
  hardware v2/v4); GitHub `AlexandrErohin/TP-Link-Archer-C6U` (mecanismo de auth AES-CBC/AES-GCM,
  já registrado como reuso conhecido em `driver-adoption-strategy.md`).

Nenhum probe HTTP direto (GET real contra `Server`/`title`) foi executado nesta rodada — as
ferramentas de pesquisa disponíveis (WebSearch/WebFetch) não substituem um probe de rede real
contra a unidade física. Isso é declarado explicitamente no manifesto (`value: null`,
`confidenceLevel: NONE_VERIFIED`) para as duas evidências mais importantes de fingerprint HTTP
(título HTML e headers) em ambos os profiles.

## Changelog

- **2026-07-07** — Corrigido modelo Nokia de `G-1425G-A` para `G-1425G-B` (a unidade física de
  teste do NetHAL, confirmada pelo Luiz, é o G-1425G-B; o manifesto anterior pesquisou o modelo
  errado). Novo manifesto `catalog-2026.07.07.json` (`previousManifest: catalog-2026.07.06.json`)
  com `profileId` novo (`nokia_g1425ga_v1` → `nokia_g1425gb_v1`), evidência de fingerprint agora
  citando o driver de produção do SignallQ como fonte, e `confidenceScoreOverall` recalculado de
  `0.25` para `0.55`. Implementado driver Nokia real (leitura, 4 endpoints) em
  `core/src/main/kotlin/com/nethal/core/driver/nokia/`. Profile TP-Link mantido inalterado.
- **2026-07-06** — Manifesto inicial (`catalog-2026.07.06.json`), dois profiles em `DRAFT`
  (Nokia G-1425G-A — modelo incorreto — e TP-Link Archer C6), evidência 100% documental.
