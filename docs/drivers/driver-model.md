# Driver Model

## Philosophy

NETHAL is capability-first.

Applications should use capabilities instead of vendor-specific conditionals.

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

SET_WIFI_SSID
SET_WIFI_PASSWORD
SET_WIFI_CHANNEL
SET_WIFI_BANDWIDTH
SET_WIFI_ENABLED
SET_DNS
REBOOT_DEVICE
RESTART_WIFI
```

## Catálogo de compatibilidade (Driver Registry)

O formato real do manifesto offline versionado que alimenta o Driver Registry — por profile
vendor/model, evidências de fingerprint, confidence score e estágio — está documentado em
`docs/drivers/compatibility-catalog.md`. Os dois primeiros profiles (Nokia G-1425G-A e TP-Link
Archer C6) ficam em `core/src/main/resources/catalog/catalog-2026.07.06.json`, ambos nascendo em
estágio `DRAFT` por serem só pesquisa documental, sem probe real ainda.
