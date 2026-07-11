# Driver Model

## Philosophy

NETHAL is capability-first.

Applications should use capabilities instead of vendor-specific conditionals.

## Sanitização de dado sensível não é responsabilidade do driver

Modelos de dado de uma Driver Family (SSID, MAC, IP, hostname de clientes conectados etc.) carregam valor bruto, real — nunca hash ou máscara aplicada pelo próprio parser do driver. Mascaramento/hash (spec §8.9) é regra da fronteira de exportação do Telemetry Collector, aplicada só se e quando um dado sai do dispositivo — nunca na representação interna usada pelo NetHAL Lab para o usuário ver a própria rede. Exceção dura, não sanitização: senha (do roteador ou do Wi-Fi) nunca é lida por nenhum driver, em nenhuma circunstância — isso não é sanitização, é proibição de coleta. Decisão e motivação completas em `docs/architecture/adr/0001-fronteira-sanitizacao-telemetria.md`.

## Capability states

```text
AVAILABLE
UNAVAILABLE
REQUIRES_AUTH
EXPERIMENTAL
UNSAFE
UNKNOWN
```

## Initial capabilities

```text
READ_DEVICE_INFO
READ_WAN_STATUS
READ_LAN_STATUS
READ_WIFI_STATUS
READ_WIFI_RADIOS
READ_CONNECTED_CLIENTS
READ_FIRMWARE
READ_UPTIME
READ_DNS
READ_DHCP
READ_CPU
READ_MEMORY
READ_SIGNAL
READ_MESH_STATUS
READ_GPON_ERROR_COUNTERS
READ_LAN_PORT_STATUS
READ_MESH_TOPOLOGY
READ_DOS_PROTECTION_THRESHOLDS

SET_WIFI_SSID
SET_WIFI_PASSWORD
SET_WIFI_CHANNEL
SET_WIFI_BANDWIDTH
SET_WIFI_ENABLED
SET_DNS
REBOOT_DEVICE
RESTART_WIFI
RUN_NATIVE_DIAGNOSTIC_PING
```

