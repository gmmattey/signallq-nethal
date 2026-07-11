# NetHAL — Especificação de Produto e Arquitetura

**Nome do produto:** NetHAL  
**Significado:** Network Hardware Abstraction Layer  
**Status:** Produto experimental independente, com potencial de incorporação futura ao SignallQ  
**Relação com SignallQ:** NetHAL é o motor técnico de descoberta, leitura e controle local de roteadores, ONTs, modems, APs e sistemas mesh. O SignallQ poderá consumir o NetHAL futuramente como módulo interno quando os drivers estiverem homologados.

---

## 1. Visão do Produto

O NetHAL é uma camada de abstração para equipamentos de rede doméstica e pequenos ambientes corporativos. Seu objetivo é permitir que aplicações consigam descobrir, identificar, diagnosticar e, quando autorizado, executar ações básicas em roteadores, modems, ONTs, APs e sistemas mesh conectados à mesma rede local do usuário.

A proposta não é depender da operadora, de TR-069/TR-369 ou de serviços em nuvem dos fabricantes. O foco inicial é acesso local via LAN/Wi-Fi, usando protocolos e interfaces disponíveis no próprio equipamento.

O NetHAL deve funcionar como uma biblioteca/plataforma de drivers, semelhante a uma HAL: a aplicação consumidora não precisa saber se o equipamento é Huawei, ZTE, TP-Link, Intelbras ou OpenWrt. Ela pergunta pelas capacidades disponíveis e executa comandos por uma interface comum.

---

## 2. Objetivo Estratégico

Criar uma base técnica reutilizável para:

- Identificar automaticamente o gateway da rede local.
- Detectar fabricante, modelo, firmware e protocolo disponível.
- Ler informações úteis para diagnóstico.
- Catalogar compatibilidade real por modelo e firmware.
- Permitir testes colaborativos com usuários voluntários.
- Evoluir drivers sem comprometer o SignallQ principal.
- Transformar drivers estáveis em módulos oficiais do SignallQ.

O NetHAL deve começar separado para evitar que funcionalidades instáveis prejudiquem a reputação do SignallQ.

---

## 3. Posicionamento

### NetHAL não é

- Um speedtest.
- Um app final para usuário leigo.
- Uma ferramenta de invasão.
- Um sistema de gerenciamento remoto por operadora.
- Um substituto para GenieACS, TR-069 ou TR-369.
- Um app que promete controlar qualquer roteador sem autenticação.

### NetHAL é

- Uma plataforma técnica de compatibilidade.
- Um laboratório controlado de drivers.
- Um SDK para acesso local a equipamentos de rede.
- Uma base de homologação para integração futura ao SignallQ.
- Um mecanismo de descoberta, leitura, diagnóstico e ação segura.

---

## 4. Escopo Inicial

### Incluído no MVP

- Descoberta do gateway padrão.
- Identificação básica do equipamento.
- Fingerprint de fabricante/modelo.
- Detecção de protocolos locais.
- Login manual quando necessário.
- Execução somente em rede local.
- Drivers experimentais.
- Modo read-only por padrão.
- Telemetria sanitizada de compatibilidade.
- Catálogo de modelos e firmwares.
- Relatório técnico para o usuário/testador.
- Exportação de logs técnicos anonimizados.

### Fora do MVP

- Acesso remoto fora de casa.
- Integração com operadoras.
- TR-069 ACS.
- TR-369 Controller.
- Alteração automática sem confirmação.
- Armazenamento de senha do roteador.
- Firmware upgrade.
- Backup e restore de configuração.
- Suporte universal garantido.
- Controle de equipamentos sem autenticação.
- Ações destrutivas ou de risco alto.

---

## 5. Público-Alvo Inicial

### Primário

Usuários beta técnicos ou semi-técnicos que aceitam testar compatibilidade de roteadores e ONTs em suas próprias redes.

### Secundário

- Desenvolvedores do SignallQ.
- ISPs parceiros no futuro.
- Comunidade open source.
- Usuários avançados.
- Equipes de suporte técnico.

---

## 6. Princípios do Produto

