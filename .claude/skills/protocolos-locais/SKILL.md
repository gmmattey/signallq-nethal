---
name: protocolos-locais
description: Protocolos/superfícies candidatos por prioridade, portas, heurísticas de detecção e classificação de suporte. Consultar antes de implementar Discovery Engine, Protocol Detector ou qualquer adaptador de protocolo/driver novo.
---

Consulte os protocolos e heurísticas relevantes para a tarefa abaixo:

$ARGUMENTS

Fonte completa: `docs/architecture/driver-adoption-strategy.md`, `docs/protocols/local-protocols.md`, `docs/protocols/unified-management-brazil.md`.

---

## Ordem de descoberta — obrigatória

1. **Passivo antes de credencial.** SSDP, GET em paths seguros, banners HTTP/SSH, OIDs SNMP básicos primeiro. Nunca autenticar antes de descobrir.
2. **TR-064** entra como família própria (não subcaso de SOAP genérico) — árvore de serviços e auth digest da AVM são bem documentadas.
3. **OpenWrt** merece dois adaptadores separados: LuCI JSON-RPC (legado) e ubus/rpcd (nativo, preferível).
4. **TR-069/CWMP** só entra em fingerprinting/telemetria — nunca como driver de ação local.

## Classificação de suporte por equipamento

```text
SUPPORTED
DETECTED_BUT_UNSUPPORTED
REQUIRES_AUTH
BLOCKED
UNKNOWN
```

## Portas iniciais do Discovery Engine

```text
80    HTTP
443   HTTPS
1900  SSDP/UPnP
22    SSH
23    Telnet (somente fingerprint — nunca ação; ver /seguranca-nethal)
7547  TR-069 — apenas detecção passiva, nunca controle
5000  UPnP/serviços locais
8080  HTTP alternativo
8443  HTTPS alternativo
```

## Primeiros 12 alvos de driver (Fase 1, ordem de prioridade)

1. FRITZ!Box TR-064 — valor muito alto, dificuldade baixa, risco baixo
2. UPnP/IGD genérico
3. OpenWrt ubus/rpcd
4. OpenWrt LuCI JSON-RPC
5. MikroTik RouterOS API
6. MikroTik RouterOS REST
7. ASUSWRT / AsusWRT-Merlin
8. UniFi local/official Network API
9. TP-Link Archer/Mercusys — beta forte, firmware quebra compatibilidade com frequência
10. Huawei HiLink XML API
11. ZTE Web API families
12. Xiaomi MiWiFi — leitura primeiro, escrita só em modelo validado

D-Link (HNAP) fica só como fingerprint/alerta defensivo — histórico de auth bypass e command injection. Nunca automatizar ação nessa superfície.

## Heurísticas de fingerprint por protocolo (resumo)

| Protocolo | Sinal de detecção |
|---|---|
| TR-064 | SSDP `ST: urn:dslforum-org:device:InternetGatewayDevice:1`; `LOCATION` → `tr64desc.xml`; `SERVER: FRITZBOX UPnP/...` |
| UPnP IGD | SSDP `239.255.255.250:1900`; descritor com `InternetGatewayDevice`, serviços `WANIPConnection`/`WANPPPConnection` |
| LuCI JSON-RPC | `POST /cgi-bin/luci/rpc/auth` com `login`; `Set-Cookie: sysauth=...` |
| OpenWrt ubus | `POST /ubus`; método `session login`/`session list`; presença de `uhttpd-mod-ubus` |
| MikroTik | `GET/POST /rest` (REST v7) ou handshake da API clássica |
| Huawei HiLink | `GET /api/webserver/SesTokInfo` → `SesInfo`/`TokInfo`; depois `GET /api/device/information` |
| ZTE | `/goform/goform_get_cmd_process` / `goform_set_cmd_process`; endpoints `.lua` com `_type`/`_tag` |
| Xiaomi MiWiFi | `POST /cgi-bin/luci/api/xqsystem/login`; token `stok` na URL; `/api/misystem/devicelist` |
| D-Link HNAP | `POST /HNAP1/`; header `SOAPAction` — só fingerprint, nunca automação |
| SNMP | OIDs `.1.3.6.1.2.1.1.1` (sysDescr), `.1.3.6.1.2.1.1.2` (sysObjectID), `.1.3.6.1.2.1.1.5` (sysName) |

## Scoring de confiança (0 a 1)

- 0,25 — match de headers/banners (`SERVER`, `WWW-Authenticate`, título HTML, marca em XML)
- 0,20 — match de descritor/endpoint canônico
- 0,20 — autenticação bem-sucedida no modo esperado
- 0,15 — capability sanity check coerente
- 0,10 — firmware/modelo presente no catálogo offline
- 0,10 — evidência comunitária/histórico local bem-sucedido

Regras de decisão:
- `< 0,50` → só leitura passiva, sem autenticação adicional
- `0,50–0,75` → leitura autenticada permitida
- `0,75–0,90` → escrita não destrutiva permitida
- `> 0,90` → reboot e mudanças sensíveis só com consentimento explícito

## Limites

- Esta skill orienta discovery e fingerprint, não implementa driver — implementação é do Diego.
- Ação de escrita em qualquer protocolo passa por revisão de Marisa antes de sair de `EXPERIMENTAL`.
