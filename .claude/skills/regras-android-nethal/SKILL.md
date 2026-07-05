---
name: regras-android-nethal
description: Regras de plataforma Android para discovery de rede local, permissões e limites de background aplicadas ao NetHAL Lab. Consultar antes de implementar Wi-Fi scan, detecção de gateway, SSDP/mDNS ou qualquer fluxo que rode em background.
---

Consulte as regras Android relevantes para a tarefa abaixo:

$ARGUMENTS

---

## Escopo

O NetHAL Lab é Android-first porque discovery de rede local e probing de dispositivo são fortemente restritos em browser/PWA (`README.md`). Estas regras cobrem o que a plataforma realmente permite — documentação oficial não equivale a comportamento real de device/OEM.

## Permissões — checklist obrigatório

- [ ] `ACCESS_FINE_LOCATION` declarado no Manifest? Obrigatório para Wi-Fi scan a partir da API 26+.
- [ ] Solicitação feita em momento contextual (na tela de descoberta, nunca no cold start)?
- [ ] Rationale exibido se o usuário negar na primeira vez?
- [ ] Fluxo degrada graciosamente com localização negada (discovery limitado, sem crash)?
- [ ] `ACCESS_BACKGROUND_LOCATION` só solicitado se houver discovery contínuo em background (API 29+) — evitar se possível, dado o perfil de privacidade do produto.

## Wi-Fi e discovery — comportamento real

| Comportamento | Restrição | API |
|---|---|---|
| `WifiInfo.getSSID()` | Retorna null sem `ACCESS_FINE_LOCATION` | 26+ |
| `WifiManager.getScanResults()` | Throttled: 4 scans/2min foreground, 1/30min background | 28+ |
| `WifiManager.getConnectionInfo()` | Deprecated — usar `NetworkCapabilities`/`WifiInfo` via `NetworkCallback` | 31+ |
| SSDP/mDNS discovery | Exige rede Wi-Fi ativa; falha silenciosamente em rede móvel — checar `NetworkCapabilities` antes |

## Gateway, DNS e IP — obtenção correta

- Gateway padrão e subnet: via `LinkProperties` da rede ativa (`ConnectivityManager.getLinkProperties(network)`), nunca hardcoded.
- DNS: `LinkProperties.getDnsServers()`; DNS privado (DoT) via `privateDnsServerName`, API 28+.
- `InetAddress.getByName()` é bloqueante — nunca chamar na main thread; sempre em coroutine/dispatcher IO.
- Sempre confirmar `network.hasCapability(NET_CAPABILITY_INTERNET)` e que a rede é Wi-Fi antes de iniciar probing — nunca varrer rede móvel.

## Background — limites reais

- Probing de rede é uma ação do usuário (tela de descoberta) — não deve rodar como serviço de background contínuo no MVP.
- Se um fluxo futuro exigir `FOREGROUND_SERVICE`, declarar tipo `FOREGROUND_SERVICE_CONNECTED_DEVICE` (API 34+) e notificação obrigatória.
- Doze Mode e App Standby podem suspender `NetworkCallback` — não assumir callback contínuo sem app em foreground.

## Restrições de Play Store relevantes

- Localização em background exige formulário de declaração e justificativa real — evitar até que haja necessidade de produto comprovada.
- Acesso a IMEI/Device ID: fortemente restrito API 29+ — usar Android ID ou instalação hash anônima (alinhado com `/seguranca-nethal`).

## Limites

- Esta skill orienta comportamento de plataforma, não implementa — implementação é do Bruno.
- Comportamento incerto em OEM específico (Samsung, MIUI) deve ser declarado explicitamente e testado em device real antes de assumir funcionamento.