1. **Segurança antes de compatibilidade.**  
   Se uma ação pode quebrar a rede do usuário, ela não deve ser automática.

2. **Read-only primeiro.**  
   Todo driver começa lendo dados. Só depois ganha ações.

3. **Capability-based, não vendor-based.**  
   A aplicação não deve perguntar “é Huawei?”. Deve perguntar “suporta alterar canal?”.

4. **Sem senha armazenada.**  
   Credenciais do roteador devem ser usadas somente na sessão local.

5. **Driver instável não entra no SignallQ.**  
   NetHAL é laboratório. SignallQ é produto.

6. **Homologação por modelo e firmware.**  
   Não basta “funciona em TP-Link”. Precisa saber exatamente modelo e versão.

7. **Falha segura.**  
   Quando houver dúvida, o sistema deve parar, explicar e não executar.

---

## 7. Arquitetura Geral

```text
Aplicação Consumidora
        │
        ▼
NetHAL SDK
        │
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

---

## 8. Componentes Principais

### 8.1 Discovery Engine

Responsável por descobrir dispositivos relevantes na rede local.

Funções:

- Obter gateway padrão.
- Obter IP local do dispositivo.
- Obter máscara de rede.
- Identificar servidores DNS.
- Fazer probe no gateway.
- Detectar portas abertas comuns.
- Executar SSDP/UPnP discovery.
- Executar mDNS quando disponível.
- Coletar headers HTTP.
- Identificar MAC address quando permitido pelo sistema operacional.
- Usar OUI para inferir fabricante quando possível.
- Enumerar múltiplos equipamentos candidatos na sub-rede (mesh nodes, APs adicionais), não só o gateway padrão.
- Detectar indício de duplo NAT: se o IP externo reportado pelo gateway (via UPnP IGD `WANIPConnection.ExternalIPAddress`, quando disponível) também for um IP privado, sinalizar equipamento adicional provável a montante (ex.: ONT em modo router atrás do roteador do usuário).

O resultado da descoberta é sempre uma lista de equipamentos candidatos, nunca um único alvo assumido — ver `DiscoveryResult` em §13.

Portas iniciais:

```text
80    HTTP
443   HTTPS
1900  SSDP/UPnP
22    SSH
23    Telnet
7547  TR-069, apenas detecção passiva
5000  UPnP/serviços locais
8080  HTTP alternativo
8443  HTTPS alternativo
```

Observação: a porta 7547 não deve ser usada para tentar controle local. Apenas detectar exposição ou presença.

---

### 8.2 Fingerprint Engine

Responsável por identificar fabricante, modelo, firmware e família.

Fontes possíveis:

- Página inicial HTTP.
- Título HTML.
- Headers HTTP.
- Favicon hash.
- Endpoints públicos.
- Respostas SSDP.
- Página de login.
- Estrutura de URLs.
- Cookies iniciais.
- Nome de realm em Basic/Digest Auth.
- Certificado TLS local.
- OUI do MAC address.
- Strings de JavaScript.
- Arquivos comuns de configuração web.

Exemplo de fingerprint:

```json
{
  "vendor": "TP-Link",
  "model": "Archer AX23",
  "firmware": "1.1.0 Build 20231201",
  "confidence": 0.86,
  "sources": ["http_title", "favicon_hash", "login_form_signature"]
}
```

---

### 8.3 Protocol Detector

Detecta qual protocolo ou interface parece disponível.

Protocolos candidatos:

- HTTP local.
- HTTPS local.
- CGI.
- REST privado.
- SOAP.
- TR-064.
- LuCI/OpenWrt.
- JSON-RPC.
- UPnP.
- SNMP read-only.
- SSH.
- Telnet legado.

Classificação:

```text
SUPPORTED
DETECTED_BUT_UNSUPPORTED
REQUIRES_AUTH
BLOCKED
UNKNOWN
```

---

### 8.4 Authentication Manager

Gerencia autenticação local com o equipamento.

Métodos previstos:

- Sem autenticação para leitura pública.
- Basic Auth.
- Digest Auth.
- Login por formulário.
- Cookie de sessão.
- Token CSRF.
- Challenge/response.
- Login com hash MD5/SHA.
- Login com senha codificada no front-end.
- JSON login.
- LuCI sysauth.
- SSH username/password.

Regras:

- Não armazenar senha permanente.
- Não enviar credenciais para a nuvem.
- Não registrar senha em log.
- Mascarar tokens em telemetria.
- Expirar sessão ao fechar o módulo.
- Permitir “testar credenciais” antes de ações.

---

### 8.5 Driver Registry

Mantém os drivers disponíveis localmente.

Exemplo:

```text
drivers/
  generic_http/
  generic_upnp/
  generic_snmp/
  openwrt_luci/
  tplink_archer/
  tplink_deco/
  asuswrt/
  huawei_hg/
  zte_f6xx/
  fiberhome_an/
  intelbras_wifiber/
