---
name: modelo-capacidades
description: Vocabulário oficial de capabilities e estados do NetHAL. Consultar antes de escrever critério de aceite, declarar capability em driver, ou implementar Capability Engine/Command Executor. Aplicação nunca pergunta "é Huawei?" — pergunta pela capability.
---

Consulte o modelo de capacidades relevante para a tarefa abaixo:

$ARGUMENTS

Fonte completa: `docs/drivers/driver-model.md` e `docs/product/specification.md` §8.6.

---

## Princípio

Toda aplicação consumidora do NetHAL (incluindo o próprio app Lab e, futuramente, o SignallQ) decide o que fazer com base em **capability**, nunca em fabricante/modelo. Se um código novo contém `if (vendor == "X")` fora da camada de driver, está errado.

## Capabilities iniciais

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

`RUN_NATIVE_DIAGNOSTIC_PING` (issues #23/#24) é classificada como **ação**, não leitura pura —
dispara um teste real no equipamento. Restrita ao driver TP-Link Archer C6 (issue #26); a versão
Nokia (issue #25) fica pausada em backlog até revisão de segurança separada.

Nomenclatura alternativa normalizada por verbo/objeto (usada em análises de arquitetura, `docs/architecture/driver-adoption-strategy.md`): `wan.status.read`, `wifi.ssid.write`, `clients.list.read`, `system.reboot.write` etc. As duas notações representam o mesmo conceito — escolher uma e manter consistência dentro do mesmo componente.

## Estados de capability

```text
AVAILABLE        — pode ser lida/executada agora
UNAVAILABLE       — equipamento não suporta
REQUIRES_AUTH     — precisa de login antes
EXPERIMENTAL      — driver ainda não homologado para essa capability
UNSAFE            — tecnicamente possível, mas bloqueada por risco
UNKNOWN           — não foi possível determinar
```

Toda capability retornada pelo Capability Engine deve vir com `state`, `confidence` (0–1) e, quando não `AVAILABLE`, um `reason`.

## Checklist ao declarar uma capability nova

- [ ] Nome segue o vocabulário acima (ou a notação verbo/objeto, sem inventar terceiro padrão)
- [ ] Estado inicial é o mais conservador possível (nunca começa em `AVAILABLE` sem evidência)
- [ ] Se for capability de escrita (`SET_*`, `REBOOT_*`), foi revisada por Marisa antes de sair de `EXPERIMENTAL`
- [ ] Confidence reflete fonte real (fingerprint, sanity check, capability testada), não um valor arbitrário
- [ ] Documentada no driver que a declara (`docs/product/specification.md` §8.5 tem o formato de manifesto do driver)

## Limites

- Esta skill define vocabulário, não implementa.
- Adicionar capability nova ao vocabulário oficial é decisão de arquitetura — passa por Rafael antes de virar padrão do repositório.
