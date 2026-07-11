# Architecture Overview

```text
NETHAL SDK
    ├── Discovery Engine
    ├── Fingerprint Engine
    ├── Protocol Detector
    ├── Authentication Manager
    ├── Driver Registry
    ├── Capability Engine
    ├── Command Executor
    ├── Safety Guard
    ├── Telemetry Collector
    └── Compatibility Catalog
```

## Discovery Engine

Finds the local gateway and candidate network devices.

Sources:

- Android network APIs
- default gateway
- DNS servers
- local IP/subnet
- HTTP/HTTPS probes
- SSDP/UPnP
- mDNS when available
- port probes
- HTTP headers
- TLS certificate metadata

## Safety Guard

Mandatory layer before any write action.

## Telemetry Collector

Implementado em `core/telemetry/` (issue #97, Lane A — sessão/capability; Lane B de eventos de produto ainda não, bloqueada por #66). É o único ponto autorizado a aplicar as regras de mascaramento/hash da spec §8.9 (SSID, MAC, IP público) — na fronteira de exportação, quando um dado sai do dispositivo. Modelos internos de Driver Family (ex.: `TpLinkStokLuciSnapshot`) continuam carregando dado bruto para uso local do NetHAL Lab; sanitização não é responsabilidade do parser de um driver. Ver [`adr/0001-fronteira-sanitizacao-telemetria.md`](adr/0001-fronteira-sanitizacao-telemetria.md).

- `TelemetryCollector` (interface) — `sendDiagnosticSession`/`sendCapabilityResult`, fire-and-forget, nunca lança exceção ao chamador.
- `DiagnosticSessionEvent`/`CapabilityResultEvent` — únicos tipos de entrada aceitos; não têm campo de dado bruto de rede (SSID/MAC/IP/hostname) por desenho, então é estruturalmente impossível repassar `CapabilityPayload` cru por este caminho.
- `TelemetryReasonCode.classify(rawReason)` — converte motivo de falha de driver (texto livre, pode conter IP/hostname) num vocabulário fechado antes de exportar; nunca repassa o texto de entrada.
- `HttpTelemetryCollector` — implementação real, gate por `ConsentScope.TELEMETRY_BETA` (`consentProvider: () -> Boolean`, default seguro é o chamador nunca injetar `{ true }` sem checagem real), endpoint (`TelemetryEndpointConfig`) vazio por padrão — rotas `/ingest/nethal/...` do `signallq-admin-worker` ainda não existem (depende de `linka-android#886`).
- `device_id`: UUID v4 (`TelemetryDeviceId.generate`), nunca derivado de hardware. `core:telemetry` é JVM puro (mesmo padrão de `core:consent`) — só define `TelemetryDeviceIdRepository` (contrato); persistência real via DataStore Preferences fica em `app/src/main/kotlin/com/nethal/lab/data/telemetry/TelemetryDeviceIdDataStore.kt`, mesmo mecanismo de `ConsentDataStoreRepository`.

## Driver Registry, Driver Family e Compatibility Catalog

A partir de 2026-07-06, o NetHAL segue o modelo de camadas congelado em
[`hal-layering-model.md`](hal-layering-model.md):

```text
Vendor → Platform → Protocol → Authentication Strategy → Driver Family → Profile → Capability
```

`Vendor`, `Platform` e `Profile` são dado puro do catálogo (`CompatibilityProfile`, ver
`docs/drivers/compatibility-catalog.md`) — nunca ganham lógica em Kotlin. `Authentication Strategy`
e `Driver Family` são código em `core/auth/` e `core/driver/family/<vendor>/<família>/` — nunca têm
endpoint/campo de modelo específico hardcoded, recebem essa configuração via `Profile.driverConfig`.

Resolução em runtime: Fingerprint Engine casa evidência contra um `Profile` → `DriverRegistry`
devolve o `Profile` completo → `DriverFamilyRegistry.resolve(profile)` usa `profile.driverFamilyId`
para encontrar a `DriverFamilyFactory` registrada e construir a `DriverFamily` já parametrizada →
Capability Engine consulta `profile.capabilities[]`, nunca pergunta "é TP-Link?".

Um modelo novo no mesmo protocolo (ex.: um segundo Archer na mesma linha "legacy CGI") é só um
`Profile` novo no catálogo — zero Kotlin. Só um protocolo/mecanismo de autenticação genuinamente
novo justifica uma Driver Family nova (critério objetivo na §9 do documento de arquitetura).