```

Cada driver deve declarar:

```json
{
  "id": "tplink_archer_v1",
  "vendor": "TP-Link",
  "families": ["Archer"],
  "protocols": ["HTTP", "HTTPS"],
  "mode": "experimental",
  "capabilities": [
    "READ_WIFI",
    "READ_WAN",
    "READ_CLIENTS",
    "REBOOT",
    "SET_WIFI_CHANNEL"
  ]
}
```

O Driver Registry sincroniza contra um catálogo offline versionado (ex.: `catalog-2026.07.05.json`), com diff incremental. O app verifica atualização ao abrir, se houver conexão, mas nunca bloqueia o uso offline — usa a última versão local disponível.

---

### 8.6 Capability Engine

O NetHAL deve expor capacidades e não fabricantes.

Capabilities iniciais:

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

`RUN_NATIVE_DIAGNOSTIC_PING` é classificada como ação (dispara um teste real no equipamento), não
leitura pura — ver `docs/drivers/driver-model.md` para o detalhe da decisão e do shape de
request/resultado.

Cada capability deve ter estado:

```text
AVAILABLE
UNAVAILABLE
REQUIRES_AUTH
EXPERIMENTAL
UNSAFE
UNKNOWN
```

Estados diferentes de `AVAILABLE` devem sempre popular o campo `reason` (ver §13) com um motivo específico: driver não suporta a ação neste modelo, estágio do driver ainda não libera a capability, ou falta de autenticação. Capability bloqueada sem motivo explicado não deve ser exibida ao usuário.

---

### 8.7 Command Executor

Executa ações padronizadas através do driver correto.

Exemplo de comando:

```json
{
  "command": "SET_WIFI_CHANNEL",
  "target": "wifi_5ghz",
  "params": {
    "channel": 44,
    "bandwidth": "80MHz"
  }
}
```

O executor deve:

- Validar parâmetros.
- Verificar capability.
- Verificar autenticação.
- Solicitar confirmação.
- Capturar o valor anterior (snapshot) antes de qualquer ação de escrita, quando o driver suportar leitura do mesmo dado — permite ao usuário reverter manualmente mesmo quando o driver não suporta rollback automatizado.
- Executar dry-run quando possível.
- Registrar resultado sanitizado.
- Reverter apenas quando o driver suportar rollback.

### Ações disruptivas (que derrubam a própria conexão)

Reboot, restart Wi-Fi e qualquer alteração de canal, largura de banda, SSID, senha ou habilitação de rádio no mesmo rádio/SSID ao qual o celular está conectado vão derrubar a conexão usada para confirmar o resultado. O Command Executor trata essa classe como execução assíncrona, não como request/response síncrono:

1. Antes de confirmar, identificar se a ação afeta o rádio/SSID atual do celular (comparar SSID/BSSID conectado com o alvo do comando).
2. Se afetar, avisar explicitamente que a conexão vai cair por alguns segundos antes de pedir confirmação.
3. Enviar o comando sem esperar a resposta HTTP como prova de sucesso — ela pode nunca chegar.
4. Após a reconexão (automática, se SSID/senha não mudaram; manual, se mudaram), rodar `healthcheck(session)` para confirmar se a mudança foi aplicada.
5. Se não houver reconexão dentro de um timeout, mostrar estado de falha ao confirmar — nunca ficar girando indefinidamente.

---

### 8.8 Safety Guard

Camada obrigatória antes de qualquer ação de escrita.

Bloqueios obrigatórios:

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

Ações permitidas com menor risco:

- Reboot.
- Restart Wi-Fi.
- Alterar canal.
- Alterar largura do canal.
- Alterar DNS, com confirmação.
- Ativar rede guest, se suportado.

Todas as ações acima, quando afetam o rádio/SSID que o próprio celular usa para se conectar, seguem o fluxo de ação disruptiva descrito em §8.7 — aviso prévio de desconexão esperada e confirmação via `healthcheck` pós-reconexão, nunca via resposta HTTP síncrona.

---

### 8.9 Telemetry Collector

Coleta dados para evolução de compatibilidade.

> Esta seção define a fronteira de exportação (o que pode sair do dispositivo), não o modelo de dados interno de um driver. Um driver pode e deve carregar SSID, MAC, IP e hostname em claro em sua própria estrutura de dados, para uso local do dono do equipamento (NetHAL Lab) — mascaramento/hash só se aplica quando/se este Telemetry Collector existir e algum dado cruzar a fronteira do dispositivo. Ver `docs/architecture/adr/0001-fronteira-sanitizacao-telemetria.md`.

Permitido coletar:

- Fabricante.
- Modelo.
- Firmware.
- Tipo de driver usado.
- Capabilities detectadas.
- Protocolo detectado.
- Resultado da autenticação, sem senha.
- Código de erro.
- Tempo de resposta.
- País/região aproximada opcional.
- Operadora informada manualmente, opcional.
- Hash anônimo da instalação.

Não coletar:

- Senha do roteador.
- Senha do Wi-Fi.
- SSID real sem consentimento.
- MAC completo de clientes.
- IP público completo.
- Lista nominal de dispositivos.
- Histórico de navegação.
- Dados pessoais do usuário.

Dados sensíveis devem ser mascarados:

```text
SSID: CasaLuiz_5G → SSID_HASH
MAC: AA:BB:CC:DD:EE:FF → AA:BB:CC:**:**:**
IP público: 201.17.45.90 → 201.17.xxx.xxx
```

---

## 9. Estados do Driver

Todo driver deve passar por estágios.

```text
DRAFT
DISCOVERY_ONLY
READ_ONLY_ALPHA
READ_ONLY_BETA
WRITE_BETA
STABLE
DEPRECATED
BLOCKED
```

### Critério para STABLE

Um driver só pode ser considerado estável quando:

- Tiver pelo menos 20 testes bem-sucedidos.
- Cobrir pelo menos 3 firmwares diferentes ou justificar firmware único.
- Tiver taxa de falha crítica abaixo de 2%.
- Não registrar ação que derrube a conectividade sem aviso.
- Tiver documentação de capabilities.
- Tiver fallback seguro.
- Tiver logs suficientes para diagnóstico.

---

## 10. Fluxo do Usuário Beta

1. Usuário abre NetHAL Lab.
2. App explica que é um módulo experimental e, antes de qualquer prompt do sistema, explica por que vai pedir permissão de localização (exigida pelo Android para ler SSID/BSSID).
3. Usuário aceita termo de teste. O consentimento é por escopo — um aceite cobre "ler status", outro "alterar configuração", outro "reiniciar o equipamento". Não é um único aceite genérico cobrindo tudo.
   - Antes do discovery ativo, app exige confirmação explícita separada de que a rede testada é do próprio usuário ou que ele tem autorização do responsável pela rede.
4. App detecta gateway.
   - Se a descoberta falhar (AP isolation, VPN ativa, rede sem gateway identificável), app mostra estado de erro dedicado com próximos passos (Tela 2b).
   - Se o Discovery Engine encontrar mais de um equipamento candidato (mesh, AP adicional, ou indício de duplo NAT), o usuário escolhe qual testar antes do fingerprint (Tela 2c).
5. App identifica equipamento.
6. App mostra fabricante/modelo provável e a confiança da identificação.
   - Se a confiança for baixa, ou o usuário souber que a identificação está errada, pode corrigi-la manualmente. A correção alimenta o Compatibility Catalog.
7. App mostra capabilities detectadas.
8. Usuário escolhe “testar leitura”.
9. Se necessário, informa login do roteador.
   - Se a WebUI do equipamento aceitar só uma sessão simultânea (comum em TP-Link e outras famílias), app avisa que deixar o painel aberto no navegador pode causar falha de autenticação.
10. App coleta dados read-only.
11. App gera relatório.
    - Se o driver for de uma família de CPE gerenciado por operadora (ONTs Huawei, ZTE, FiberHome, Nokia), o relatório sinaliza que alterações locais podem ser revertidas por reprovisionamento do ACS da operadora.
12. Usuário pode enviar relatório anonimamente.
13. Ações de escrita só aparecem se o driver permitir, sempre atrás do Safety Guard.
    - Se a ação afetar o rádio/SSID que o próprio celular usa, app avisa da desconexão esperada antes de confirmar e mostra estado "aguardando reconexão" até confirmar o resultado (Tela 7).

### Entrada e saída do programa beta

Entrada:

- Opt-in dentro do próprio app, sem necessidade de convite fechado no MVP.
- App explica o que será coletado antes do opt-in (ver §8.9 Telemetry Collector).
- Usuário recebe confirmação de que está no programa e pode revisar o que foi enviado.

Saída:

- Usuário pode sair do programa a qualquer momento nas configurações do app.
- Saída interrompe novo envio de telemetria; não retroage sobre relatórios já enviados, que são anônimos e sem vínculo reversível ao usuário.
- App deve deixar claro que dados já enviados não podem ser removidos individualmente.

---

## 11. UX Inicial

### Tela 1 — Boas-vindas

Mensagem:

```text
NetHAL Lab é uma ferramenta experimental para detectar e testar compatibilidade com roteadores, ONTs e modems na sua rede local.

