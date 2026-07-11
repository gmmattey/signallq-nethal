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
| `productLine` | string | sim | Descrição textual da família/linha comercial do produto, para contexto humano (ex.: `"Archer"`). Renomeado de `family` (ver changelog `2026-07-07`) para não colidir com `driverFamilyId`, que é outro conceito. |
| `platformId` | string | sim | Metadado de catálogo identificando a plataforma tecnológica compartilhada (protocolo + autenticação), ex.: `"tplink-encrypted-web"`, `"tplink-legacy-cgi"`, `"nokia-gpon-rsa-aes"`. Ver `docs/architecture/hal-layering-model.md` §5.2/§11.1 — é deliberadamente `String` simples, sem tipo Kotlin próprio nesta rodada. |
| `driverFamilyId` | string | sim | Chave de resolução no `DriverFamilyRegistry` (`docs/architecture/hal-layering-model.md` §5.5/§10 — implementado desde o passo 4/6). Mapa fixo `driverFamilyId -> DriverFamilyFactory`, montado uma única vez na inicialização do `core` (`com.nethal.core.driver.family.defaultDriverFamilyRegistry()`), nunca via reflection. O valor é a chave literal usada para registrar a factory correspondente (ex.: `"tplink-legacy-cgi-driver"`, registrado por `TpLinkLegacyCgiDriverFamilyFactory`). |
| `driverConfig` | `JsonElement` | não (default `null`) | Payload opaco de configuração específica do driver (endpoints, seções, mapeamento de campos) que cada Driver Family interpreta do seu próprio jeito. Deliberadamente sem schema comum entre plataformas diferentes (TP-Link vs Nokia) — ver changelog `2026-07-07` (passo 4) para o primeiro schema concreto, o do profile `tplink_archer_c20_v1`/`tplink-legacy-cgi-driver`. Continua `null` para profiles cuja Driver Family ainda não foi criada. |
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

## Limitação conhecida — TOFU no handshake stok/luci do TP-Link Archer C6

O login do `TpLinkStokLuciDriverFamily` (profile `tplink_archer_c6_stok_v1`) busca duas chaves
públicas RSA distintas do próprio host, sem certificado nem pinagem — `POST
/cgi-bin/luci/;stok=/login?form=keys` (chave de cifra de senha, 1024-bit) e `POST
/cgi-bin/luci/;stok=/login?form=auth` (chave de assinatura do envelope `sign`, 512-bit, + `seq`) —
mesma classe de risco *trust on first use* (TOFU) já documentada acima para o Nokia, inerente ao
protocolo desse firmware TP-Link, não uma escolha do NetHAL.

Mitigação já implementada: `TpLinkStokLuciDriverFamily` recusa construir contra qualquer host que
não seja IP privado (RFC 1918) — mesma guarda `PrivateIpRanges` usada por todos os drivers. Isso
reduz o risco a "host malicioso dentro da própria LAN do usuário", não elimina o TOFU em si.

**Pendência antes de `READ_ONLY_BETA` — RESOLVIDA (2026-07-08, ver Changelog acima):** a Tela 5
(Autenticação) foi implementada com o aviso explícito de TOFU, específico deste profile
(`AuthenticationUiState.Ready.showTofuWarning`), revisada e aprovada por Marisa sem correção
obrigatória. Não era bloqueante para o driver continuar em `DISCOVERY_ONLY`/`READ_ONLY_ALPHA`; era
a única pendência registrada para a promoção a `READ_ONLY_BETA`, decidida nesta mesma data.

Revisão de segurança: Marisa, 2026-07-07 (implementação do `TpLinkStokLuciDriverFamily`), aprovado
com esta ressalva documentada — ressalva fechada em 2026-07-08.

## Nota de mapeamento — `manufacturer` real (`ALCL`) vs. nome comercial (`Nokia`)

A execução real de `nokiaManualCheck` (SIG-333) contra a unidade física confirmou que
`/device_status.cgi` reporta `manufacturer=ALCL` — herança do fabricante original Alcatel-Lucent,
adquirido pela Nokia em 2016, cuja base de firmware desta família de ONT GPON não foi renomeada
internamente. Isso **não é um bug nem uma inconsistência a corrigir**: é só uma diferença entre o
identificador interno de firmware e o nome comercial atual (`Nokia`) usado no catálogo e na UI.
Qualquer exibição ao usuário deve continuar usando `vendor: "Nokia"` (nome comercial, já correto no
profile); `manufacturer=ALCL` só é relevante como evidência de fingerprint interna ou nota de
debugging, nunca como valor a expor na Tela de identificação do equipamento.

## Fontes consultadas — manifesto `2026.07.09` (2026-07-06, SIG-333, segunda execução)

- **Nokia G-1425G-B**: segunda execução real de `nokiaManualCheck` contra a mesma unidade física
  do Luiz, agora com a instrumentação de captura de fingerprint da tela de login (introduzida no
  manifesto `2026.07.08`). Trouxe o dado que faltava: `html_title` = "GPON Home Gateway", capturado
  por probe passivo real, sem autenticação, na raiz do equipamento. O header `Server` foi verificado
  e confirmado como genuinamente ausente na resposta — não uma lacuna de captura. Uptimes de GPON,
  WAN e DeviceInfo incrementaram corretamente (~16 min) entre as duas execuções, confirmando leitura
  real (não cache/fixture).
- **TP-Link Archer C6**: inalterado nesta rodada.

## Fontes consultadas — manifesto `2026.07.08` (2026-07-06, SIG-333)

- **Nokia G-1425G-B**: primeira execução real de leitura autenticada do próprio NetHAL
  (`nokiaManualCheck`, via `NokiaOntDriver.readSnapshot`) contra a unidade física do Luiz. Não é
  mais evidência indireta de driver irmão — é o teste que faltava, exigido por
  `/ciclo-vida-driver` para promover de `DISCOVERY_ONLY` para `READ_ONLY_ALPHA`. Os 4 endpoints
  (GPON, WAN, PPP, DeviceInfo) retornaram dados coerentes entre si (mesmo `serialNumber` em dois
  endpoints, uptimes consistentes, `connectionType` idêntico em WAN e PPP), o que caracteriza
  "capability sanity check coerente" na heurística de score, não só "endpoint respondeu".
- **TP-Link Archer C6**: inalterado nesta rodada.

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

## Riscos — `driverConfig` como superfície futura de dado não confiável

`driverConfig` (introduzido no passo 5 do plano de refatoração HAL, preenchido pela primeira vez
para o TP-Link Archer C20 no passo 4) é um `JsonElement` opaco que cada Driver Family interpreta do
seu próprio jeito — hoje, seções/campos que viram literalmente o corpo de requisições autenticadas
enviadas ao equipamento (ver `TpLinkLegacyCgiResponseParser.buildRequestBody` e
`TpLinkLegacyCgiDriverConfig`).

Isso é seguro **só porque o catálogo hoje é 100% embarcado/local** (`RemoteCatalogSource` em
`core/catalog/DriverRegistry.kt` é `NoOpRemoteCatalogSource`, nunca busca nada de rede). O sync
remoto de catálogo é item real do roadmap do produto (spec §8.5), não hipotético — no dia em que
`RemoteCatalogSource` ganhar implementação real, um manifesto malicioso ou corrompido poderia, sem
o gate certo, injetar seção/campo arbitrário em uma requisição autenticada contra o roteador do
usuário. Não é bypass de autenticação nem exfiltração direta de credencial, mas é uma superfície de
"comando cego vindo de dado remoto não confiável".

**Gate obrigatório antes de qualquer `RemoteCatalogSource` real** (ver também `SECURITY.md`,
seção "Catalog integrity"):

1. Manifesto remoto deve ser assinado/verificado antes de ser aceito.
2. Nenhuma Driver Family pode enviar uma seção/campo vindo de `driverConfig` sem checar contra uma
   allowlist de seções/campos que ela já conhece para o próprio protocolo — presença no JSON nunca
   deve, por si só, autorizar o envio.

Revisão de segurança: Marisa, 2026-07-07 (passo 4 do plano de refatoração HAL — reorganização do
C20 como `TpLinkLegacyCgiDriverFamily`), aprovado com esta ressalva documentada.

## Changelog

- **2026-07-11 (Bruno — issue #125, correção real do contador `seq` do envelope `sign` em
  `tplink-stok-luci`)** — Investigação anterior (mesmo dia, entrada abaixo) tinha levantado a
  hipótese de lockout por múltiplos re-logins em sequência; **refutada por teste real do Luiz**
  isolando `tplinkC6StokManualCheck` a um único `login()` seguido de leituras autenticadas: login
  sempre bem-sucedido (`stok` real capturado), mas **toda** leitura autenticada seguinte falhando com
  HTTP 403 ("sessão/token stok provavelmente expirado") — inclusive a primeira, inclusive
  `admin/status?form=all`, já dado como confirmado em 2026-07-07.

  **Causa raiz encontrada por leitura de código** (não por nova captura ao vivo):
  `TpLinkStokLuciAuthenticationClient` guardava `seq` (devolvido cru por `form=auth`) como valor
  fixo, reusado sem alteração tanto na assinatura do próprio `POST .../login?form=login` quanto em
  TODA chamada de `fetchAuthenticatedRaw` da mesma sessão. A lib de referência `tplinkrouterc6u`
  (`EncryptionWrapperMR`) — já citada em todo este driver como fonte da correção de `k=`/`i=`/`h=` —
  trata `seq` como um contador monotônico por sessão: a cada envelope `sign` assinado, o `s=` enviado
  (`seq + tamanho_base64_do_data`) vira o novo piso esperado pelo firmware na PRÓXIMA assinatura da
  mesma sessão. Sem esse avanço, a assinatura do login continua válida (é a própria chamada que
  estabelece o piso), mas toda chamada seguinte assina com um `s=` que já ficou pra trás no instante
  em que o login termina — 403 em 100% das leituras autenticadas, exatamente o padrão relatado.

  **Nota de processo (gap descoberto durante a investigação, não introduzido por esta correção):** a
  afirmação "leitura autenticada real de `admin/status?form=all` confirmada em 2026-07-07"
  (`docs/drivers/live-evidence/tplink-archer-c6-stok-v1.json`, entrada `2026-07-07` abaixo) vem de
  captura de tráfego do NAVEGADOR (Playwright) confirmando a FORMA do protocolo, não de uma execução
  bem-sucedida do `tplinkC6StokManualCheck` (driver Kotlin real) contra o hardware. Não há entrada
  neste changelog documentando uma corrida real do driver com o envelope `sign=`/`data=` de leitura
  (adicionado só em `d5b2181`, 2026-07-07 08:25) confirmada contra o equipamento antes da rodada desta
  issue — plausível que este bug de `seq` sempre tenha existido desde então e nunca tivesse sido
  exercitado de verdade contra hardware físico até o teste do Luiz em #125.

  **Código alterado:** `TpLinkStokLuciAuthenticationClient.SessionEncryptorContext.seq` passou de
  `val` para `var`. `login()` inicializa `seq` já contabilizando o próprio request de login
  (`parsedAuthKeys.seq + dataBase64.length`, o `s=` que aquele login acabou de enviar/ter aceito), em
  vez do `seq` bruto de `form=auth`. `fetchAuthenticatedRaw()` avança `seq` (`+= dataBase64.length`)
  logo após montar cada `sign`, antes de enviar — para a PRÓXIMA chamada assinada da sessão (seja
  outra leitura, seja uma ação como `REBOOT_DEVICE`) já nascer sincronizada.

  **Testes novos** (`TpLinkStokLuciAuthenticationClientTest`, `FakeTpLinkStokLuciHttpTransport` ganhou
  `capturedAuthenticatedSeqValues`, decifrando `s=` de cada chamada autenticada): confirma que a
  PRIMEIRA leitura autenticada de uma sessão nova já usa um `s=` maior que o `seq` bruto de
  `form=auth` (contabilizando o avanço do próprio login) e que duas leituras consecutivas com corpo
  idêntico produzem `s=` estritamente crescente (nunca o mesmo valor reusado). Ambos os testes falham
  contra o código anterior à correção — reproduzem o bug sem precisar de hardware real, é comportamento
  de transporte/parsing, não de firmware.

  **Ainda sem confirmação por evidência ao vivo desta correção específica** contra o Archer C6 físico
  do Luiz — é uma correção de leitura de protocolo contra a MESMA lib de referência que já orientou
  toda a correção anterior deste driver (nenhuma suposição nova), mas o próximo `tplinkC6StokManualCheck`
  do Luiz é quem confirma ou refuta de fato. `stage`/`confidenceScoreOverall` do profile
  `tplink_archer_c6_stok_v1` não mudam nesta rodada — sem manifesto novo, é correção de código, não de
  catálogo.

