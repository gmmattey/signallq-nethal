---
name: seguranca-nethal
description: Bloqueios obrigatórios do Safety Guard, regras de autenticação e checklist de sanitização de telemetria. Consultar antes de implementar qualquer ação de escrita, fluxo de login/credencial, ou campo novo de telemetria.
---

Consulte as regras de segurança relevantes para a tarefa abaixo:

$ARGUMENTS

Fonte completa: `SECURITY.md`, `CONTRIBUTING.md`, `docs/product/specification.md` §8.4, §8.8, §8.9, §18.

---

## Proibições absolutas (sem exceção)

- Bypass de autenticação.
- Exploração de vulnerabilidade.
- Brute-force de credencial.
- Uso automático de credencial padrão sem confirmação explícita do usuário.
- Armazenamento permanente de senha de roteador ou de Wi-Fi.
- Envio de credencial para servidor/nuvem.
- Habilitar acesso remoto fora da LAN sem consentimento.
- Ação destrutiva ou silenciosa (sem aviso e sem confirmação).

## Safety Guard — bloqueios obrigatórios antes de ação de escrita

- Trocar senha Wi-Fi sem confirmação explícita.
- Trocar SSID sem confirmação.
- Desativar ambas as bandas Wi-Fi.
- Alterar LAN IP.
- Alterar DHCP range.
- Alterar modo bridge/router.
- Reset de fábrica.
- Firmware upgrade.
- Excluir configuração mesh.
- Alterar PPPoE.
- Alterar VLAN.
- Abrir portas automaticamente.

## Ações permitidas com menor risco (ainda assim com confirmação quando aplicável)

- Reboot.
- Restart Wi-Fi.
- Alterar canal Wi-Fi.
- Alterar largura de canal.
- Alterar DNS — com confirmação.
- Ativar rede guest, se suportado pelo driver.

## Autenticação — regras

- Nunca armazenar senha permanentemente — sessão local, válida só durante o uso do módulo.
- Nunca registrar senha em log, crash report ou telemetria.
- Mascarar tokens de sessão em qualquer telemetria.
- Expirar sessão ao fechar o módulo/app.
- Sempre oferecer "testar credenciais" antes de qualquer ação de escrita.
- Métodos suportados: sem auth (leitura pública), Basic, Digest, login por formulário, cookie de sessão, CSRF token, challenge/response, LuCI sysauth, SSH user/senha — nenhum implica dispensa das regras acima.

## Telemetria — o que pode e o que não pode

**Permitido coletar:** fabricante, modelo, firmware, driver usado, capabilities detectadas, protocolo detectado, resultado da autenticação (sem senha), código de erro, tempo de resposta, país/região aproximada opcional, hash anônimo da instalação.

**Proibido coletar:** senha do roteador, senha do Wi-Fi, SSID real sem consentimento, MAC completo de clientes, IP público completo, lista nominal de dispositivos, histórico de navegação, dado pessoal do usuário.

**Mascaramento obrigatório antes de qualquer envio:**

```text
SSID: CasaLuiz_5G       → SSID_HASH
MAC: AA:BB:CC:DD:EE:FF  → AA:BB:CC:**:**:**
IP público: 201.17.45.90 → 201.17.xxx.xxx
```

## Checklist antes de aprovar merge com impacto de segurança

- [ ] Nenhuma credencial é persistida em disco, `SharedPreferences`, log ou crash report
- [ ] Toda ação de escrita nova está na lista de "menor risco" ou tem revisão explícita de Marisa
- [ ] Toda ação da lista de bloqueios obrigatórios exige confirmação explícita do usuário — nunca é automática
- [ ] Campo de telemetria novo está na lista de permitido e passa pelo mascaramento correspondente
- [ ] Superfície historicamente perigosa (HNAP, credenciais padrão, Telnet) é tratada só como fingerprint, nunca como automação
- [ ] Sessão expira ao fechar o módulo

## Limites

- Esta skill define regra, não implementa — implementação cabe a Bruno/Diego, revisão final cabe a Marisa.
- Dúvida sobre risco de uma ação nova: tratar como bloqueio até revisão explícita — falha segura sempre vence.