Para identificar sua rede, o Android exige permissão de localização. O NetHAL usa isso apenas para ler informações de Wi-Fi (SSID/BSSID) — nunca para rastrear sua localização.
```

Ações:

- Iniciar diagnóstico. Bloqueado até o usuário confirmar, separadamente do termo de teste: "Esta é a minha rede, ou tenho autorização para testá-la."
- Ver privacidade.
- Sair.

---

### Tela 2 — Descoberta

Exibe:

- IP do aparelho.
- Gateway detectado.
- DNS.
- Rede atual.
- Status da varredura.

---

### Tela 2b — Falha na descoberta

Exibida quando o app não consegue identificar nenhum gateway válido (AP isolation, VPN ativa, rede sem gateway).

Exibe:

- Motivo provável da falha.
- Sugestões: desativar VPN, trocar de rede, informar IP do gateway manualmente.
- Botão tentar novamente.

---

### Tela 2c — Múltiplos equipamentos encontrados

Exibida quando o Discovery Engine encontra mais de um equipamento candidato (mesh, AP adicional) ou detecta indício de duplo NAT (gateway reportando IP externo que também é privado).

Exibe:

- Lista de equipamentos candidatos, com papel de cada um (gateway principal, possível equipamento a montante, nó mesh).
- Aviso quando houver indício de duplo NAT: "Pode haver um equipamento adicional entre você e a internet (ex.: ONT da operadora)."
- Ação: escolher qual equipamento testar.
- Ação: adicionar equipamento manualmente por IP.

---

### Tela 3 — Equipamento detectado

Exibe:

- Fabricante provável.
- Modelo provável.
- Firmware, se disponível.
- Protocolo detectado.
- Confiança da identificação.
- Data da última atualização do catálogo de fingerprints, para o usuário saber se a identificação pode estar desatualizada.

Ações:

- Corrigir identificação manualmente (fabricante/modelo), disponível quando a confiança for baixa ou o usuário souber que a identificação está errada. A correção alimenta o Compatibility Catalog.

---

### Tela 4 — Capabilities

Exibe lista. Todo item que não estiver `AVAILABLE` mostra o motivo (campo `reason`, ver §13), não só o estado:

```text
Ler Wi-Fi: disponível
Ler WAN: disponível
Clientes conectados: requer login
Alterar canal: experimental (estágio do driver: read-only beta)
Trocar senha: indisponível (driver não suporta esta ação neste modelo)
Reiniciar: disponível
```

---

### Tela 5 — Autenticação

Campos:

- Usuário.
- Senha.
- Botão testar.
- Aviso de que a senha não será salva.
- Aviso de que, em equipamentos que aceitam só uma sessão simultânea (comum em TP-Link e outras famílias), deixar a WebUI aberta no navegador pode causar falha de autenticação.

---

### Tela 6 — Relatório

Exibe:

- Resultado geral.
- Dados lidos.
- Capabilities.
- Erros.
- Driver usado.
- Aviso de reprovisionamento por operadora, quando o driver for de uma família de CPE-ISP (Huawei, ZTE, FiberHome, Nokia): alterações locais podem ser revertidas pelo ACS.
- Botão enviar relatório anônimo.

---

### Tela 7 — Ação em andamento (desconexão esperada)

Exibida ao confirmar uma ação que deve derrubar a conexão do próprio celular (troca de canal/banda/SSID/senha, restart Wi-Fi, reboot).

Exibe:

- Aviso: "Sua conexão pode cair por alguns segundos. O app vai confirmar o resultado assim que a rede voltar."
- Valor anterior → valor novo, para referência caso precise reverter manualmente.
- Se SSID/senha mudou: exibe a nova credencial na tela, apenas em memória, para o usuário reconectar manualmente — nunca persistida, nunca logada.
- Status: aguardando reconexão / confirmado / falha ao confirmar (timeout).
- Botão reverter, disponível quando o driver suportar a mesma escrita e o equipamento estiver alcançável de novo.

---

## 12. API Interna do SDK

Interface conceitual:

```typescript
interface NetHAL {
  discover(): Promise<DiscoveryResult>;
  fingerprint(target: NetworkTarget): Promise<FingerprintResult>;
  getCapabilities(session: RouterSession): Promise<CapabilitySet>;
  authenticate(credentials: RouterCredentials): Promise<AuthResult>;
  readDeviceInfo(): Promise<DeviceInfo>;
  readWanStatus(): Promise<WanStatus>;
  readWifiStatus(): Promise<WifiStatus>;
  readClients(): Promise<ClientList>;
  execute(command: RouterCommand): Promise<CommandResult>;
}
```

---

## 13. Modelo de Dados

### DiscoveryResult

```typescript
type DiscoveryResult = {
  devices: NetworkTarget[];
  possibleDoubleNat: boolean;
};