- **2026-07-11 (Bruno — investigação da issue #125, hipótese de lockout por múltiplos logins,
  refutada por teste real do Luiz)** — Ver entrada acima para a causa raiz real e a correção. Esta
  entrada preserva o registro da hipótese descartada: análise de código (`git diff` entre o commit
  confirmado em 2026-07-07 e a PR #124) não encontrou regressão em `fetchAuthenticatedRaw`,
  `core/protocol` nem `TpLinkStokLuciAuthenticationClient` — byte-idênticos entre as duas rodadas. A
  diferença real identificada foi de quantidade de re-logins que o `tplinkC6StokManualCheck` da PR
  #124 passou a disparar em sequência rápida (4-5, vs. 3 antes), levantando a hipótese de lockout por
  firmware. Teste real do Luiz isolando a um único login refutou essa hipótese (login sempre
  funciona; toda leitura falha mesmo com um login só) e apontou para o bug de `seq` documentado acima.

- **2026-07-11 (Bruno — `REBOOT_DEVICE` no `tplink-stok-luci`, primeira capability de ação/escrita
  "genérica" do produto, issues #95/#103)** — Novo manifesto `catalog-2026.07.29.json`
  (`previousManifest: catalog-2026.07.28.json`).

  **Backend (#103):** `DriverFamily` (`:core:catalog`) ganha `executeAction(id): CapabilityActionResult`
  (default honesto `Unavailable`, mesmo espírito de `authenticate`) e `CapabilityEngine`
  (`:core:capability`) ganha `executeAction(id)` reaproveitando a MESMA política de sessão
  lazy/renovação de `readCapability` (nenhuma lógica de sessão duplicada). `REBOOT_DEVICE` já
  existia no vocabulário oficial (`CapabilityId`) — não foi criada capability nova; decisão
  registrada no KDoc de `CapabilityId.REBOOT_DEVICE` de reaproveitar este nome em vez de inventar
  `REBOOT_WAN`, porque nenhum protocolo mapeado até aqui tem reinício seletivo de interface — todo
  roteador doméstico reinicia o equipamento inteiro. `TpLinkStokLuciDriverFamily.executeAction`
  implementa só `REBOOT_DEVICE`, via um único `operation=write` autenticado em
  `admin/system?form=reboot` (`config.rebootPath`/`rebootQuery`, campos novos do `driverConfig`),
  reaproveitando a sessão já aberta (`authenticatedClient`) — **sem retry automático de propósito**
  (reenviar uma ação que muda o estado do equipamento arriscaria disparar dois reboots reais por
  uma falha só de leitura da resposta).

  **Restrição de driver (decisão de produto do Rafael/Luiz, não técnica):** `REBOOT_DEVICE` só
  executa no driver TP-Link Archer C6 (`tplink-stok-luci`) — nunca no Archer C20
  (`tplink-legacy-cgi`) nem no Nokia G-1425G-B (`nokia-gpon`), mesmo que `DriverFamily.executeAction`
  permitisse tecnicamente nos três. A restrição é estrutural (nenhuma outra Driver Family deste
  repositório sobrescreve o método, então a implementação default `Unavailable` é tudo o que os
  outros dois expõem) — nunca um `if (vendor == ...)` em código compartilhado do Core. Testes
  dedicados em `TpLinkLegacyCgiDriverFamilyTest`/`NokiaGponDriverFamilyTest` confirmam essa
  restrição explicitamente.

  **UI (#95):** módulo novo `:feature:tools-reboot-wan` — `RebootWanScreen`/`RebootWanViewModel`
  implementam o protótipo `4h` (diálogo de confirmação, design system seção 1n): estado inicial
  `ConfirmationPending` exibe o diálogo automaticamente assim que há sessão; `confirmReboot()` é o
  ÚNICO caminho até `CapabilityEngine.executeAction(REBOOT_DEVICE)`, disparado exclusivamente pelo
  toque explícito em "Reiniciar" — cancelar (toque em "Cancelar", tocar fora ou voltar) nunca chega
  a chamar o Core. Cor de "Reiniciar" é `colorScheme.primary` (destaque), não a cor de erro — reboot
  não apaga dado, mesma regra do design system (seção 1n: "cor de erro só se a ação apagar dados").
  Não conectado a `SettingsScreen`/`BottomNavHost` nem ao Hub de Ferramentas (`:feature:tools-common`,
  issue #89, também não conectado ainda) — pendência registrada para quando esse hub existir.

  **Validação: só fake de transporte (JVM), nenhum reboot real disparado contra o hardware do
  Luiz.** A regressão de sessão HTTP 403 documentada na entrada `2026-07-11` abaixo (issue #125,
  investigação em paralelo) já bloqueava qualquer leitura autenticada real desta plataforma antes
  desta rodada começar — capability entra `EXPERIMENTAL` no catálogo por esse motivo (endpoint
  assumido por analogia com a convenção `admin/<seção>?form=<ação>` já confirmada ao vivo para as
  demais leituras deste profile, sem confirmação por evidência ao vivo própria), não por dúvida
  sobre a restrição de driver. Validação real fica para o Luiz, manualmente, depois de #125
  resolvida.

  `capabilities[]` do profile `tplink_archer_c6_stok_v1` — `REBOOT_DEVICE` sobe de `UNKNOWN` para
  `EXPERIMENTAL`. `driverConfig` ganha `rebootPath`/`rebootQuery`. `confidenceScoreOverall`
  inalterado (0.65) — a implementação de uma ação nova sem validação ao vivo não muda a heurística
  de confiança de fingerprint/leitura já calculada na entrada `2026-07-11` abaixo.

  Revisão de segurança da Marisa pendente antes de sair de `EXPERIMENTAL` (`/ciclo-vida-driver`) —
  primeira capability de ação "genérica" do produto, fluxo de confirmação revisado com atenção
  redobrada conforme pedido explícito da tarefa.

- **2026-07-11 (Bruno — Feat #27: `READ_OPTICAL_SIGNAL_MARGIN`/`READ_GPON_ERROR_COUNTERS`/
  `READ_LAN_PORT_STATUS` no driver Nokia, issues #28/#29/#30)** — Três novos campos/capabilities do
  levantamento de campo `NOKIA_GPON_FIELD_MAP.md` (produto irmão SignallQ), itens 1-3 da seção
  "Oportunidades", mesmo endpoint já lido pelo driver (`wan_status.cgi?gpon`) e um vizinho já
  mapeado (`lan_status.cgi?lan`).

  **Pré-check da Feat (bug do `NokiaAuthenticationClient`/`HttpTransport.kt`):** confirmado
  resolvido antes de iniciar — é o mesmo bug já documentado na entrada `2026-07-08` desta seção
  (`DefaultHttpTransport.parseCookies`), corrigido pela PR #110 (closes #55), mergeada em `main` via
  PR #115 (modularização ADR 0002) antes desta rodada começar. Nenhum bug adicional específico do
  `NokiaAuthenticationClient` estava aberto no GitHub Issues.

  - **`READ_SIGNAL` estendido (issue #28), não capability nova** — decisão registrada na própria
    issue (nomenclatura fica a cargo de quem implementa): `SignalStatus` (`core/model`) ganha
    `rxPowerLowerThresholdDbm`/`rxPowerUpperThresholdDbm`/`rxPowerMarginToLowerThresholdDb`, todos
    `null` quando o firmware não devolve os campos. `RXPowerLower`/`RXPowerLowerDec` e
    `RXPowerUpper`/`RXPowerUpperDec` combinados via `NokiaResponseParser.combineIntAndFractionDbm`
    (parte inteira + fração em centésimos). Margem = `rxPowerDbm - rxPowerLowerThresholdDbm`,
    calculada pelo driver (critério de aceite explícito da issue, app não repete a conta).
  - **`READ_GPON_ERROR_COUNTERS` (issue #29, capability nova)** — `stats.FECError`/`HECError`/
    `DropPackets`, mesmo endpoint de `READ_SIGNAL`. Comportamento cumulativo-desde-boot vs.
    por-janela **não confirmado** contra hardware real — assumido cumulativo (comportamento típico
    de PMBd GPON), flag explícita no manifesto (`state: EXPERIMENTAL`).
  - **`READ_LAN_PORT_STATUS` (issue #30, capability nova, genérica/não vendor-specific)** —
    `lan_ether[]` de `lan_status.cgi?lan` (`Status`, `X_ALU_COM_CurMaxBitRate`/`MaxBitRate`,
    `ErrorsSent`/`ErrorsReceived` aninhado em `stat:{...}`). Novo helper
    `NokiaResponseParser.extractJsArrayObjects` (balanceamento de colchetes/chaves, generaliza
    `extractFirstJsonArrayObject` para múltiplos objetos e sintaxe JS solta). Adicionada ao
    vocabulário oficial em `docs/drivers/driver-model.md`/spec §8.6/skill `/modelo-capacidades`.

  **Validação: só JVM/mock, não hardware real.** Rede local do equipamento do Luiz estava acessível
  neste ambiente (ping e HTTP GET na raiz confirmados), mas a captura autenticada ao vivo
  (`nokiaManualCheck`) não foi executada por este agente — exigiria digitar a senha real numa sessão
  de Claude Code, o que o próprio `NokiaManualCheck.kt` proíbe explicitamente ("nem digitada numa
  sessão do Claude Code — sempre via prompt interativo, num terminal próprio, fora de qualquer
  transcript de conversa"). Por isso o encoding assumido de `RXPowerLower`/`RXPowerLowerDec`/
  `RXPowerUpper`/`RXPowerUpperDec` (inferido só do exemplo textual do field map, sem corpo bruto
  capturado) e o comportamento cumulativo dos contadores GPON ficam como suposição documentada, não
  fato confirmado — ambas as capabilities novas entram como `EXPERIMENTAL`, não `AVAILABLE`.
  Validação real em hardware fica pendente, a ser rodada pelo próprio usuário.

  **Estágio do profile:** permanece `READ_ONLY_ALPHA` — promoção é decisão do Rafael, fora de escopo
  desta implementação (instrução explícita da task).

  Novo manifesto `catalog-2026.07.27.json` (`previousManifest: catalog-2026.07.26.json`):
  `driverConfig.lanStatusPath` novo (`/lan_status.cgi?lan`), `capabilities[]` ganha
  `READ_GPON_ERROR_COUNTERS`/`READ_LAN_PORT_STATUS` (`EXPERIMENTAL`), `READ_SIGNAL` permanece
  `AVAILABLE` com nota sobre a extensão, novo `knownFirmwareBugs` documentando a suposição de
  encoding dos thresholds. `confidenceScoreOverall` inalterado (0.9) — cobre só as capabilities já
  validadas, as duas novas `EXPERIMENTAL` não entram no cálculo agregado.

  Issue #35 (backlog de campos secundários, itens 5-8 do mesmo levantamento) registrada como
  backlog — nenhuma implementação nesta rodada, conforme o próprio critério de aceite da issue.

- **2026-07-11 (Bruno — capabilities de topologia/rádio/DoS e ping nativo no `tplink-stok-luci`,
  issues #31-#34, #36, #24, #26)** — Novo manifesto `catalog-2026.07.28.json`
  (`previousManifest: catalog-2026.07.27.json`, já com as capabilities novas do Nokia da entrada
  acima — conflito de merge real com a PR #121 resolvido preservando as duas rodadas, não
  descartando nenhuma). Quatro capabilities novas/reaproveitadas no profile
  `tplink_archer_c6_stok_v1`, todas `EXPERIMENTAL` ou `UNKNOWN` (nenhuma promovida a `AVAILABLE`):
  (1) `READ_WIFI_RADIOS` (issue #33) — capability já existente no vocabulário, sem implementação até
  aqui; passa a carregar canal em uso/potência de transmissão por rádio, reaproveitando o mesmo
  endpoint/payload de `READ_WIFI_STATUS` (decisão registrada: sem terceira capability). (2)
  `READ_MESH_TOPOLOGY` (issue #32) — capability nova (`admin/onemesh_network?form=mesh_topology`),
  distinta de `READ_MESH_STATUS` por ter shape de grafo, não enum de status. (3)
  `READ_DOS_PROTECTION_THRESHOLDS` (issue #34) — capability nova, leitura pura de configuração de
  segurança já existente (`admin/security_settings?form=dos_setting`); confirmação explícita da
  Marisa sobre dispensa de tratamento de Safety Guard fica pendente no PR, não feita ainda. (4)
  `RUN_NATIVE_DIAGNOSTIC_PING` (Feat #23, vocabulário definido na Task #24, implementado na #26) —
  classificada como AÇÃO (não leitura), `admin/diag?form=diag`; implementação restrita a este driver
  por decisão do Rafael, versão Nokia (issue #25) permanece pausada em backlog. Issue #36 registra
  formalmente o backlog de baixa prioridade (RF avançado, double-NAT), sem implementação nesta
  rodada. `driverConfig` do profile ganhou quatro pares novos de path/query
  (`meshTopologyPath`/`meshTopologyQuery`, `dosSettingPath`/`dosSettingQuery`,
  `diagPath`/`diagQuery`) — campos agora obrigatórios no schema Kotlin (`TpLinkStokLuciDriverConfig`),
  não hardcoded no driver. **Regressão negativa encontrada nesta rodada**: duas tentativas reais de
  validação ao vivo (`tplinkC6StokManualCheck` contra a unidade física do Luiz, 192.168.0.1)
  mostraram login bem-sucedido seguido de HTTP 403 em TODA chamada autenticada seguinte, incluindo
  `admin/status?form=all` (endpoint que este mesmo catálogo registrava como confirmado em
  2026-07-07) — não é regressão de código desta rodada (mecanismo de `fetchAuthenticated` inalterado,
  só ganhou `fetchAuthenticatedRaw` como extração sem mudança de comportamento; testes unitários com
  fake de transporte continuam verdes). Nenhuma das quatro capabilities novas, nem as quatro já
  `EXPERIMENTAL` antes desta rodada, puderam ser confirmadas contra hardware real — `fingerprintEvidence[]`
  do profile registra a entrada completa; `confidenceScoreOverall` baixado de `0.75` para `0.65`.
  Recomendação registrada: abrir Task `[BUG]` própria para investigar a causa raiz da regressão de
  sessão antes de qualquer nova tentativa de validação ao vivo desta plataforma — fora do escopo
  desta entrega.
- **2026-07-10 (issue #19 — `authenticate()` real no `tplink-legacy-cgi`; `readCapability()` sai do
  estado "sempre `Unavailable`")** — Mesmo padrão de sessão gerenciada já usado por
  `TpLinkStokLuciDriverFamily`/issue #16: `TpLinkLegacyCgiDriverFamily` ganha `authenticate()` real
  (campo `authenticatedClient`, preenchido pelo handshake já existente — este protocolo não tem
  endpoint de login dedicado, "autenticar" continua sendo a mesma primeira leitura real de sempre) e
  `readCapability(id)` passa a usar essa sessão em vez de devolver `Unavailable` incondicionalmente.
  Diferente do `stok-luci` (um único endpoint devolve tudo), este protocolo tem um bundle `/cgi`
  dedicado por capability — cada leitura busca só o bundle de que precisa (mesma separação já usada
  por `readSnapshot`), então `authenticate()` só valida a credencial (1 chamada), sem custo de ler
  Wi-Fi/clientes antecipadamente. `login()`/`readSnapshot()` (usados por `ManualCheckRunnerC20`) não
  foram alterados — continuam fazendo login novo a cada chamada.

  `TpLinkLegacyCgiLoginFailureReason.SESSION_EXPIRED` (novo) mapeia HTTP 401/403 numa leitura
  autenticada pós-login para `CapabilityReadResult.SessionExpired` — antes,
  `fetchAuthenticated` nem checava `statusCode` (nada reaproveitava a sessão entre chamadas para uma
  expiração ter chance de acontecer). Mesma heurística conservadora e mesma ressalva do `stok-luci`:
  sem confirmação por evidência ao vivo desse cenário específico contra o hardware do Luiz.

  `READ_DEVICE_INFO` ganhou parser ligado ao vocabulário público pela primeira vez em qualquer Driver
  Family do NetHAL — novo caso `CapabilityPayload.DeviceInfo(info: DeviceInfo)` em
  `core/model/CapabilityPayload.kt` (o tipo `DeviceInfo` já existia em `core/model/DeviceInfo.kt`,
  só não estava ligado a nenhum payload de capability ainda). `vendor = "TP-Link"` é hardcoded como
  fato conhecido desta plataforma (todo profile sob `tplink-legacy-cgi-driver` é um equipamento
  TP-Link — o protocolo em si não expõe campo de fabricante), não uma inferência de dado nem
  `if (vendor == ...)`; `firmware`/`hardwareVersion`/`serialNumberHash`/`uptimeSeconds`/`deviceType`
  ficam `null` — nenhum desses campos apareceu na captura real (SIG-337/SIG-338) do bundle
  IGD_DEV_INFO+ETH_SWITCH+SYS_MODE. `app/.../CapabilityDisplay.kt` (`when` exaustivo sobre
  `CapabilityPayload`) ganhou o branch correspondente para continuar compilando.

  **Correção de gate (Marisa) — violação real do ADR 0001, não só divergência a documentar:**
  `TpLinkLegacyCgiConnectedClient` mascarava o MAC no próprio parser
  (`TpLinkLegacyCgiResponseParser.maskMac`, agora removido) antes de chegar ao modelo local — o ADR
  0001 (já aceito, já aplicado ao `stok-luci` na issue #16) determina que mascaramento é fronteira de
  exportação de um futuro Telemetry Collector, nunca do modelo de dados local. Campo renomeado de
  `macAddressMasked` para `macAddress` (MAC bruto) em `TpLinkLegacyCgiConnectedClient`,
  `parseConnectedClients()` para de mascarar, e `connectedClientsResultFor()` passa o valor bruto
  direto para `ConnectedClient.macAddress` — mesmo tratamento já dado a
  `TpLinkStokLuciConnectedClient`/`TpLinkStokLuciLanStatus`. Testes ajustados para MAC completo
  (`AA:BB:CC:DD:EE:FF`), inclusive `DriverFamilyRegistryIntegrationTest`.

  **Estágio do profile:** permanece `READ_ONLY_ALPHA`, sem promoção (fora de escopo desta issue —
  critério de promoção é `/ciclo-vida-driver`, decisão de Rafael). Escopo estritamente `READ_ONLY`,
  nenhuma ação de escrita. Nenhum campo do manifesto (`catalog-2026.07.13.json` vigente) foi alterado
  — mudança é só de código (`DriverFamily`), sem mudança de schema/dado publicado.

  Suíte `:core:test` verde (18 testes novos/atualizados entre
  `TpLinkLegacyCgiDriverFamilyTest`/`TpLinkLegacyCgiCapabilityEngineIntegrationTest`, 214 testes no
  total do módulo, 0 falhas). Módulo `:app` não pôde ser compilado neste ambiente (sem
  `ANDROID_HOME`) — o branch novo em `CapabilityDisplay.kt` não foi verificado por build real, só
  por leitura; validação de app fica para a Marisa/gate de QA com Android SDK disponível.

- **2026-07-10 (Bruno — migração de `NokiaOntDriver` para `NokiaGponDriverFamily`, issue #18)** —
  `nokia_g1425gb_v1` declarava `driverFamilyId: "nokia-ont-gpon-driver"` desde o manifesto
  `2026.07.06`, mas nenhuma `DriverFamilyFactory` estava registrada sob essa chave em
  `defaultDriverFamilyRegistry()` — `DriverFamilyRegistry.resolve()` lançava
  `UnknownDriverFamilyException` para este profile. Corrigido: `NokiaGponDriverFamily`/
  `NokiaGponDriverFamilyFactory` novos em
  `core/src/main/kotlin/com/nethal/core/driver/family/nokia/gpon/`, implementando `DriverFamily`
  no mesmo molde de `TpLinkStokLuciDriverFamily` (issue #16) — `authenticate()` cacheia o
  `NokiaAuthenticationClient` autenticado, `readCapability()` reaproveita essa sessão entre
  chamadas. Reaproveita `NokiaResponseParser`/`NokiaAuthenticationClient` (pacote
  `driver/nokia/`) sem reescrever; `NokiaOntDriver.readSnapshot` **não foi tocado**, continua o
  caminho já validado ao vivo, agora em paralelo ao novo.
  - Novo manifesto `catalog-2026.07.25.json` (`previousManifest: catalog-2026.07.24.json`):
    profile `nokia_g1425gb_v1` ganha `driverConfig` (os 5 endpoints antes hardcoded em
    `NokiaOntDriver.readSnapshot`, agora dado de catálogo — mesma regra de
    `TpLinkStokLuciDriverConfig`/`TpLinkLegacyCgiDriverConfig`, "nunca hardcodar endpoint na
    Driver Family"). `stage`/`capabilities[]`/`confidenceScoreOverall` inalterados — mudança é
    estrutural (config-driven), não nova evidência de campo.
  - `readCapability` cobre `READ_WAN_STATUS`, `READ_DEVICE_INFO`, `READ_CONNECTED_CLIENTS`,
    `READ_SIGNAL` (óptica GPON, novo `CapabilityPayload.Signal`/modelo `SignalStatus`) com
    `Success` real; `READ_DEVICE_INFO` usa o `CapabilityPayload.DeviceInfo` novo (modelo
    `DeviceInfo` já existia em `core/model/`, sem uso até aqui). `READ_WIFI_STATUS`/
    `READ_LAN_STATUS` devolvem `Unavailable` honesto e explícito ("Nokia é ONT, não expõe
    Wi-Fi/LAN neste payload") — não omitidos, conforme complemento de escopo do Rafael na issue.
  - `SessionExpired`: sessão Nokia não sinaliza expiração por HTTP 401/403 como o TP-Link
    stok/luci — o firmware devolve HTTP 200 com corpo
    `<script>var Errorinfo ="Bad request for invalid parameter in the coookie.";...</script>`
    (já documentado como bug conhecido nesta mesma seção de changelog, entrada 2026-07-08).
    `NokiaGponDriverFamily` detecta esse marcador textual e devolve `CapabilityReadResult.
    SessionExpired` em vez de tentar parsear um corpo de erro como se fosse dado real — o
    `CapabilityEngine` renova a sessão a partir daí, mesmo fluxo genérico já usado pelo TP-Link.
  - Suíte `:core:test` verde: 220 testes, 0 falhas (12 testes novos em
    `NokiaGponDriverFamilyTest`, 4 em `NokiaGponCapabilityEngineIntegrationTest`, 1 em
    `DriverFamilyRegistryIntegrationTest` provando a resolução real via catálogo embarcado).

- **2026-07-10 (Bruno — completar as famílias órfãs `tplink-gdpr-cgi-driver`/`tplink-xdr-ds-driver`,
  issue #20, sem hardware físico)** — Decisão do Luiz registrada no plano de fundação HAL: as duas
  famílias (com login funcional, `readCapability()` sempre `Unavailable` até aqui) ganham
  `authenticate()` real e parser experimental por capability, permanecendo órfãs de teste físico —
  nunca promovidas além de `DRAFT`/`EXPERIMENTAL`. Detalhe completo de protocolo em
  `docs/drivers/tplink-mercusys-families-2026-07-07.md` ("Atualização 2026-07-10").

  **Novo manifesto `catalog-2026.07.26.json`** (`previousManifest: catalog-2026.07.25.json` — o
  manifesto Nokia da entrada acima, mergeado depois deste trabalho ter começado; renomeado de
  `catalog-2026.07.25.json` para `catalog-2026.07.26.json` durante o rebase para não colidir com o
  manifesto Nokia, que já ocupava esse nome/versão), dois profiles novos:
  - `tplink_archer_c50_v4` (`tplink-gdpr-cgi-driver`, estilo `C50_GDPR_BODY_LOGIN`/CBC) — `stage:
    DRAFT`, `physicalTestAccess: false`. `READ_WIFI_STATUS`/`READ_CONNECTED_CLIENTS`: `EXPERIMENTAL`,
    parser reaproveita a gramática de dispatcher clássico `/cgi` já confirmada ao vivo para
    `tplink-legacy-cgi` (Archer C20) — o próprio corpo de login C50 usa essa gramática — com nomes de
    oid/campo (`LAN_WLAN`, `LAN_HOST_ENTRY`) herdados por analogia, nunca confirmados para este ramo.
    `READ_WAN_STATUS`/`READ_LAN_STATUS`/`READ_DEVICE_INFO`/`READ_FIRMWARE`: `UNKNOWN` (nenhuma base
    documental para inferir oid/campo — mesma limitação já registrada para `tplink-legacy-cgi`).
    `confidenceScoreOverall: 0.2`.
  - `tplink_xdr3010_v2` (`tplink-xdr-ds-driver`) — `stage: DRAFT`, `physicalTestAccess: false`. Todas
    as capabilities declaradas `UNKNOWN`, **decisão deliberada, não omissão**: diferente do
    `tplink-gdpr-cgi`, a superfície JSON de `/ds` não compartilha gramática confirmada com nenhuma
    outra família do NetHAL — só `error_code` tem uso confirmado (probe `get_encrypt_info` do
    login). Declarar `EXPERIMENTAL` sem nenhum campo de capability mapeado prometeria parsing
    inexistente, violando a regra "não prometer mais do que a evidência sustenta"
    (`CLAUDE.md`/`SECURITY.md`). `readCapability()` ainda assim executa a leitura autenticada real
    (sessão cacheada via `authenticate()`) e distingue, no `reason` do `Unavailable`, se a leitura em
    si funcionou (`error_code=0`) de uma falha de sessão/rede — decisão registrada explicitamente
    para revisão do Rafael, reversível sem mudança de código (só o `state`/`reason` do array
    `capabilities[]`) se ele preferir `EXPERIMENTAL` mesmo sem parser de campo.
    `confidenceScoreOverall: 0.15`.

  Ambos os profiles usam `candidateIps`/`credentialConvention` genéricos por convenção de mercado da
  linha TP-Link (mesmos valores dos demais profiles TP-Link do catálogo), nunca confirmados
  especificamente para estes dois modelos — `ipConfidence`/`confidence` mais baixos que os profiles
  com acesso físico refletem isso.

  **Código:** `TpLinkGdprCgiDriverFamily`/`TpLinkXdrDsDriverFamily` ganharam `authenticatedClient`
  cacheado (mesmo molde de `TpLinkStokLuciDriverFamily`, issue #16) — `login()`/`readRaw()`
  anteriores preservados intactos (cobertura de teste existente não tocada).
  `TpLinkGdprCgiDriverConfig` ganhou `capabilitySections` (config-driven, default vazio — não quebra
  fixtures/profiles existentes). `TpLinkGdprCgiResponseParser`/`TpLinkXdrDsResponseParser` ganharam
  parsing novo (`parseStackFields`/`isStackSuccess`; `parseErrorCode`, respectivamente).
  `loadEmbeddedCatalogResource` e as asserções de `manifestVersion`/contagem de profiles em
  `DriverRegistryTest`/`FingerprintEngineTest` atualizadas para o novo manifesto (6 profiles).
  Verificado que os testes de fingerprint que dependem de `candidateIps` compartilhados
  (`192.168.0.1`/`192.168.1.1`, também usados pelos dois profiles novos) continuam determinísticos:
  os dois profiles novos são `DRAFT` (rank de maturidade mínimo), nunca vencem o desempate de
  `DefaultFingerprintEngine` contra os profiles TP-Link já existentes.

  Suíte `:core:test` verde (235 testes, 0 falhas — 220 já existentes após o merge de
  `NokiaGponDriverFamily` acima + 15 novos desta entrada: parser + driver family para as duas
  famílias, cobrindo `authenticate`, sessão reaproveitada por `readCapability`, estado
  `EXPERIMENTAL` com `reason` explícito, e os casos honestos de `Unavailable`).

  **Sem decisão de estágio:** nenhum dos dois profiles pode sair de `DRAFT` sem evidência de device
  real — gate explícito para Rafael, não decidido nesta rodada (`/ciclo-vida-driver`).

- **2026-07-09 (Diego — verificação da hipótese "HTTP 299 tratado como não-sucesso" a partir do
  `NOKIA_GPON_FIELD_MAP.md` do SignallQ: não confirmada, nenhum bug adicional; corrobora o fix
  anterior; `stage` mantido, sem decisão de promoção)** — Luiz trouxe um levantamento irmão feito no
  SignallQ (`docs_ai/technical/NOKIA_GPON_FIELD_MAP.md`, replicação em Node.js do mesmo handshake
  RSA+AES contra a mesma família Nokia G-14xxG/ALCL), que documenta: sucesso de `/login.cgi` é HTTP
  **299**, não 200, com `X-SID` + cookies `sid`/`lsid`. Hipótese levantada: se alguma checagem de
  sucesso no código estivesse restrita a `200`, isso explicaria a sessão nunca sendo aceita mesmo
  depois da correção de `parseCookies` da entrada anterior.

  **Verificado, sem tocar o equipamento real:**
  1. `NokiaAuthenticationClient.login` já checava `response.statusCode == 299 || response.statusCode
     == 200` desde a implementação original — não havia branch restrito a 200 aqui.
  2. Adicionado 5º teste em `DefaultHttpTransportTest.kt`: servidor HTTP local responde `299` com
     `X-SID` e `Set-Cookie` num `POST` (mesmo formato de `/login.cgi`) — confirma que
     `HttpURLConnection`/`DefaultHttpTransport.post` reportam `statusCode=299` corretamente e
     capturam headers/cookies sem exceção nem truncamento. **Hipótese não confirmada como causa
     adicional** — a camada de transporte já lida bem com esse código não-padrão.
  3. Conferida a codificação `base64_custom_escape` (`+`→`-`, `/`→`_`, `=`→`.`) do parâmetro `ck`,
     citada pelo doc do SignallQ: `NokiaAuthCrypto.base64UrlEscape` já implementa exatamente essa
     troca de caracteres. Sem divergência.
  4. O mesmo doc (item 5 da seção de autenticação) descreve que leituras GET pós-login dependem dos
     cookies `lang`/`sid`/`lsid` — **corrobora, de fonte independente**, a correção de
     `parseCookies` já registrada na entrada anterior deste changelog (não é uma correção nova, é
     confirmação externa da mesma causa raiz).

  Suíte `:core:test` reconfirmada verde (202 testes, 0 falhas) depois do teste novo. Novo manifesto
  `catalog-2026.07.24.json` (`previousManifest: catalog-2026.07.23.json`) com nova entrada em
  `knownFirmwareBugs[]` do profile `nokia_g1425gb_v1` registrando esta verificação.

  **Sem decisão de estágio, de novo:** `stage`/`capabilities[]`/`confidenceScoreOverall` continuam
  inalterados — nada nesta rodada muda o que já estava pendente de confirmação: a correção de
  `parseCookies` da entrada anterior segue não validada contra o hardware físico. Próximo passo
  continua o mesmo: Luiz rodar o `nokiaManualCheck` (já com a correção) contra 192.168.1.254 mais uma
  vez.

  **Pendência de dados (Diego → Bruno, mesmo padrão de sempre):** `loadEmbeddedCatalogResource`
  aponta para `catalog/catalog-2026.07.23.json` no momento desta entrada — Bruno bumpar para
  `catalog/catalog-2026.07.24.json` (e as asserções de `manifestVersion` em
  `DriverRegistryTest`/`FingerprintEngineTest`) quando puder.

- **2026-07-08 (Diego — causa raiz da falha de leitura do `nokia_g1425gb_v1` encontrada e
  corrigida: bug real de código em `DefaultHttpTransport.parseCookies`, não firmware; `stage`
  mantido, sem decisão de promoção)** — Luiz rodou o `nokiaManualCheck` de novo (senha não passou
  pelo Diego em nenhum momento), agora já com o logging bruto adicionado na entrada anterior deste
  changelog. Resultado: login reporta sucesso, fingerprint pós-login reconfirma `nokia_g1425gb_v1`
  de novo, mas os 4 endpoints de leitura (GPON, WAN, Device Info, Clientes) devolveram o **mesmo**
  corpo de erro de 113 chars — `<script>var Errorinfo ="Bad request for invalid parameter in the
  coookie.";window.location.replace('/');</script>` — já documentado na entrada anterior como a
  resposta que `/device_status.cgi` dá quando sondado **sem sessão nenhuma**. PPP devolveu corpo
  vazio (0 chars). Ou seja: o servidor tratava as chamadas pós-login como não-autenticadas, apesar
  do login em si "funcionar" (retornar `sid` via `X-SID`).

  **Investigação (sem tocar o equipamento real):** lido `NokiaAuthenticationClient.
  authenticatedHeaders`/`fetchAuthenticated` e o transporte HTTP compartilhado
  `core/src/main/kotlin/com/nethal/core/protocol/http/HttpTransport.kt`
  (`DefaultHttpTransport.parseCookies`). Achados dois defeitos reais nessa função — **bug de código
  do NetHAL, não variante/bug de firmware**:
  1. O parser só lia o primeiro par `nome=valor` de cada header `Set-Cookie`. O firmware Nokia
     empacota vários cookies num único header (`Set-Cookie: sid=...; lsid=...; lang=...; path=/` —
     mesmo padrão já visto no probe passivo anterior de `/device_status.cgi` sem sessão, que devolveu
     `sid=deleted; lsid=deleted; expires=...; path=/;` numa linha só). `lsid` (e qualquer cookie além
     do primeiro) era descartado silenciosamente e nunca reenviado nas leituras autenticadas — o
     equipamento aparentemente exige `lsid` junto de `sid` para aceitar a sessão como válida.
  2. A busca do header usava a chave exata `"Set-Cookie"` no `Map` de
     `HttpURLConnection.headerFields`, sensível a maiúsculas/minúsculas — nomes de header HTTP são
     case-insensitive por RFC 7230. Essa busca exata já quebrou contra pelo menos um servidor HTTP
     real usado nos testes novos (não o Nokia — um servidor local de teste), então é um risco
     genérico, não hipotético.

  **Correção:** os dois defeitos corrigidos na mesma função (`core/src/main/kotlin/com/nethal/core/
  protocol/http/HttpTransport.kt`). Como pedido, nada foi testado em loop contra o equipamento real
  — a reprodução e a correção foram validadas com um servidor HTTP local determinístico
  (`com.sun.net.httpserver.HttpServer`, JDK padrão, sem dependência nova), num teste novo
  (`core/src/test/kotlin/com/nethal/core/protocol/http/DefaultHttpTransportTest.kt`, 4 casos):
  1. Reproduz literalmente o padrão do firmware Nokia (`Set-Cookie` combinado com `sid`+`lsid`+`lang`)
     e confirma que todos os cookies são capturados, não só o primeiro.
  2. Confirma que o caso padrão RFC 6265 (um `Set-Cookie` por cookie) continua funcionando — sem
     regressão para os demais drivers (TP-Link também usa `DefaultHttpTransport`).
  3. Reproduz o header exato `sid=deleted; lsid=deleted; expires=...; path=/;` já capturado ao vivo
     do Nokia G-1425G-B (probe sem sessão), confirmando que os atributos de cookie (`expires`,
     `path`) nunca viram entrada do mapa de cookies.
  4. Confirma que um header `Cookie` explícito passado em `extraHeaders` (como
     `NokiaAuthenticationClient.authenticatedHeaders` monta) realmente chega ao servidor — descarta a
     hipótese 2 do coordinator (client HTTP diferente/sem propagação do header) como causa adicional.

  Suíte completa `:core:test` reconfirmada verde (201 testes, 0 falhas) depois da correção;
  `:core:compileKotlin`/`:core:compileTestKotlin` também. Novo manifesto `catalog-2026.07.23.json`
  (`previousManifest: catalog-2026.07.22.json`) com nova entrada em `knownFirmwareBugs[]` do profile
  `nokia_g1425gb_v1` registrando a correção e deixando claro que **não é bug de firmware** — é bug de
  código do NetHAL num componente compartilhado (`DefaultHttpTransport`, usado também pelo TP-Link).

  **Decisão explícita: NÃO promovi `stage` (mantido `READ_ONLY_ALPHA`), NÃO restaurei
  `capabilities[]`/`confidenceScoreOverall` para o estado anterior à falha.** A correção foi validada
  só contra servidor local determinístico, nunca contra o equipamento físico — restaurar
  `capabilities[]: AVAILABLE`/`confidenceScoreOverall: 0.9` sem essa confirmação seria inventar
  evidência que este catálogo proíbe explicitamente. `stageReason` do profile atualizado para
  registrar a correção e a pendência de confirmação real.

  **Próximo passo:** Luiz rodar o `nokiaManualCheck` (já com a correção) mais uma vez contra
  192.168.1.254. Se os 5 endpoints agora retornarem dado real, é confirmação de fix normal de driver
  que já estava em `READ_ONLY_ALPHA` — Diego atualiza `capabilities[]`/`confidenceScoreOverall` no
  manifesto seguinte sem precisar de decisão do Rafael (mesmo padrão de "reconfirmação" já usado
  nas rodadas anteriores). Se a leitura continuar falhando por outro motivo, ou se o corpo de erro
  mudar, é sinal de que a causa raiz não era só isto (ou não era só isto) — aí sim escalo para
  Rafael com o achado, sem decidir promoção sozinho.

  **Pendência de dados (Diego → Bruno, mesmo padrão de sempre):** `loadEmbeddedCatalogResource`
  ainda aponta para `catalog/catalog-2026.07.22.json` — Bruno bumpar para
  `catalog/catalog-2026.07.23.json` (e as asserções de `manifestVersion` em
  `DriverRegistryTest`/`FingerprintEngineTest`) quando puder; deixado assim de propósito, mesmo
  split de responsabilidade de sempre.

- **2026-07-08 (Diego — terceira execução real do `nokiaManualCheck` contra `nokia_g1425gb_v1`
  em 192.168.1.254: login confirmado, leitura falhou; `stage` mantido, sem decisão de promoção)** —
  O Luiz forneceu credencial (usuário `admin`) fora de banda e rodou
  `gradlew :core:nokiaManualCheck --args="192.168.1.254 admin"` ele mesmo, senha via prompt
  interativo (fallback `readlnOrNull()`, pois rodou via Gradle Daemon sem console — aviso do próprio
  runner). Diego não teve acesso à senha nem a qualquer token de sessão em nenhum momento; a
  autenticação foi executada e relatada pelo Luiz/coordenação, nunca por chamada de ferramenta deste
  agente (regra inegociável do projeto: senha nunca em argumento de linha de comando, nunca digitada
  numa sessão de agente de IA — ver KDoc de `ManualCheckRunner.readPasswordInteractively`).

  **Resultado:**
  1. Handshake RSA+AES fechou normalmente (login sem erro, sessão aberta via `X-SID`/`sid`).
  2. Fingerprint pós-login (título HTML, header `Server`) reconfirmou exatamente os valores já
     registrados para `nokia_g1425gb_v1` ("GPON Home Gateway", `Server` ausente) — não é evidência
     de unidade física diferente, é o mesmo host já listado em `managementDefaults.candidateIps`
     (192.168.1.254 já era o primeiro candidato do profile antes desta rodada). **Sem necessidade de
     profile irmão** — confirmação registrada nos `note` de `fingerprintEvidence[]` (`html_title`,
     `http_headers`) do manifesto `catalog-2026.07.22.json`.
  3. Os 5 endpoints de leitura (`/device_status.cgi`, `/wan_status.cgi?gpon`,
     `/show_wan_status.cgi?ipv4`, `/index.cgi?getppp`, `/lan_status.cgi?wlan`) retornaram corpo que
     `NokiaResponseParser` não conseguiu interpretar — todos os campos vieram em branco/zerados (não
     "sem sinal", valores default do data class). Isso **contradiz** o histórico de duas execuções
     anteriores bem-sucedidas que sustentam `capabilities[]: AVAILABLE` e
     `confidenceScoreOverall: 0.9` deste profile. Causa raiz não confirmada (variante de firmware,
     sessão/cookie rejeitados apesar do login "ter sucedido", ou efeito do fallback de senha
     não-interativo) — documentado como novo item de `knownFirmwareBugs[]` no manifesto
     `catalog-2026.07.22.json`, `confidence: 0.3` (baixa, exatamente por não estar confirmado).

  **Ação de código (Diego):** `core/src/main/kotlin/com/nethal/core/tooling/ManualCheckRunner.kt`,
  `runNokia()` reescrito para não depender mais de `NokiaOntDriver.readSnapshot()` (que só expõe o
  snapshot já parseado) — agora usa `NokiaAuthenticationClient` diretamente, login único, e imprime
  o corpo bruto de cada um dos 5 endpoints lado a lado com o resultado de
  `NokiaResponseParser`, para o próximo teste real já trazer o dado que falta para diagnosticar a
  causa (nunca imprime senha nem `sid`/`X-SID`). Guarda `PrivateIpRanges.isPrivate(ip)` preservada
  (antes vinha do `init` de `NokiaOntDriver`, que deixou de ser usado neste runner). Compilação
  (`:core:compileKotlin`) e suíte de testes (`:core:test`) verificadas.

  **Decisão explícita: NÃO promovi nem rebaixei `stage` (`READ_ONLY_ALPHA` mantido), não toquei
  `capabilities[]` nem `confidenceScoreOverall`.** A causa da falha de leitura ainda não está
  confirmada — pode ser bug de parser (ajuste técnico normal, sem precisar de decisão do Rafael) ou
  variante de firmware que justificaria um profile novo (que sim precisa de decisão do Rafael antes
  de existir) — não há evidência suficiente ainda para saber qual dos dois é. `stageReason` do
  profile foi atualizado só para registrar que houve nova execução real e que a contradição está em
  aberto, não para anunciar uma conclusão.

  **Próximo passo:** Luiz rodar o `nokiaManualCheck` atualizado novamente, de preferência num shell
  próprio com console interativo (não IDE/Gradle Daemon), e compartilhar os corpos brutos dos 5
  endpoints já mascarados (SSID, MAC completo, IP público, serial — mesma regra da spec §8.9) para
  Diego comparar contra os campos que `NokiaResponseParser` espera e decidir se é correção de
  parser ou variante de firmware.

  **Pendência de dados (Diego → Bruno, mesmo padrão já usado nas rodadas anteriores de bump de
  manifesto) — RESOLVIDA:** `loadEmbeddedCatalogResource` (default agora
  `catalog/catalog-2026.07.22.json`, `core/src/main/kotlin/com/nethal/core/catalog/DriverRegistry.kt`)
  e as asserções de `manifestVersion` em `DriverRegistryTest`/`FingerprintEngineTest` foram
  atualizadas por Bruno para o novo manifesto; `:core:test` está verde de novo.

- **2026-07-08 (decisão do Rafael: `tplink_archer_c6_stok_v1` PROMOVIDO para
  `READ_ONLY_BETA`)** — Reavaliação da pendência registrada na entrada `2026-07-09` abaixo (nota:
  aquela entrada foi escrita antes desta, apesar da data nominal posterior — é o registro histórico
  da decisão de não promover, mantido como está). Conferi o working tree real (não só os resumos de
  revisão), especificamente:

  1. **Aviso de TOFU na Tela 5 — confirmado presente e correto.**
     `app/src/main/kotlin/com/nethal/lab/ui/authentication/AuthenticationScreen.kt`
     (`ReadyContent`, condicionado a `state.showTofuWarning`) exibe o aviso em destaque
     (`MaterialTheme.colorScheme.error`) descrevendo busca de chave sem certificado, impossibilidade
     de confirmar a autenticidade do host de antemão, e recomendação de uso restrito à rede local
     confiável — fiel ao risco real descrito na seção "Limitação conhecida — TOFU no handshake
     stok/luci do TP-Link Archer C6" acima, só com wording simplificado ("sua própria chave de
     criptografia", singular, em vez de "duas chaves RSA distintas") — divergência de literalidade
     técnica já sinalizada por Marisa, não de conteúdo de risco, e não bloqueante.
     `AuthenticationUiState.Ready.showTofuWarning` só é `true` para
     `driverFamilyId == "tplink-stok-luci-driver"` (`AuthenticationViewModel.resolveDriver`),
     confirmando que o aviso é específico deste profile, não um alerta genérico.
  2. **Fluxo de navegação força passagem pela Tela 5 antes de qualquer leitura autenticada —
     confirmado por leitura de código.** `app/src/main/kotlin/com/nethal/lab/ui/navigation/
     NetHalNavHost.kt`: a única forma de `authenticatedCapabilityEngine` deixar de ser `null` é o
     callback `onAuthenticated` da rota `AUTHENTICATION` (Tela 5); a rota `CAPABILITIES` (Tela 4)
     não tem nenhum caminho alternativo de entrada. Isso resolve, por evidência estrutural de
     código, o item 2 da pendência de `2026-07-09` ("Diego — validar que o fluxo passa pela Tela 5
     antes de qualquer leitura autenticada") — a validação "ao vivo contra hardware" que a entrada
     de `2026-07-09` também pedia continua desejável como reforço antes de `STABLE`, mas não é
     necessária para fechar o critério objetivo de `/ciclo-vida-driver` para `READ_ONLY_BETA`
     (abaixo), porque o próprio código já torna esse desvio impossível, não apenas improvável.
  3. **Critério objetivo de `/ciclo-vida-driver` para `READ_ONLY_ALPHA → READ_ONLY_BETA`**
     ("capabilities de leitura declaradas e revisadas por Marisa quanto a telemetria") — cumprido:
     Marisa já havia revisado o `CapabilityEngine` quanto a telemetria/credencial (entrada
     `2026-07-09` abaixo) e revisou de novo, nesta rodada, a Tela 5 completa (credencial nunca
     logada/persistida em nenhuma classe nova, confirmado por leitura completa do diff — entrada
     `2026-07-08 revisão de segurança da Marisa` abaixo) e o working tree das Telas 5/4/6 na íntegra
     (entrada `2026-07-08 revisão cruzada` abaixo, sem correção obrigatória).

  **Decisão: promove.** `stage` passa de `READ_ONLY_ALPHA` para `READ_ONLY_BETA` para o profile
  `tplink_archer_c6_stok_v1`. A pendência que bloqueava a promoção (entrada `2026-07-09` abaixo,
  "Tela 5 precisa avisar explicitamente sobre TOFU antes do primeiro login") está satisfeita — a
  tela existe, o aviso existe, é específico deste profile, e o fluxo de navegação não permite
  contorná-la. Nenhuma capability de escrita envolvida (fora do escopo desta promoção,
  `READ_ONLY_BETA → WRITE_BETA` exige sign-off adicional de Marisa via Safety Guard quando/se algum
  dia houver capability de escrita para este driver).

  **Ação de dados pendente (Diego):** o campo `stage` real deste profile vive no manifesto JSON
  (`core/src/main/resources/catalog/catalog-2026.07.21.json`, ainda `"READ_ONLY_ALPHA"` na linha do
  profile) — esta entrada de changelog registra a decisão, mas não edita o JSON (fora da minha
  alçada, não implemento/edito artefato de dado de produto). Diego: gerar novo manifesto
  (`catalog-2026.07.22.json`, `previousManifest: "catalog-2026.07.21.json"`) com `stage:
  "READ_ONLY_BETA"` e `stageReason` citando esta entrada; Bruno: atualizar
  `loadEmbeddedCatalogResource` (default no momento desta entrada `catalog/catalog-2026.07.21.json`,
  hoje já `catalog/catalog-2026.07.22.json` — bump seguinte, por outro motivo, ver entrada de
  2026-07-08 sobre `nokia_g1425gb_v1` no topo deste changelog —
  `core/src/main/kotlin/com/nethal/core/catalog/DriverRegistry.kt`) e as asserções de
  `manifestVersion` em `DriverRegistryTest`/`FingerprintEngineTest` para o novo manifesto, mesmo
  padrão já usado nas rodadas anteriores de bump de manifesto.

  **Débitos não bloqueantes desta promoção, mantidos em aberto (já registrados em entradas
  anteriores, revisitar antes de `WRITE_BETA`/`STABLE`, não antes):**
  - Validação ao vivo contra hardware real do fluxo completo (Diego) — reforço desejável, não
    bloqueante (ver item 2 acima).
  - Heurística de `SessionExpired` (HTTP 401/403) nunca confirmada contra expiração real de `stok`
    (Diego).
  - `CapabilityEngine.closeSession()` não faz logout no servidor — débito técnico sem dono/prazo.
  - `capabilities[].state` do manifesto vigente desatualizado (`UNKNOWN` em vez de refletir o parser
    real) — Diego sincronizar no mesmo bump de manifesto acima.
  - As três ressalvas de produto/UX da revisão cruzada (wording TOFU mais literal, reset de
    `credentialTestState` ao detectar sessão encerrada, gate de `DriverStage` antes de tentar
    `authenticate()`) seguem como decisão de produto em aberto, sem prazo — nenhuma altera
    segurança/telemetria, nenhuma bloqueia este estágio.

- **2026-07-08 (revisão cruzada Marisa/Diego das Telas 5/4/6 — ambos aprovados, sem correção
  obrigatória)** — Marisa (segurança) e Diego (protocolo/driver) revisaram o working tree completo
  das Telas 5/4/6 do NetHAL Lab de forma independente. Ambos os pareceres: **Aprovado**, "nenhuma
  correção de código necessária". Nenhum item exigia mudança de código — só três ressalvas
  não-bloqueantes, explicitamente descritas por ambos como decisão de produto/UX fora da alçada da
  revisão, e por isso não corrigidas por conta própria. Cada uma foi documentada em KDoc no ponto
  exato do código a que se refere, para não ficar só registrada em texto de review:
  1. **Wording do aviso TOFU** (Marisa) — `AuthenticationScreen.kt` simplifica "duas chaves RSA
     distintas" (redação técnica desta seção, ver acima) para "sua própria chave de criptografia"
     (singular). Não distorce o risco, diverge do detalhe técnico. Ver KDoc de `AuthenticationScreen`
     (`app/src/main/kotlin/com/nethal/lab/ui/authentication/AuthenticationScreen.kt`).
  2. **UX de voltar da Tela 4 após sessão já encerrada** (Marisa) — voltar da Tela 4 para a Tela 5
     depois que `CapabilitiesViewModel.closeSession()` já rodou, e clicar "Continuar" de novo,
     devolve `null` (comportamento honesto, nunca finge sessão viva) e navega para a Tela 4 com
     "sessão indisponível" — pode intrigar o usuário, mas não é falha de segurança. Ver KDoc de
     `AuthenticationViewModel.captureAuthenticatedSession`.
  3. **`resolveDriver()` não verifica `profile.stage`** (Diego) — um profile `DRAFT` (ex.:
     `tplink_archer_c6_v1`/`legacy-cgi`) chega normalmente a `AuthenticationUiState.Ready`; a falha
     só aparece depois, ao clicar "Testar" (mensagem honesta do `authenticate()` default). Pergunta
     em aberto para Rafael: a Tela 5 deveria bloquear/avisar por `DriverStage` antes mesmo de
     tentar autenticar? Ver KDoc de `AuthenticationViewModel.resolveDriver`.

  Nenhuma das três ressalvas altera `stage` do profile, capability declarada, ou comportamento de
  segurança/telemetria — todas ficam para Rafael (produto) ou Bruno (implementação, se Rafael
  decidir) num ciclo futuro. Suíte completa (`:app:test`, `:core:test`,
  `:app:compileDebugKotlin`) reconfirmada verde após as edições de KDoc (só documentação, sem
  mudança de comportamento).

- **2026-07-09 (avaliação de promoção do `tplink_archer_c6_stok_v1` para `READ_ONLY_BETA` —
  decisão do Rafael: NÃO promovido ainda)** — Marisa revisou o `CapabilityEngine` (issue #16,
  entrada de changelog abaixo) e aprovou sem bloqueio de segurança quanto a telemetria/credencial:
  nunca logada/persistida, sem cache de sessão entre equipamentos, retentativa única de
  reautenticação sem risco de força bruta. Isso cumpre a metade do critério objetivo de
  `/ciclo-vida-driver` para `READ_ONLY_ALPHA → READ_ONLY_BETA` ("capabilities de leitura declaradas
  e revisadas por Marisa quanto a telemetria") — as quatro capabilities (`READ_WIFI_STATUS`,
  `READ_LAN_STATUS`, `READ_WAN_STATUS`, `READ_CONNECTED_CLIENTS`) estão declaradas em código e
  documentadas.

  **Não promove agora.** Este mesmo documento já registrava uma pendência explícita para este
  profile especificamente antes de `READ_ONLY_BETA` (ver seção "Limitação conhecida — TOFU no
  handshake stok/luci do TP-Link Archer C6" acima, revisão de segurança de Marisa em 2026-07-07): a
  Tela 5 (Autenticação, `docs/product/specification.md` §11) precisa avisar explicitamente o
  usuário sobre o TOFU no handshake RSA (duas chaves buscadas do próprio host, sem certificado nem
  pinagem) antes do primeiro login neste profile. Conferido nesta rodada: `app/src/main/kotlin/com/
  nethal/lab/ui/` não tem nenhuma tela de Autenticação implementada ainda (existem apenas Welcome,
  BetaOptIn, Privacy, Discovery/DiscoveryFailed/MultipleCandidates, EquipmentDetected, Settings) —
  a pendência continua aberta, não foi endereçada pelo trabalho da issue #16 (que foi só SDK/core,
  sem UI) nem revisitada pela Marisa nesta rodada (o escopo da revisão dela foi o `CapabilityEngine`
  em si, não essa pendência de UX já registrada). Promover o profile sem esse aviso na tela real
  significaria declarar `READ_ONLY_BETA` sem o rastro de evidência que este próprio catálogo exige
  para o gate — não aceitável.

  **O que falta, e quem:**
  1. **Bruno** — implementar a Tela 5 (Autenticação) com o aviso de TOFU descrito acima (mais os
     demais campos já especificados: usuário, senha, botão testar, aviso de senha não salva, aviso
     de sessão única). Sem isso, este gate não fecha.
  2. **Diego** — quando a Tela 5 existir, validar ao vivo que o fluxo de login do
     `tplink_archer_c6_stok_v1` passa por ela antes de qualquer leitura autenticada.

  **Ressalvas adicionais, registradas mas não bloqueantes deste gate (revisitar quando a Tela 5
  destravar a promoção):**
  - A heurística de `SessionExpired` (HTTP 401/403 pós-login) nunca foi confirmada contra hardware
    real — o driver nunca viu um `stok` expirar de verdade. Diego valida quando possível.
  - `CapabilityEngine.closeSession()` não faz logout no servidor, só descarta a credencial local —
    sessão do equipamento expira por TTL do firmware. Não explorável hoje (uma instância de engine =
    uma sessão descartável), mas é débito técnico sem dono/prazo nesta rodada.
  - O manifesto de catálogo vigente (`catalog-2026.07.19.json`) ainda declara
    `READ_WIFI_STATUS`/`READ_WAN_STATUS`/`READ_CONNECTED_CLIENTS`/`READ_DEVICE_INFO` do profile
    `tplink_archer_c6_stok_v1` como `state: "UNKNOWN"`, com `reason` dizendo que "ainda não existe
    parser estruturado" — desatualizado desde o commit `d0d8582` (o parser existe e o Capability
    Engine já lê dado real para as quatro capabilities citadas acima). Diego: sincronizar
    `capabilities[].state` num novo manifesto antes que algum consumidor trate o catálogo como fonte
    de verdade e esconda funcionalidade que já funciona.
  - Débito de arquitetura já registrado em `docs/architecture/hal-layering-model.md` §8 (atualização
    2026-07-08): `CapabilityEngine` ainda não consulta `profile.capabilities[]` do catálogo para
    declarar estado inicial por capability. Não bloqueia este gate especificamente, mas é a raiz da
    ressalva anterior — Bruno mantém prioridade para o próximo ciclo de SDK.

- **2026-07-08 (revisão de segurança da Marisa — Telas 5/4/6 do NetHAL Lab, `AuthenticationScreen`/
  `CapabilitiesScreen`/`ReportScreen`)** — Revisão do item 1 da pendência acima ("Bruno — implementar
  a Tela 5 com o aviso de TOFU"): **cumprido**. `AuthenticationScreen` exibe o aviso quando
  `AuthenticationUiState.Ready.showTofuWarning` é `true` (só para `tplink-stok-luci-driver`), com
  texto fiel a esta seção — nem suaviza nem infla o risco: descreve a busca de chave sem
  certificado, a impossibilidade de confirmar a autenticidade do host de antemão, e recomenda uso
  restrito à rede local confiável. Campos obrigatórios da spec §11 também presentes (usuário, senha,
  botão testar, aviso de senha não salva, aviso de sessão única). Credencial nunca logada/persistida
  em nenhuma classe nova (`AuthenticationViewModel`, `CapabilitiesViewModel`, `ReportViewModel`,
  `NetHalViewModelFactory`) — confirmado por leitura completa do diff, sem uso de
  `SavedStateHandle`/`Bundle`/DataStore para credencial em nenhum ponto.
  `CapabilityEngine.closeSession()` é chamado via `DisposableEffect` ao sair de composição tanto da
  Tela 5 quanto da Tela 4, com `sessionHandedOff` evitando o bug real já documentado no KDoc de
  `AuthenticationViewModel.closeSession` (derrubar a sessão que acabou de ser entregue à Tela 4 ao
  navegar para frente) — coberto por teste (`AuthenticationViewModelTest`). Tela 6 ("Enviar
  relatório anônimo") não faz nenhuma chamada de rede: `ReportViewModel.sendAnonymousReport` só
  troca o estado local para `SendReportState.Unavailable` com mensagem honesta — confirmado que não
  há import de `HttpTransport`/cliente HTTP em `ui/report/`, consistente com ADR 0001 (nenhum
  Telemetry Collector implementado ainda). Nenhuma capability de escrita (`SET_*`/`REBOOT_*`/
  `RESTART_*`) é exposta como ação nas Telas 4/6 — aparecem só como linha de leitura do vocabulário
  oficial (estado + motivo), sem botão associado; único uso de `Button`/`onClick` nessas telas é
  navegação (voltar/continuar/testar/ver relatório/enviar relatório anônimo, este último
  comprovadamente no-op). Aprovado sem ressalva de segurança.

  **Item 2 da pendência acima (Diego — validar ao vivo que o login do `tplink_archer_c6_stok_v1`
  passa pela Tela 5 antes de qualquer leitura autenticada) continua em aberto** — fora do escopo
  desta revisão (revisão de código/UI, não execução contra hardware físico). `stage` do profile
  permanece `READ_ONLY_ALPHA` no manifesto vigente; promoção para `READ_ONLY_BETA` depende também
  desse item 2 e é decisão do Rafael, não desta revisão.

- **2026-07-08 (issue #16 — Capability Engine com gerenciamento de sessão real; `tplink-stok-luci`
  sai do estado "sempre `Unavailable`")** — `DriverFamily.readCapability(id)` deixa de ser stub em
  `TpLinkStokLuciDriverFamily`: novo componente `core/capability/CapabilityEngine.kt` autentica de
  forma preguiçosa (lazy, na primeira leitura), reaproveita a sessão entre chamadas e renova
  automaticamente (uma única tentativa) quando a Driver Family sinaliza `CapabilityReadResult.
  SessionExpired`. A sessão real (token `stok`, cookie `sysauth`, chave/IV AES) continua vivendo
  dentro da própria `TpLinkStokLuciDriverFamily` (campo `authenticatedClient`, preenchido pelo novo
  `authenticate()`); o `CapabilityEngine` guarda só a credencial crua em memória, exclusivamente
  para poder reautenticar sozinho, nunca persistida em disco/log (ver KDoc de `CapabilityEngine`
  para a decisão de arquitetura completa e o racional de onde cada peça de estado vive).
  `login()`/`readStatusRaw()`/`readSnapshot()` (usados por `ManualCheckRunner`) não foram alterados
  — continuam fazendo login novo a cada chamada, comportamento já validado ao vivo; `authenticate()`/
  `readCapability()` são um caminho adicional, não uma substituição.

  Novo tipo `CapabilityReadResult.SessionExpired(reason)`, distinto de `Failure`, para o driver
  sinalizar expiração de sessão sem sobrecarregar `Failure.reason` com texto livre — mapeado a
  partir de HTTP 401/403 numa leitura autenticada pós-login (`TpLinkStokLuciLoginFailureReason.
  SESSION_EXPIRED`, novo). **Sem confirmação por evidência ao vivo de como o firmware sinaliza
  expiração real do `stok`** (nunca foi capturado um token expirando contra o hardware do Luiz) —
  heurística conservadora reaproveitando o mesmo código HTTP usado por `login()` para credencial
  inválida. Diego: confirmar contra hardware real quando possível.

  `CapabilityReadResult.Success` ganhou um campo `payload: CapabilityPayload` (novo, em
  `core/model/CapabilityPayload.kt`) — antes só carregava a declaração `Capability`
  (estado/confidence/reason), sem nenhum dado de fato; sem isso, `readCapability` não tinha como
  cumprir o que promete. Novos tipos de modelo público `LanStatus`, `WanStatus`, `ConnectedClient`/
  `ConnectedClientList` (`core/model/`), espelhando o que `docs/product/specification.md` §12 já
  previa (`readWanStatus()`, `readClients()`) sem nunca ter sido detalhado em §13 — sinalizado para
  Rafael atualizar §13 formalmente. `WifiRadio.ssidHash` renomeado para `ssid` (dado bruto), mesma
  extensão do ADR 0001 (ver entrada abaixo) ao primeiro consumidor real desse tipo — `specification.md`
  §13 atualizada em conjunto.

  **Estágio do profile:** permanece `READ_ONLY_ALPHA`, sem promoção (fora de escopo desta issue —
  critério de promoção é `/ciclo-vida-driver`, decisão de Rafael). Escopo estritamente `READ_ONLY`,
  nenhuma ação de escrita.

  **Testes:** `core/src/test/kotlin/com/nethal/core/capability/CapabilityEngineTest.kt` (política
  genérica de sessão contra uma `DriverFamily` fake — criação lazy, reaproveitamento, renovação,
  credencial nunca exposta) e `core/src/test/kotlin/com/nethal/core/driver/family/tplink/stokluci/
  TpLinkStokLuciCapabilityEngineIntegrationTest.kt` (ponta a ponta real contra
  `TpLinkStokLuciDriverFamily` + `FakeTpLinkStokLuciHttpTransport`, incluindo simulação de expiração
  de sessão via novo parâmetro `expireAuthenticatedReadsAfter` do fake). Suíte completa do `core`
  (197 testes) permanece verde.

- **2026-07-07 (mapeamento das capabilities restantes do `tplink-stok-luci`, manifesto
  `catalog-2026.07.21.json`)** — Revisão dos três pontos em aberto deixados pela rodada anterior
  (parser estruturado + ADR 0001), sem coleta de evidência ao vivo nova. Nenhuma capability nova
  entrou em `SUPPORTED_CAPABILITIES`/`TpLinkStokLuciStatusParser` nesta rodada — é uma rodada de
  documentação/decisão, não de implementação.

  1. **`READ_DEVICE_INFO`/`READ_FIRMWARE` continuam `UNKNOWN`.** Checado o corpo de resposta de
     todas as chamadas já capturadas ao vivo do fluxo `tplink-stok-luci` (`form=keys`, `form=auth`,
     `form=login`, `admin/status?form=all`, ver `docs/drivers/live-evidence/tplink-archer-c6-stok-v1.json`)
     — nenhuma delas carrega campo de vendor/modelo/versão de firmware. O modelo/firmware
     conhecidos desta unidade (`Archer C6 v2.0`, `1.1.10 Build 20230830 rel.69433(5553)`) são
     metadado de identificação manual da unidade física de teste, não um campo de API parseado —
     não há como preencher `READ_DEVICE_INFO`/`READ_FIRMWARE` sem inventar heurística. Nenhum
     endpoint novo foi chamado nesta rodada por falta de evidência ao vivo (regra explícita da
     tarefa: sem evidência, documenta `UNKNOWN`, não inventa).

  2. **Guest network confirmada sem capability própria — decisão mantida.** Revisão do vocabulário
     oficial (`docs/drivers/driver-model.md`, `core/src/main/kotlin/com/nethal/core/model/Capability.kt`
     — `CapabilityId` completo) confirma que não existe `READ_GUEST_NETWORK_STATUS` nem
     equivalente. A modelagem já em produção desde a rodada anterior (`guest_2g_ssid`/
     `guest_5g_ssid` como entradas de `TpLinkStokLuciWifiRadio` com `guestNetwork=true`, dentro de
     `READ_WIFI_STATUS`) permanece — não é proposta capability nova unilateralmente (decisão de
     vocabulário é do Rafael, ver `/modelo-capacidades`). Fica resolvido o "gap a discutir com
     Rafael" sinalizado na entrada de changelog anterior: a conclusão é manter como está, não que
     falte decisão.

  3. **Campos observados sem capability correspondente no vocabulário atual — nenhum parser
     implementado, listados só para referência futura:**
     - `wireless_2g_wps_state` (estado de WPS) — não existe capability dedicada de WPS no
       vocabulário.
     - `storage_*`, `usb_available`, `printer_*` (compartilhamento USB/armazenamento/impressora) —
       não existe capability de armazenamento ou impressora no vocabulário.
     - `modem_*` — prefixo observado sem nome/conteúdo de campo exato confirmado; insuficiente para
       mapear com segurança para `READ_WAN_STATUS` ou qualquer outra capability sem forçar
       mapeamento sem evidência.

     Nenhum desses campos ganhou implementação de parser nesta rodada — regra explícita da tarefa
     de não forçar mapeamento onde não há capability correspondente clara.

  4. **Correção de consistência incidental:** o texto de `reason` de `READ_WIFI_STATUS`/
     `READ_LAN_STATUS`/`READ_CONNECTED_CLIENTS` em `catalog-2026.07.20.json` ainda descrevia SSID
     como hash SHA-256 e MAC sempre mascarado — texto nunca atualizado quando a ADR 0001 corrigiu o
     modelo do driver para dado bruto. Corrigido em `catalog-2026.07.21.json` para refletir o
     comportamento real do código (`TpLinkStokLuciStatusParser`/`TpLinkStokLuciModels.kt`) desde a
     ADR.

  **Estágio do profile:** permanece `READ_ONLY_ALPHA`, sem promoção (fora de escopo desta rodada).
  `confidenceScoreOverall` permanece `0.75` — rodada de revisão/documentação, sem evidência ao vivo
  nova.

  **Testes:** nenhum teste novo — `SUPPORTED_CAPABILITIES` de `TpLinkStokLuciDriverFamily` não
  mudou (`TpLinkStokLuciDriverFamilyTest` já cobre isso). Ajustadas as asserções de
  `manifestVersion` (`"2026.07.20"` → `"2026.07.21"`) em `DriverRegistryTest` e
  `FingerprintEngineTest` para o novo manifesto embarcado default; `loadEmbeddedCatalogResource()`
  atualizada para `catalog/catalog-2026.07.21.json`.

- **2026-07-07 (ADR 0001 — modelo do `tplink-stok-luci` passa a carregar SSID/MAC brutos)** —
  `docs/architecture/adr/0001-fronteira-sanitizacao-telemetria.md` (Rafael) decidiu que
  sanitização de dado sensível (hash de SSID, mascaramento de MAC) não é responsabilidade do
  parser/modelo do driver — é responsabilidade exclusiva de um futuro Telemetry Collector, aplicada
  só na fronteira de exportação. `TpLinkStokLuciWifiRadio.ssidHash` virou `ssid: String?` (SSID
  real, sem hash); `TpLinkStokLuciLanStatus.macAddressMasked` e
  `TpLinkStokLuciConnectedClient.macAddressMasked` viraram `macAddress: String?` (MAC completo, sem
  mascaramento). A entrada de changelog anterior (abaixo) que descrevia essa sanitização como regra
  da spec §8.9 aplicada "já na origem" reflete o entendimento anterior, corrigido por este ADR — a
  proibição de coleta de senha do Wi-Fi (`*_psk_key`) não muda, continua nunca lida. Não altera
  `stage` (`READ_ONLY_ALPHA` permanece).

- **2026-07-07 (parser estruturado de capabilities do `tplink-stok-luci`, manifesto
  `catalog-2026.07.20.json`)** — `TpLinkStokLuciDriverFamily` (profile `tplink_archer_c6_stok_v1`)
  ganhou `TpLinkStokLuciStatusParser`, mapeando o corpo já decifrado de `admin/status?form=all`
  (validado ao vivo em rodada anterior, ver evidência acima) para o vocabulário de capabilities do
  NetHAL:
  - `READ_WIFI_STATUS` ← `wireless_2g_ssid`/`wireless_5g_ssid`/`wireless_2g_channel` (rádios
    principais) e `guest_2g_ssid`/`guest_5g_ssid` (redes de convidados, modeladas como rádios
    adicionais com `guestNetwork=true` — não existe capability própria para rede de convidados no
    vocabulário atual, sinalizado como possível gap a discutir com Rafael).
  - `READ_LAN_STATUS` ← `lan_macaddr` (mascarado) + `lan_ipv4_ipaddr`.
  - `READ_WAN_STATUS` ← `wan_ipv4_ipaddr`.
  - `READ_CONNECTED_CLIENTS` ← `access_devices_wired[]` (`macaddr` mascarado, `ipaddr`, `hostname`).

  **Sanitização (spec §8.9), aplicada já na origem do parsing:** SSID nunca fica em texto puro —
  vira hash SHA-256 (`ssidHash`); MAC sempre mascarado (3 últimos octetos); `wireless_*_psk_key`/
  `guest_*_psk_key` (a senha do Wi-Fi) **nunca são lidos para nenhum campo** do modelo resultante —
  não existe campo para isso em `TpLinkStokLuciWifiRadio` de propósito. WAN/LAN IP permanecem em
  texto puro (não estão na lista de campos proibidos/mascarados da spec §8.9 — só "IP público
  completo" da telemetria está listado, e o valor de diagnóstico de ver o próprio IP é central para
  o NetHAL Lab).

  **Campos do payload sem capability correspondente no vocabulário atual:** nenhum — todos os
  campos citados na tarefa (`wireless_2g_ssid`, `wireless_5g_ssid`, `wireless_2g_channel`,
  `wireless_2g_psk_key`, `lan_macaddr`, `lan_ipv4_ipaddr`, `wan_ipv4_ipaddr`,
  `access_devices_wired`, `guest_2g_ssid`, `guest_5g_ssid`) foram mapeados ou deliberadamente
  excluídos (`psk_key`, nunca — é segredo, não identificador). `READ_DEVICE_INFO`/`READ_FIRMWARE`
  continuam `UNKNOWN`: nenhum campo de modelo/firmware apareceu no payload observado até aqui.

  **Limite arquitetural que permanece, idêntico ao de `tplink-legacy-cgi-driver`/
  `tplink-gdpr-cgi-driver`/`tplink-xdr-ds-driver`:** `DriverFamily.readCapability(id)` (a
  implementação da interface pública, sem parâmetro de credencial) continua retornando
  `CapabilityReadResult.Unavailable` para todo `CapabilityId` — não existe Capability Engine
  gerenciando sessão ainda (`docs/architecture/hal-layering-model.md` §8 passo 5), então o dado
  estruturado real só é alcançável hoje via o novo método `readSnapshot(username, password)`,
  mesmo padrão de `login()`/`readStatusRaw()` já existentes. `readCapability(id)` ganhou
  `SUPPORTED_CAPABILITIES` (mesmo padrão introduzido antes por `TpLinkLegacyCgiDriverFamily`) para
  distinguir "esta Driver Family nunca vai cobrir `$id`" de "cobre, mas exige sessão que esta
  assinatura não recebe".

  **Estágio do profile:** permanece `READ_ONLY_ALPHA` — não sobe para `READ_ONLY_BETA` nesta
  rodada porque essa transição exige sign-off explícito de telemetria da Marisa
  (`/ciclo-vida-driver`), ainda não feito para as capabilities novas. `capabilities[]` do profile
  passou de `UNKNOWN` para `EXPERIMENTAL` nas quatro capabilities cobertas pelo parser
  (`READ_WIFI_STATUS`, `READ_LAN_STATUS` — nova, antes ausente do array —, `READ_WAN_STATUS`,
  `READ_CONNECTED_CLIENTS`), nunca `AVAILABLE`: o mapeamento de nome de campo usado no parser não
  foi recapturado/reconfirmado byte a byte nesta rodada, só herdado de nomes de campo relatados de
  uma sessão de teste manual anterior (console do `gradlew :core:tplinkC6StokManualCheck`, não
  persistido em arquivo). `confidenceScoreOverall` subiu de 0.70 para 0.75 (componente "capability
  sanity check" de 0.10 para 0.15) pelo mesmo motivo.

  **Testes novos:** `TpLinkStokLuciStatusParserTest` (puro, sem rede — mapeamento de campos,
  sanitização de SSID/MAC, ausência total de `psk_key` no modelo resultante, JSON malformado/campos
  ausentes nunca lançam) e extensões em `TpLinkStokLuciDriverFamilyTest`
  (`SUPPORTED_CAPABILITIES`, `readSnapshot` ponta a ponta contra o fake de transporte,
  distinção de motivo em `readCapability`). `ManualCheckRunner.runTplinkC6Stok` passou a chamar
  `readSnapshot` também, imprimindo o resultado estruturado e sanitizado ao lado do corpo bruto já
  impresso antes.

- **2026-07-07 (correção de consistência do Archer C6 físico no runtime atual)** — O texto
  histórico abaixo preserva corretamente cada hipótese/refutação por rodada, mas ficou um descompasso
  entre changelog, catálogo embarcado e evidência viva do profile `tplink_archer_c6_stok_v1`. A
  evidência consolidada em `docs/drivers/live-evidence/tplink-archer-c6-stok-v1.json` já registra
  que a implementação atual fez **login real bem-sucedido** e **leitura autenticada real de
  `admin/status?form=all`** contra o hardware do Luiz em 2026-07-07. Pelo critério deste próprio
  documento (`DISCOVERY_ONLY -> READ_ONLY_ALPHA` exige ao menos uma leitura autenticada real),
  o profile não podia continuar descrito como `DISCOVERY_ONLY` por falta de teste. O ajuste desta
  rodada é de consistência, não de descoberta nova: o catálogo atual passa a refletir o estado real
  já comprovado da Driver Family `tplink-stok-luci-driver`:
  `READ_ONLY_ALPHA` para login + `readStatusRaw`, ainda **sem** mapeamento estruturado de
  capabilities nem cobertura completa de navegação/coleta.

- **2026-07-07 (quarta rodada, chave/IV AES corrigida para string decimal de 16 dígitos —
  evidência via captura byte a byte externa, manifesto `catalog-2026.07.19.json`)** — A terceira
  rodada (manifesto `catalog-2026.07.18.json`) corrigiu as duas chaves RSA distintas
  (`form=keys`/`form=auth`), mas o teste real seguinte (`gradlew :core:tplinkC6StokManualCheck`)
  **ainda falhou** com `INVALID_CREDENTIALS`/HTTP 403. Nesta rodada, uma ferramenta externa (Codex,
  outro agente de IA, **não Claude Code**) capturou o texto puro exato do campo `sign` antes de
  cifrar, durante um login real bem-sucedido pelo navegador contra a mesma unidade física/firmware
  (Archer C6 v2.0, `1.1.10 Build 20230830 rel.69433(5553)`):

  ```
  k=5945270769887026&i=3257785177414969&h=f6fdffe48c908deb0f4c3bd36c032e72&s=855135262
  ```

  (84 caracteres — a senha real usada no login nunca aparece em claro nesta captura, só via hash
  MD5; não foi compartilhada com este agente e não deveria ser.)

  Essa captura refuta a suposição presente desde a primeira rodada de que a chave/IV AES eram bytes
  binários aleatórios reais (`SecureRandom.nextBytes`) hex-encodados para virar `k=`/`i=`. O valor
  real: `k=5945270769887026` e `i=3257785177414969` são strings de **exatamente 16 caracteres, só
  dígitos decimais `0-9`** (nunca hex, que teria `a-f` misturado). Isso confirma que este firmware
  usa a variante **`EncryptionWrapperMR`** da lib de referência `tplinkrouterc6u` — distinta da
  `EncryptionWrapper` genérica que orientou as três rodadas anteriores: a chave/IV AES-128 são
  strings decimais de 16 caracteres usadas **diretamente como os 16 bytes ASCII/UTF-8** da chave e
  do IV, nunca decodificadas de hex, nunca bytes binários aleatórios convertidos para hex depois.

  `s=855135262` do exemplo capturado confirma matematicamente `seq (855134878) + tamanho do
  ciphertext AES em bytes (384) = 855135262` — a fórmula já implementada em
  `TpLinkStokLuciCrypto.buildSignPlaintext` estava correta e não precisou mudar. String de 84
  caracteres cifrada em pedaços de 53 bytes (RSA 512-bit, já implementado certo) → 2 pedaços
  (53+31) → 2 blocos de 64 bytes = 256 caracteres hex no `sign` final, batendo com o tamanho real já
  observado em capturas anteriores. `h=f6fdffe48c908deb0f4c3bd36c032e72` (MD5, 32 caracteres hex)
  não teve seu conteúdo exato confirmado nesta rodada — mantida a hipótese `md5(password)`; é a
  próxima (e provavelmente última) suspeita se esta correção sozinha não bastar.

  **Código alterado:** `TpLinkStokLuciCrypto` ganhou `AES_KEY_OR_IV_DIGIT_COUNT` (16) e
  `generateAesKeyOrIvDigits()` (gera uma string de 16 dígitos decimais via `SecureRandom`) —
  substitui o uso de `generateRandomBytes` + `bytesToHex` para a chave/IV desta plataforma
  (`generateRandomBytes`/`bytesToHex` continuam existindo como utilitários genéricos, usados agora
  só pelo fake de teste). `TpLinkStokLuciAuthenticationClient.login` gera duas strings decimais
  (chave e IV), converte cada uma para bytes via `Charsets.US_ASCII` para a `SecretKeySpec`/
  `IvParameterSpec` que cifra de fato o campo `data`, e passa as mesmas strings decimais (não hex)
  para `buildSignPlaintext` compor `k=`/`i=` do `sign` — garantindo que é literalmente a mesma
  chave/IV nos dois lugares. O hash `h=` continua `md5(password)`, sem alteração, documentado como
  próxima suspeita.

  Testes atualizados: `TpLinkStokLuciCryptoTest` ganhou `generateAesKeyOrIvDigits produces exactly
  16 decimal ASCII digits...`, `...round-trips through AES-CBC` (confirma que a string decimal usada
  como bytes ASCII cifra/decifra corretamente) e `buildSignPlaintext matches the shape of the real
  sign plaintext captured byte by byte externally` (reproduz a forma exata do `sign` capturado, sem
  usar a senha real). `TpLinkStokLuciAuthenticationClientTest` ganhou um teste que roda o login
  completo contra o fake e confirma que a chave/IV extraída do `sign` decifrado é uma string de 16
  dígitos decimais. `FakeTpLinkStokLuciHttpTransport` foi ajustado para extrair `k=`/`i=` como
  dígitos decimais (regex `\d+` em vez de `[0-9a-f]+`) e expor `lastCapturedAesKeyDigits`/
  `lastCapturedAesIvDigits` para asserção nos testes.

  Novo manifesto `catalog-2026.07.19.json` (`previousManifest: catalog-2026.07.18.json`): só o
  profile `tplink_archer_c6_stok_v1` foi alterado — nova entrada `fingerprintEvidence[]` tipo
  `auth_mechanism` (HIGH, 0.75) documenta a captura byte a byte externa; novo item em
  `knownFirmwareBugs[]` documenta a lição (segundo erro independente pode produzir o mesmo sintoma
  de falha que o primeiro); `stageReason`/`physicalTestAccessNote` atualizados;
  `confidenceScoreOverall` sobe de `0.5` para `0.55`. `stage` permanece `DISCOVERY_ONLY` até o
  próximo teste real (`gradlew :core:tplinkC6StokManualCheck`) confirmar login bem-sucedido com a
  chave/IV decimal corrigida — não promover sem esse teste (`/ciclo-vida-driver`).
  `loadEmbeddedCatalogResource()` (default de `DriverRegistry.kt`) atualizado para apontar para o
  novo manifesto.

- **2026-07-07 (terceira rodada, evidência DEFINITIVA via Playwright — reposição de `form=keys`,
  manifesto `catalog-2026.07.18.json`)** — A segunda rodada de correção (manifesto
  `catalog-2026.07.17.json`) tinha concluído, por engano, que o handshake do
  `TpLinkStokLuciAuthenticationClient` usa uma única chamada de preparação (`form=auth`) com uma
  única chave RSA reaproveitada para cifrar a senha e assinar o envelope `sign`. Essa conclusão foi
  baseada em captura **incompleta** feita com a extensão Chrome, que pulou a chamada `form=keys` por
  algum motivo de cache/estado do navegador naquela tentativa específica — não porque o protocolo
  real só tem uma chamada. Nesta rodada usamos **Playwright** (não mais a extensão Chrome) para
  abrir um navegador real, interceptar `page.on('response')` e capturar o corpo **completo** de
  request E response de cada chamada `cgi-bin/luci` durante um login real que teve sucesso total —
  inclusive chamadas autenticadas pós-login, com `stok` real funcionando. Essa captura completa
  **confirma que existem sim duas chamadas de preparação com duas chaves RSA distintas**, exatamente
  como a lib de referência `tplinkrouterc6u` sempre documentou: `form=keys` (`operation=read`)
  devolve `{"success":true,"data":{"password":[<256 hex>,"010001"],"mode":"router","username":""}}`
  — chave RSA 1024-bit usada só para cifrar a senha; `form=auth` (`operation=read`) devolve
  `{"success":true,"data":{"key":[<128 hex>,"010001"],"seq":<número>}}` — chave RSA 512-bit
  **diferente** da anterior, usada só para assinar o envelope `sign`. O tamanho de bloco do RSA em
  pedaços do `sign` (53 bytes) foi confirmado como corretamente derivado do tamanho real da chave de
  assinatura (512-bit = 64 bytes, menos 11 bytes de overhead PKCS1v1.5) — não é mais um valor
  arbitrário. A remoção do `&confirm=true` do corpo de login (correção da rodada anterior) permanece
  confirmada correta pela captura completa desta rodada. `TpLinkStokLuciAuthenticationClient`,
  `TpLinkStokLuciResponseParser` e `TpLinkStokLuciModels` foram corrigidos para repor a chamada a
  `form=keys` e usar as duas chaves distintas corretamente. `stage` permanece `DISCOVERY_ONLY` até o
  próximo teste real (`gradlew :core:tplinkC6StokManualCheck`) confirmar login bem-sucedido com esta
  implementação corrigida.

- **2026-07-07 (correção do corpo de login `tplink-stok-luci` — `&confirm=true` removido, senha
  cifrada em RSA confirmada byte a byte, manifesto `catalog-2026.07.17.json`)** — Segunda rodada de
  correção do `TpLinkStokLuciDriverFamily`. A correção anterior (envelope `sign`/`data`, uma única
  chamada a `form=auth`) ainda falhava com `INVALID_CREDENTIALS`/HTTP 403 contra o hardware físico
  do Luiz. Causa raiz encontrada por evidência ao vivo mais precisa: hook real instalado em
  `CryptoJS.AES.encrypt` na própria página do equipamento (não mais interceptação de
  `XMLHttpRequest`), capturando o texto plano exato que entra no AES durante um login real
  bem-sucedido pelo navegador. O texto plano é exatamente `operation=login&password=<256 caracteres
  hex>` — **sem** `&confirm=true` (tamanho total capturado, 281 caracteres, bate exatamente com
  `"operation=login&password="` de 25 caracteres + 256 caracteres hex). Os 256 caracteres hex do
  campo `password` são a senha **já cifrada em RSA** (saída de RSA de 1024 bits com a mesma chave de
  `form=auth`), não a senha em texto puro como a implementação anterior assumia por analogia com a
  lib de referência `tplinkrouterc6u`.

  `TpLinkStokLuciCrypto.buildLoginPlaintext` mudou de assinatura: recebe agora
  `rsaEncryptedPasswordHex` (a senha já cifrada em RSA, em hex), não mais a senha em texto puro, e
  monta só `operation=login&password=<rsaEncryptedPasswordHex>`, sem `confirm=true`.
  `TpLinkStokLuciAuthenticationClient.login` cifra a senha em RSA (mesma chave devolvida por
  `form=auth`, PKCS1v1.5) antes de montar o texto plano do login. A mesma captura ao vivo confirmou
  `keyWords: 4` no hook (CryptoJS usa palavras de 32 bits; 4 palavras = 16 bytes = 128 bits) →
  **AES-128**, nunca AES-256 — a implementação já usava `AES_KEY_SIZE_BYTES = 16`, então nenhuma
  mudança de código foi necessária aí, só a confirmação em KDoc.

  Testes atualizados: `TpLinkStokLuciCryptoTest` (`buildLoginPlaintext` agora testa o corpo com a
  senha já em RSA-hex, sem `confirm=true`). `TpLinkStokLuciAuthenticationClientTest` e
  `FakeTpLinkStokLuciHttpTransport` não precisaram de mudança — o fake decifra o envelope `sign`
  para extrair a chave/IV AES e nunca inspecionava o texto plano de `data` diretamente.

  Novo manifesto `catalog-2026.07.17.json` (`previousManifest: catalog-2026.07.16.json`): só o
  profile `tplink_archer_c6_stok_v1` foi alterado — a entrada de evidência anterior sobre a
  estrutura do texto plano de `data` (baseada só na lib `tplinkrouterc6u`, MEDIUM) foi rebaixada para
  LOW e marcada como parcialmente refutada; nova entrada `auth_mechanism` (HIGH) documenta o achado
  confirmado byte a byte; `stageReason`/`physicalTestAccessNote`/`knownFirmwareBugs[]` atualizados.
  `confidenceScoreOverall` permanece `0.4` — ainda não há execução real de login bem-sucedida com a
  implementação corrigida, só a correção da causa raiz do 403 anterior. `stage` permanece
  `DISCOVERY_ONLY` até o próximo teste real (`gradlew :core:tplinkC6StokManualCheck`) confirmar
  sucesso — não promover sem esse teste (`/ciclo-vida-driver`). `loadEmbeddedCatalogResource()`
  (default de `DriverRegistry.kt`) atualizado para apontar para o novo manifesto.

- **2026-07-07 (implementação de `TpLinkStokLuciDriverFamily` — protocolo entendido por pesquisa
  de terceiros, NUNCA testado contra hardware real)** — Implementa o login (passos 1-5 do
  handshake, ver abaixo) e uma leitura autenticada simples da plataforma `tplink-stok-luci`, pacote
  `core/driver/family/tplink/stokluci/` (`TpLinkStokLuciDriverFamily`,
  `TpLinkStokLuciAuthenticationClient`, `TpLinkStokLuciCrypto`, `TpLinkStokLuciResponseParser`,
  `TpLinkStokLuciDriverConfig`), seguindo exatamente o mesmo padrão arquitetural do
  `TpLinkLegacyCgiDriverFamily` (`DriverFamily`/`DriverFamilyFactory`, `HttpTransport` compartilhado,
  `DriverRetryPolicy`, `AuthenticationStrategy`). Registrado em `DriverFamilyRegistry`
  (`core/driver/family/DriverFamilies.kt`) sob a chave `"tplink-stok-luci-driver"`.

  Entendimento do protocolo vem da leitura direta do código-fonte real do pacote Python
  `tplinkrouterc6u` (PyPI, GPL-3.0) — classe `TplinkEncryption` (`tplinkrouterc6u/client/c6u.py`) e
  `EncryptionWrapper` (`tplinkrouterc6u/common/encryption.py`) — usado só como referência da
  existência/forma do protocolo, nunca copiado literalmente; a implementação Kotlin é original,
  usando `javax.crypto`/`java.security` do JDK como qualquer outra Authentication Strategy do
  projeto. Handshake de login implementado: (1) `POST /cgi-bin/luci/;stok=/login?form=keys` →
  chave RSA (módulo/expoente hex) para cifrar a senha; (2) `POST /cgi-bin/luci/;stok=/login?form=auth`
  → sequência + chave RSA de assinatura (diferente da do passo 1, guardada para uso futuro em
  chamadas autenticadas, não usada no login em si); (3) senha cifrada com RSA **PKCS#1 v1.5**
  (diferente do mecanismo antigo `tplink-encrypted-web`, que usa RSA sem padding); (4)
  `POST /cgi-bin/luci/;stok=/login?form=login`, corpo `operation=login&password=<hex>&confirm=true`
  — sem campo de usuário, batendo com a evidência real já capturada; (5) sucesso extrai `stok` do
  corpo JSON e `sysauth` do header `Set-Cookie` (via regex, não parser de cookie genérico).

  **Escopo desta entrega e o que ficou de fora:** só os passos 1-5 (login) mais uma leitura
  autenticada simples (`readStatusRaw`, endpoint `admin/status?form=all&operation=read`, sem
  envelope AES/assinatura) foram implementados. A etapa 6 completa (chamadas autenticadas com
  envelope AES-CBC de chave/IV por sessão + campo `sign` assinado com a chave RSA do passo 2, em
  pedaços de 53 bytes) fica documentada em KDoc mas não implementada — próximo passo. A terceira
  geração de firmware que a mesma pesquisa de terceiros documenta (`TplinkRouterV1_11`,
  autenticação só-RSA sem AES, distinguível pelo tamanho da chave RSA da senha: >=512 chars hex =
  2048-bit vs. <512 = 1024-bit do mecanismo aqui implementado) não foi implementada — só registrada
  como nota de risco no catálogo, caso o teste real revele que é essa a variante correta.

  **Nunca testado contra hardware real.** `profile.tplink_archer_c6_stok_v1.stage` permanece
  `DISCOVERY_ONLY` — a implementação existe e está coberta por testes com fake de transporte
  (`TpLinkStokLuciAuthenticationClientTest`, `TpLinkStokLuciDriverFamilyTest`,
  `TpLinkStokLuciCryptoTest`, 23 testes novos), mas nenhuma execução real de login aconteceu ainda.
  `driverConfig` do profile ganhou o primeiro schema desta plataforma (`statusReadPath`,
  `statusReadQuery`). `ManualCheckRunner` (`core/tooling/ManualCheckRunner.kt`) ganhou um branch
  novo (`runTplinkC6Stok`) e a task Gradle `tplinkC6StokManualCheck`
  (`gradlew :core:tplinkC6StokManualCheck --args="<ip> <usuario>"`) — comando a rodar quando o Luiz
  quiser validar o login contra a unidade física real; o resultado (sucesso ou falha) deve ser
  reportado para atualizar `stage`/`fingerprintEvidence[]`/`confidenceScoreOverall` do profile, não
  promovido sem esse teste (`/ciclo-vida-driver`).

  Novo manifesto `catalog-2026.07.15.json` (`previousManifest: catalog-2026.07.14.json`): só o
  profile `tplink_archer_c6_stok_v1` foi alterado — ganhou `driverConfig`, duas novas entradas de
  `fingerprintEvidence[]` do tipo `auth_mechanism` (o handshake detalhado entendido via leitura de
  código de terceiros, e a nota sobre a terceira geração de firmware não implementada), e
  `stageReason`/`confidenceScoreOverallNote` atualizados para refletir que a implementação existe
  mas segue sem validação real. `confidenceScoreOverall` permanece `0.35` — a heurística de score
  não tem categoria para "existe implementação", só para evidência real contra o equipamento, então
  nenhuma categoria muda até o teste real acontecer. `loadEmbeddedCatalogResource()` (default de
  `DriverRegistry.kt`) atualizado para apontar para o novo manifesto, seguindo a rede de segurança
  já existente (`DriverRegistryTest`, "default embedded manifest is the newest catalog file in
  resources") que detecta esse tipo de drift automaticamente.

- **2026-07-07 (TP-Link Archer C6 tem duas plataformas por firmware — refutação real + profile
  novo `tplink_archer_c6_stok_v1`)** — Teste real contra a unidade física de teste do Luiz (Archer
  C6, recém resetada de fábrica, IP `192.168.0.1`) refutou o mecanismo que o profile
  `tplink_archer_c6_v1` (driver atual `TplinkOntDriver`/`TplinkAuthenticationClient`, "web
  encrypted password": RSA sem padding + AES via `POST /cgi/getParm` + `POST /cgi_gdpr`) descreve:
  `POST /cgi/getParm` devolveu HTTP 404 — o endpoint não existe neste firmware. Investigação
  subsequente (probes passivos reais, sem credencial, mais pesquisa comunitária) revelou que esta
  unidade roda um mecanismo de login completamente diferente, do tipo `stok`/luci. Novo manifesto
  `catalog-2026.07.14.json` (`previousManifest: catalog-2026.07.13.json`):
  - **`tplink_archer_c6_v1` (inalterado em `stage`, continua `DRAFT`)**: ganhou uma entrada nova em
    `fingerprintEvidence[]` do tipo `auth_mechanism` com `confidenceLevel: REFUTED`, documentando o
    HTTP 404 real contra a unidade do Luiz; `knownFirmwareBugs[]` ganhou uma entrada confirmada
    documentando que pelo menos uma geração de firmware da linha Archer C6 abandona completamente
    o mecanismo "Web Encrypted Password" em favor do mecanismo `stok`/luci; `physicalTestAccess`
    volta para `true` (o Luiz tem uma unidade Archer C6 física real, só que ela não roda este
    mecanismo específico); `confidenceScoreOverall` recalculado de `0.4` para `0.35` (a categoria
    "autenticação testada" não pode mais contribuir em cenário otimista, já que a única execução
    real terminou em refutação, não em ausência de teste). Nenhuma capability nem `stage` mudou
    além do necessário para registrar esta evidência negativa — o profile segue `DRAFT`.
  - **`tplink_archer_c6_stok_v1` (novo, `DISCOVERY_ONLY`)**: mesma família comercial (`vendor:
    "TP-Link"`, `model: "Archer C6"`), plataforma tecnológica diferente —
    `platformId: "tplink-stok-luci"`, `driverFamilyId: "tplink-stok-luci-driver"` (driver ainda não
    implementado, é só o identificador previsto). `stage: "DISCOVERY_ONLY"` pelo mesmo critério já
    aplicado ao driver Nokia: houve contato de rede real e documentado (probes sem credencial)
    antes de qualquer tentativa de autenticação. Evidência real capturada em 2026-07-07, toda sem
    credencial: `POST /cgi/getParm` → HTTP 404 (controle negativo, motivou a investigação);
    `GET /` → HTTP 200, sem header `Server`, redireciona via meta-refresh para
    `/webpages/login.html`; `GET /webpages/login.html` → HTTP 200, título genérico `Opening...`,
    scripts `tpEncrypt.js`/`cryptoJS.min.js` (cifra client-side própria, diferente de
    `TplinkAuthCrypto`), quatro formulários (`form-first-login`, `form-login`, `form-login-bind`,
    `form-forget-password`) todos com `action="/cgi-bin/luci"` e **nenhum com campo de usuário** —
    autenticação só por senha. Evidência complementar de pesquisa comunitária (não teste real):
    pacote `tplinkrouterc6u`/`home-assistant-tplink-router` (sucessor de
    `AlexandrErohin/TP-Link-Archer-C6U`, já citado em `driver-adoption-strategy.md`) documenta duas
    gerações de login para o mesmo hardware — a antiga ("Web Encrypted Password") e uma nova via
    `POST /cgi-bin/luci/;stok=/login?form=login` com corpo JSON `sign`/`data`, chaves buscadas em
    `GET/POST /cgi-bin/luci/;stok=/login?form=keys`, sessão via token `stok` + cookie `sysauth`; uma
    issue aberta no repositório (`home-assistant-tplink-router#31`) afirma que firmwares mais novos
    não suportam mais Web Encrypted Password. Todas as capabilities ficam `UNKNOWN` (nenhuma
    leitura autenticada ainda). `physicalTestAccess: true` (mesma unidade física do Luiz).
    `confidenceScoreOverall: 0.35` (evidência de endpoint/estrutura real + evidência comunitária
    forte, mas zero autenticação testada, por design de `DISCOVERY_ONLY`).

  Este é o primeiro caso real (não hipotético) em que o mesmo vendor+modelo comercial exige dois
  profiles distintos por divergência genuina de plataforma entre gerações de firmware — documentado
  em detalhe em `docs/architecture/hal-layering-model.md`, nova seção "Caso real — TP-Link Archer C6
  com duas plataformas por firmware". Gap de `DriverRegistry.findProfile(vendor, model)` (assumia um
  único profile por vendor+modelo, ficando ambíguo com os dois profiles TP-Link/Archer C6) já
  **corrigido** — ver "Gap corrigido" no mesmo documento: `DriverRegistry` ganhou
  `findProfiles(vendor, model)`, que devolve todos os matches. Nenhum código Kotlin de driver foi
  implementado ou alterado nesta rodada (`core/driver/`, `core/auth/` intocados) — isso é trabalho de
  catálogo/pesquisa, esperando por teste real de login bem-sucedido antes de
  qualquer implementação de Driver Family nova.

- **2026-07-07 (nota de risco de `driverConfig`, revisão de segurança da Marisa)** — Adiciona a
  seção "Riscos — `driverConfig` como superfície futura de dado não confiável" acima, ressalva
  obrigatória da revisão de segurança do passo 4 do plano de refatoração HAL (reorganização do C20
  como `TpLinkLegacyCgiDriverFamily`). Sem mudança de stage, capability ou comportamento de driver
  — só documentação do gate exigido antes de qualquer `RemoteCatalogSource` real. Espelhado em
  `SECURITY.md`, seção "Catalog integrity".
- **2026-07-07 (driverConfig do TP-Link C20 — passo 4 do plano de refatoração HAL, caso de
  validação da arquitetura)** — Implementa o passo 4 de `docs/architecture/hal-layering-model.md`
  §10/§11.3: reorganiza `TplinkC20OntDriver`/`TplinkC20AuthenticationClient`/
  `TplinkC20ResponseParser`/`TplinkC20Models` (pacote `driver/tplink/`) como a primeira Driver
  Family real, `TpLinkLegacyCgiDriverFamily` (pacote `driver/family/tplink/legacycgi/`), sem
  mudança de protocolo/autenticação/retry/capabilities — mesmo comportamento observável de antes,
  só reorganização estrutural. Ponto central desta entrega: os literais de seção/campo antes
  hardcoded em `TplinkC20OntDriver.readSnapshot()` (ex.: `listOf("LAN_WLAN" to listOf("name",
  "SSID"))`) e a constante `TplinkC20AuthenticationClient.LOGIN_VALIDATION_SECTIONS` saem do código
  e passam a vir de `profile.driverConfig`, seguindo este schema concreto (opaco para o resto do
  catálogo — só `TpLinkLegacyCgiDriverFamilyFactory` interpreta):

  ```jsonc
  "driverConfig": {
    // Bundle único usado tanto para validar a credencial (não há endpoint de login dedicado
    // neste protocolo) quanto para a leitura de device info — nunca deve divergir do único
    // bundle com prova real de sucesso.
    "loginValidationBundle": {
      "sections": [
        {"section": "IGD_DEV_INFO", "fields": ["modelName", "description", "X_TP_isFD"]},
        {"section": "ETH_SWITCH", "fields": ["numberOfVirtualPorts"]},
        {"section": "SYS_MODE", "fields": ["mode"]},
        {"section": "/cgi/info", "fields": []}
      ]
    },
    // Índice posicional de cada seção dentro de loginValidationBundle.sections, usado pelo
    // parser para reencontrar o bloco certo na resposta (protocolo indexa por posição, não por
    // nome de seção).
    "deviceInfoIndex": 0,
    "ethSwitchIndex": 1,
    "sysModeIndex": 2,
    "wifiStatusBundle": {
      "sections": [{"section": "LAN_WLAN", "fields": ["name", "SSID"]}]
    },
    "wifiStatusIndex": 0,
    "connectedClientsBundle": {
      "sections": [
        {"section": "LAN_HOST_ENTRY", "fields": ["leaseTimeRemaining", "MACAddress", "hostName", "IPAddress"]}
      ]
    },
    "connectedClientsIndex": 0
  }
  ```

  Um segundo profile no mesmo protocolo (ex.: Archer C50 V2, citado como exemplo em
  `hal-layering-model.md` §9) só precisaria de um `driverConfig` próprio com os nomes de
  seção/campo daquele modelo — zero Kotlin novo. Novo manifesto `catalog-2026.07.13.json`
  (`previousManifest: catalog-2026.07.12.json`): só o profile `tplink_archer_c20_v1` ganhou
  `driverConfig` preenchido (replicando literalmente os valores antes hardcoded no driver); nenhum
  outro campo, evidência, capability ou `stage` foi alterado — esta reorganização não é promoção de
  estágio. `DriverFamilyRegistry` (`core/catalog/DriverFamilyRegistry.kt`, infraestrutura do passo
  6) ganhou sua primeira composição real: `com.nethal.core.driver.family.defaultDriverFamilyRegistry()`
  registra `TpLinkLegacyCgiDriverFamilyFactory` sob a chave `"tplink-legacy-cgi-driver"` — o fluxo
  completo (`hal-layering-model.md` §8: Profile → `DriverFamilyRegistry.resolve` → instância →
  leitura) foi verificado ponta a ponta por um teste de integração novo
  (`DriverFamilyRegistryIntegrationTest`) que carrega o profile real do catálogo embarcado, resolve
  a Driver Family via registry e lê um snapshot completo com transporte fake.
  `ManualCheckRunnerC20.kt` também foi atualizado para resolver o profile via `DriverRegistry` e
  instanciar a Driver Family via `DriverFamilyRegistry`, em vez de construir o driver antigo
  diretamente. Achado incidental durante esta entrega (não é mudança de comportamento de
  driver/protocolo, é gap de modelo de dados): os manifestos `catalog-2026.07.11` a
  `catalog-2026.07.13` já usavam os valores `"REFUTED"` (`FingerprintConfidenceLevel`) e
  `"vendor_class_reference"` (`FingerprintEvidenceType`) em `fingerprintEvidence[]`, mas nenhum dos
  dois existia no enum Kotlin correspondente — nenhum teste carregava essas versões via
  `DefaultDriverRegistry` até o teste de integração novo desta entrega, então o gap nunca havia
  quebrado nada em CI. Ambos os valores foram adicionados aos enums (`CompatibilityCatalog.kt`)
  para o catálogo real carregar sem erro — dado já existente nos manifestos publicados, não uma
  capability ou comportamento novo.
- **2026-07-07 (extensão de schema — passo 5 do plano de refatoração HAL)** — Implementa o passo 5 de
  `docs/architecture/hal-layering-model.md` §10/§11.3: estende `CompatibilityProfile` com três campos
  novos, preparando o catálogo para a camada de Driver Family que será introduzida nos passos 4 e 6
  (ainda não executados). Mudanças de schema: (1) campo `family` renomeado para `productLine` — mesma
  semântica de sempre (linha de produto comercial), só renomeado para não colidir com o novo
  `driverFamilyId` (colisão de nome apontada em `hal-layering-model.md` §3 item 7); (2) novo campo
  obrigatório `platformId` (string simples, sem tipo Kotlin — decisão explícita do Luiz de não criar
  abstração de `Platform` nesta rodada, §11.1); (3) novo campo obrigatório `driverFamilyId` (string
  simples, ainda **sem nenhuma resolução de código** — é só o nome que a Driver Family correspondente
  terá quando for criada no passo 4/§10); (4) novo campo opcional `driverConfig` (`JsonElement`,
  default `null`) — payload opaco de configuração de driver, deliberadamente sem schema comum entre
  plataformas. Novo manifesto `catalog-2026.07.12.json` (`previousManifest: catalog-2026.07.11.json`):
  os três profiles existentes (`nokia_g1425gb_v1`, `tplink_archer_c6_v1`, `tplink_archer_c20_v1`)
  ganharam `platformId`/`driverFamilyId` (`driverConfig` deixado no default `null`, pois nenhuma
  Driver Family existe ainda para consumi-lo) e tiveram `family` renomeado para `productLine` — nenhum
  outro campo, evidência, capability ou `stage` foi alterado nesta rodada. Valores escolhidos:
  Nokia G-1425G-B → `platformId: "nokia-gpon-rsa-aes"` / `driverFamilyId: "nokia-ont-gpon-driver"`;
  TP-Link Archer C6 → `platformId: "tplink-encrypted-web"` / `driverFamilyId:
  "tplink-encrypted-web-driver"`; TP-Link Archer C20 → `platformId: "tplink-legacy-cgi"` /
  `driverFamilyId: "tplink-legacy-cgi-driver"` — todos os seis valores replicam literalmente os
  identificadores de exemplo já usados em `hal-layering-model.md` §5.2/§5.5/§7, para manter
  catálogo e arquitetura no mesmo vocabulário desde o primeiro dia. Nenhuma classe `DriverFamily`,
  `DriverFamilyFactory` ou `DriverFamilyRegistry` foi criada nesta entrega — isso é escopo dos passos
  4 e 6 do plano de refatoração, ainda não executados; `driverFamilyId` por enquanto é só dado.
- **2026-07-09 (promoção para READ_ONLY_ALPHA, decisão do Rafael)** — Segunda execução real de
  `nokiaManualCheck` (mesma unidade física do Luiz, SIG-333) trouxe o probe passivo real que faltava
  desde a correção de sequência do dia anterior: título HTML da tela de login capturado —
  `html_title` = "GPON Home Gateway". O header `Server` foi verificado e confirmado como genuinamente
  ausente na resposta deste firmware (não é lacuna de captura, é fato do servidor HTTP). Novo
  manifesto `catalog-2026.07.09.json` (`previousManifest: catalog-2026.07.08.json`): `stage` do
  profile `nokia_g1425gb_v1` avança de `DISCOVERY_ONLY` para `READ_ONLY_ALPHA`, cumprindo a
  aprovação condicionada do Rafael ("assim que o dado real da página de login chegar"). Evidência
  completa: (1) probe passivo real (título capturado sem autenticação), (2) leitura autenticada real
  dos 4 endpoints já validada na execução anterior e reconfirmada nesta segunda chamada com uptimes
  coerentemente incrementados, (3) duas execuções reais consistentes contra a mesma unidade física.
  `confidenceScoreOverall` recalculado de `0.85` para `0.9` — a categoria "match de headers/banners
  reais" (0,25) passa a contribuir porque `FingerprintEngine.matchesHeaderOrBanner` aceita título OU
  header como critério de match, e o título bateu. Cálculo somaria 1.00 se todas as seis categorias
  fossem levadas ao teto simultaneamente pela primeira vez; arredondado para `0.9` por prudência
  editorial (nenhuma das duas execuções cobriu cenário de erro/timeout real, e a faixa `>0.90` da
  heurística de score é reservada para decisões de risco mais alto, não aplicável a um profile ainda
  só-leitura). Nenhuma capability de escrita foi implementada ou proposta.
- **2026-07-08 (correção de sequência de estágio, decisão do Rafael)** — O `stage` do profile
  `nokia_g1425gb_v1` avançou incorretamente na entrega anterior deste mesmo dia: o critério
  documentado de `DRAFT → DISCOVERY_ONLY` (primeiro probe passivo real, título HTML e headers
  capturados) nunca havia sido cumprido — os campos `html_title`/`http_headers` de
  `fingerprintEvidence[]` continuavam `value: null` mesmo após o login autenticado real de
  `nokiaManualCheck`. Rafael determinou que o ciclo é sequencial e não pode pular etapas: "
  `DISCOVERY_ONLY` não é sobre completude da evidência, é sobre ter havido contato de rede real e
  documentado antes de autenticar". Correção aplicada: `NokiaAuthenticationClient.login()` já fazia
  um GET real na raiz do equipamento (para extrair `pubkey`/`nonce`/`csrf_token`) — este GET agora
  também expõe título HTML e header `Server` como `NokiaDriverSnapshot.loginPageEvidence`
  (`NokiaModels.kt`), impresso pelo `ManualCheckRunner` como "Evidência de fingerprint (Tela de
  login)". Não é uma chamada de rede nova, só exposição de dado já obtido em memória. Com isso, o
  `stage` do profile passa a `DISCOVERY_ONLY` (não `READ_ONLY_ALPHA`) nesta correção — os campos
  `html_title`/`http_headers` continuam `value: null` no manifesto até o Luiz rodar
  `nokiaManualCheck` novamente (mesmo comando de sempre) e reportar o título/header reais
  capturados pela nova instrumentação. Assim que isso acontecer, e dado que a leitura autenticada
  dos 4 endpoints já está validada por execução anterior, a promoção para `READ_ONLY_ALPHA` pode
  ocorrer no mesmo ciclo, por decisão do Rafael.
- **2026-07-08** — Primeira leitura autenticada real do próprio NetHAL contra a unidade física
  Nokia G-1425G-B, via `nokiaManualCheck` (SIG-333), fechando parcialmente o critério documentado
  para `DISCOVERY_ONLY → READ_ONLY_ALPHA` (ver correção de sequência acima — o probe passivo de
  `DISCOVERY_ONLY` ainda faltava). Novo manifesto `catalog-2026.07.08.json`
  (`previousManifest: catalog-2026.07.07.json`): `firmwareKnown` do profile `nokia_g1425gb_v1`
  passa a incluir `softwareVersion=3FE49568IJJJ09` / `hardwareVersion=3FE49937ADAA`; as 5
  capabilities de leitura sobem de `EXPERIMENTAL` para `AVAILABLE`; `confidenceScoreOverall` sobe
  de `0.55` para `0.85` (recálculo item a item na nota do próprio manifesto). Documentada
  nota de mapeamento `manufacturer=ALCL` (herança Alcatel-Lucent) vs. nome comercial `Nokia`.
- **2026-07-07** — Corrigido modelo Nokia de `G-1425G-A` para `G-1425G-B` (a unidade física de
  teste do NetHAL, confirmada pelo Luiz, é o G-1425G-B; o manifesto anterior pesquisou o modelo
  errado). Novo manifesto `catalog-2026.07.07.json` (`previousManifest: catalog-2026.07.06.json`)
  com `profileId` novo (`nokia_g1425ga_v1` → `nokia_g1425gb_v1`), evidência de fingerprint agora
  citando o driver de produção do SignallQ como fonte, e `confidenceScoreOverall` recalculado de
  `0.25` para `0.55`. Implementado driver Nokia real (leitura, 4 endpoints) em
  `core/src/main/kotlin/com/nethal/core/driver/nokia/`. Profile TP-Link mantido inalterado.
- **2026-07-06** — Manifesto inicial (`catalog-2026.07.06.json`), dois profiles em `DRAFT`
  (Nokia G-1425G-A — modelo incorreto — e TP-Link Archer C6), evidência 100% documental.