`READ_GPON_ERROR_COUNTERS` e `READ_LAN_PORT_STATUS` foram adicionadas ao vocabulário em 2026-07-11
(Feat #27, issues #29/#30), a partir do levantamento de campo do Nokia G-1425G-B
(`NOKIA_GPON_FIELD_MAP.md`, produto irmão SignallQ). `READ_SIGNAL` (issue #28, mesma Feat) não
ganhou capability nova — foi estendida (`SignalStatus.rxPowerLowerThresholdDbm`/
`rxPowerUpperThresholdDbm`/`rxPowerMarginToLowerThresholdDb`) por já cobrir o mesmo conceito
(potência óptica), decisão registrada no PR da issue. `READ_LAN_PORT_STATUS` é deliberadamente
genérica (não vendor-specific): status físico por porta Ethernet é uma necessidade de diagnóstico
comum a qualquer equipamento com portas LAN gerenciáveis, mesmo que hoje só o driver Nokia
G-1425G-B tenha parser real. Primeiro (e único, nesta rodada) caso real de ambas: `NokiaGponDriverFamily`.

**`READ_MESH_TOPOLOGY`** (issue #32) — capability nova, distinta de `READ_MESH_STATUS`: grafo de
nós mesh + clientes por nó (não um enum de status). Complementar a `READ_CONNECTED_CLIENTS`.
Primeira implementação real: `TpLinkStokLuciDriverFamily`, `admin/onemesh_network?form=mesh_topology`.

**`READ_WIFI_RADIOS`** (issue #33) — já existia no vocabulário sem implementação real; passou a
carregar canal em uso (`currentChannel`) e potência de transmissão (`txPower`) por rádio, no mesmo
`WifiRadio`/`CapabilityPayload.Wifi` já usado por `READ_WIFI_STATUS`. Decisão registrada: não criar
uma terceira capability para o mesmo dado.

**`READ_DOS_PROTECTION_THRESHOLDS`** (issue #34) — leitura pura de configuração de segurança já
existente no equipamento (thresholds de rate-limit ICMP/SYN/UDP), sem alterar nada.

**`RUN_NATIVE_DIAGNOSTIC_PING`** (Feat #23, vocabulário na Task #24) — classificada como **AÇÃO**,
não leitura pura: dispara um teste de ping real a partir do próprio equipamento. Shape de
request/resultado agnóstico de vendor (`NativeDiagnosticPingRequest`/`NativeDiagnosticPingResult`,
`core/model`). Implementação restrita ao driver TP-Link Archer C6 nesta rodada (issue #26,
`admin/diag?form=diag`) — decisão de produto do Rafael. A versão Nokia (issue #25,
`diag.cgi?ping`) fica pausada em backlog até revisão de segurança separada liberar. Não flui pelo
`DriverFamily.readCapability(id)`/`CapabilityEngine` genérico (hoje estritamente `READ_ONLY`, sem
parâmetro de request) — cada Driver Family que implementar expõe um método dedicado, ver
`TpLinkStokLuciDriverFamily.runNativeDiagnosticPing`.

## Catálogo de compatibilidade (Driver Registry)

O formato real do manifesto offline versionado que alimenta o Driver Registry — por profile
vendor/model, evidências de fingerprint, confidence score e estágio — está documentado em
`docs/drivers/compatibility-catalog.md`. O manifesto mais recente vive em
`core/src/main/resources/catalog/catalog-<YYYY.MM.DD>.json` (ver esse documento para o arquivo
vigente e o histórico de mudanças).

## Driver Family — modelo de camadas (vigente desde 2026-07-06)

A arquitetura definitiva do NetHAL está congelada em
`docs/architecture/hal-layering-model.md`. Nenhum driver deve ser organizado por vendor+modelo a
partir de agora — a cadeia oficial é:

```text
Vendor → Platform → Protocol → Authentication Strategy → Driver Family → Profile → Capability
```

Em código, isso significa:

- **Vendor/Platform/Profile são dado**, só existem como campos do `CompatibilityProfile` do
  catálogo (`vendor`, `platformId`, `profileId`) — nunca criam um tipo Kotlin próprio nem lógica.
- **Authentication Strategy** (`core/auth/AuthenticationStrategy.kt`) e **Driver Family**
  (`core/catalog/DriverFamily.kt`, implementações em `core/driver/family/<vendor>/<família>/`) são
  o único lugar com lógica de comunicação. Uma Driver Family nunca conhece um modelo específico —
  recebe endpoints/seções/campos via `CompatibilityProfile.driverConfig` (payload opaco,
  interpretado só pela própria Driver Family).
- **`profileId` resolve para uma Driver Family via `profile.driverFamilyId`**, usado pelo
  `DriverFamilyRegistry` (`core/catalog/DriverFamilyRegistry.kt`) para encontrar a
  `DriverFamilyFactory` registrada e construir a instância certa — não existe mais construção manual
  de driver por fora do catálogo.

Um modelo novo no mesmo protocolo (ex.: um segundo Archer que usa o mesmo mecanismo de
autenticação de um já suportado) é só um `Profile` novo no catálogo, sem nenhum Kotlin novo. Driver
Family nova só se justifica quando o protocolo/autenticação for genuinamente novo — critério
objetivo na §9 do documento de arquitetura, incluindo a regra "primeiro evidência, depois
abstração" para quando duas Driver Families parecidas podem (ou não) virar uma só.

**Drivers implementados como Driver Family (2026-07-10):**
- `TpLinkLegacyCgiDriverFamily` (`core/driver/family/tplink/legacycgi/`) — TP-Link Archer C20,
  protocolo dispatcher `/cgi?1&1&1&8` + HTTP Basic auth, stage `READ_ONLY_ALPHA`
- `TpLinkStokLuciDriverFamily` (`core/driver/family/tplink/stokluci/`) — TP-Link Archer C6,
  protocolo `/cgi-bin/luci` + token `stok`, stage `READ_ONLY_ALPHA`, Capability Engine com
  sessão gerenciada
- `NokiaGponDriverFamily` (`core/driver/family/nokia/gpon/`) — Nokia G-1425G-B, protocolo
  RSA+AES + GPON, stage `READ_ONLY_ALPHA`, sessão gerenciada
- `TpLinkGdprCgiDriverFamily` e `TpLinkXdrDsDriverFamily` — experimental, parser sem hardware
  confirmado, stage `DRAFT`/`EXPERIMENTAL`

Telas 1-6 do NetHAL Lab implementadas com Jetpack Compose, consumindo Driver Family via
Capability Engine: discovery (Tela 1: List, Tela 2: Detail), capabilities (Tela 4),
autenticação (Tela 5), relatório (Tela 6).