type NetworkTarget = {
  ip: string;
  role: "PRIMARY_GATEWAY" | "UPSTREAM_CANDIDATE" | "MESH_NODE" | "MANUAL";
  source: "GATEWAY" | "SSDP" | "MDNS" | "USER_INPUT";
};
```

`discover()` sempre retorna uma lista, mesmo quando só um equipamento é encontrado. `fingerprint(target)` recebe um `NetworkTarget` específico dessa lista — a aplicação nunca assume um único alvo implícito.

### DeviceInfo

```typescript
type DeviceInfo = {
  vendor?: string;
  model?: string;
  firmware?: string;
  hardwareVersion?: string;
  serialNumberHash?: string;
  uptimeSeconds?: number;
  deviceType?: "ROUTER" | "ONT" | "ONU" | "MESH" | "AP" | "REPEATER" | "UNKNOWN";
};
```

### WifiStatus

```typescript
type WifiStatus = {
  radios: WifiRadio[];
};

type WifiRadio = {
  id: string;
  band: "2.4GHz" | "5GHz" | "6GHz" | "UNKNOWN";
  enabled?: boolean;
  ssid?: string;
  channel?: number;
  bandwidth?: string;
  security?: string;
  clientCount?: number;
};
```

`ssid` carrega dado bruto (não hash) — corrigido de `ssidHash` (2026-07-08) por
`docs/architecture/adr/0001-fronteira-sanitizacao-telemetria.md`: sanitização de telemetria (hash de
SSID, mascaramento de MAC/IP) é responsabilidade exclusiva de um futuro Telemetry Collector,
aplicada só na fronteira de exportação — o NetHAL Lab é ferramenta local-first e precisa mostrar ao
usuário o nome real da própria rede Wi-Fi para cumprir o propósito de diagnóstico.

### WanStatus, LanStatus, ClientList

```typescript
type WanStatus = {
  ipv4Address?: string;
};

type LanStatus = {
  macAddress?: string;
  ipv4Address?: string;
};

type ClientList = {
  clients: ConnectedClient[];
};

type ConnectedClient = {
  hostname?: string;
  ipAddress?: string;
  macAddress?: string;
};
```

Tipos implícitos em §12 (`readWanStatus(): Promise<WanStatus>`, `readClients(): Promise<ClientList>`)
nunca tinham sido detalhados nesta seção — preenchidos em 2026-07-08 ao implementar o primeiro
leitor real (`TpLinkStokLuciDriverFamily`/issue #16), espelhando `core/model/WanStatus.kt`,
`core/model/LanStatus.kt` e `core/model/ConnectedClient.kt`. Mesma regra de dado bruto de
`WifiRadio.ssid` acima (ADR 0001) — sem mascaramento de MAC/IP neste modelo local.

### Capability

```typescript
type Capability = {
  id: string;
  state: "AVAILABLE" | "UNAVAILABLE" | "REQUIRES_AUTH" | "EXPERIMENTAL" | "UNSAFE" | "UNKNOWN";
  confidence: number;
  reason?: string;
};
```

Convenção: `reason` é opcional apenas para `AVAILABLE`. Para os demais estados, é obrigatório na prática — é o texto exibido na Tela 4 (§11).

---

## 14. Drivers Prioritários para MVP

### Nível 1 — Mais viáveis

1. OpenWrt/LuCI  
2. ASUSWRT  
3. TP-Link Archer  
4. Intelbras roteadores domésticos  
5. UPnP genérico read-only  

### Nível 2 — Importantes no Brasil, porém mais difíceis

6. Huawei HG/AX/ONTs  
7. ZTE F6xx/H-series  
8. FiberHome AN-series  
9. Nokia ONTs  
10. Mercusys  

### Nível 3 — Posterior

11. Xiaomi/Mi Router  
12. MikroTik RouterOS  
13. Deco Mesh  
14. TR-064 genérico  
15. SNMP genérico  

---

## 15. Estratégia de MVP

### MVP 0 — Fundação

- SDK básico.
- Discovery do gateway.
- Fingerprint inicial.
- Capability model.
- UI Lab.
- Telemetria sanitizada.
- Driver genérico HTTP/UPnP.

### MVP 1 — Read-only real

- OpenWrt/LuCI.
- ASUSWRT.
- TP-Link Archer.
- Intelbras.
- Relatório de compatibilidade.

### MVP 2 — Beta controlado

- Login local.
- Leitura autenticada.
- Clientes conectados.
- WAN/Wi-Fi.
- Reboot experimental.
- Canal Wi-Fi experimental.

### MVP 3 — Crowdsourcing

- Base de modelos.
- Envio de relatório.
- Ranking de compatibilidade.
- Programa de testers.
- Painel admin.

### MVP 4 — Integração com SignallQ

- Somente drivers STABLE.
- Somente read-only no início.
- Diagnóstico contextual.
- Ações seguras com confirmação.

---

## 16. Critérios para entrar no SignallQ

Um driver NetHAL só entra no SignallQ quando:

- Está marcado como STABLE.
- Tem documentação de limitações.
- Não depende de fluxo frágil demais.
- Foi testado em massa real.
- Não exige permissões abusivas.
- Tem fallback quando falha.
- Não prejudica a experiência principal.
- Não promete controle universal.

No SignallQ, a interface deve ser simples:

```text
Roteador detectado:
TP-Link Archer AX23

O SignallQ consegue ler:
- Status Wi-Fi
- Canal
- Clientes conectados
- WAN
- Uptime

Ações disponíveis:
- Reiniciar roteador
- Sugerir melhor canal
```

---

## 17. Riscos

### Técnicos

- APIs não documentadas.
- Firmwares customizados por operadoras.
- Mudanças entre versões.
- Bloqueio por CORS em PWA.
- Limitações de iOS/Android para varredura de rede.
- HTTPS local com certificado inválido.
- Endpoints diferentes por país.
- Apps oficiais usando criptografia/challenge próprio.

### Produto

- Usuário achar que funciona em qualquer roteador.
- Driver beta causar frustração.
- Ação mal executada derrubar rede.
- Suporte virar inferno.
- Misturar laboratório com produto final.

### Jurídicos e segurança

- Engenharia reversa pode ter restrições.
- Coleta de dados precisa de consentimento.
- Credenciais precisam ser tratadas localmente.
- Não pode induzir bypass de segurança.
- Não deve explorar falhas.
- Não deve acessar equipamento sem autorização.

---

## 18. Regras Jurídicas e Éticas

O NetHAL deve operar apenas quando:

- O usuário estiver conectado à própria rede.
- O usuário autorizar a varredura local.
- O usuário fornecer credenciais quando necessárias.
- A ação for claramente explicada.
- O usuário confirmar ações de escrita.

O NetHAL não deve:

- Burlar autenticação.
- Explorar vulnerabilidades.
- Forçar brute force.
- Usar senhas padrão automaticamente sem confirmação.
- Coletar credenciais.
- Enviar dados sensíveis para servidor.
- Abrir acesso remoto sem consentimento.

---

## 19. Painel Admin

O NetHAL precisa de um painel para acompanhar compatibilidade.

Campos:

- Fabricante.
- Modelo.
- Firmware.
- Driver.
- País.
- Operadora informada.
- Capabilities.
- Status.
- Taxa de sucesso.
- Erros mais comuns.
- Último teste.
- Versão do app.
- Versão do driver.

Estados:

```text
Novo modelo
Em análise
Compatível read-only
Compatível parcial
Compatível com ações
Instável
Bloqueado
```

---

## 20. Roadmap Sugerido

### Fase 1 — 2 a 4 semanas

- Especificação técnica.
- Estrutura do SDK.
- UI mínima.
- Discovery local.
- Modelo de telemetry.
- Driver genérico HTTP/UPnP.

### Fase 2 — 4 a 8 semanas

- OpenWrt.
- ASUSWRT.
- TP-Link.
- Intelbras.
- Relatório beta.
- Painel admin inicial.

### Fase 3 — 8 a 12 semanas

- Huawei.
- ZTE.
- FiberHome.
- Nokia.
- Coleta de massa beta.
- Capabilities por firmware.

### Fase 4 — após validação

- Integração read-only no SignallQ.
- Diagnóstico inteligente.
- Ações seguras.
- Promoção de drivers estáveis.

---

## 21. Naming Interno

Nome oficial:

```text
NetHAL
```

Tagline técnica:

```text
Universal Network Hardware Abstraction Layer
```

Nome do app/lab:

```text
NetHAL Lab
```

Nome do SDK:

```text
NetHAL Core
```

Nome do catálogo:

```text
NetHAL Compatibility Catalog
```

Nome do módulo futuro no SignallQ:

```text
SignallQ Router Intelligence powered by NetHAL
```

---

## 22. Decisão Recomendada

O NetHAL deve nascer separado do SignallQ.

O SignallQ deve continuar com o GPON já homologado e estável.

O NetHAL deve ser usado como laboratório de compatibilidade e plataforma técnica. Quando determinados drivers atingirem maturidade, entram no SignallQ como recursos oficiais.

Essa separação evita que uma ideia boa vire bagunça dentro do produto principal.

A decisão correta é:

```text
Construir NetHAL como produto técnico independente.
Testar com usuários beta.
Homologar por modelo e firmware.
Integrar ao SignallQ somente o que estiver estável.
```

---

## 23. Próximo Entregável Recomendado

Depois desta especificação, o próximo documento deve ser:

```text
NetHAL — Prompt de Desenvolvimento para AI Studio / Claude Code / Codex
```

Esse prompt deve transformar a especificação em tarefas de implementação, estrutura de pastas, interfaces, telas, banco, telemetria, drivers iniciais e critérios de aceite.
