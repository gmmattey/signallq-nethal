package com.nethal.core.model

/**
 * Vocabulário oficial de capabilities — ver skill /modelo-capacidades e spec §8.6.
 * Nenhum código deve decidir comportamento por fabricante; sempre por capability.
 *
 * **`READ_MESH_TOPOLOGY`** (issue #32): capability nova, distinta de `READ_MESH_STATUS` — decisão
 * registrada porque o shape é fundamentalmente diferente (grafo de nós + clientes por nó, não um
 * enum de status tipo `Connected_Good/Bad`). Complementar a `READ_CONNECTED_CLIENTS`, não
 * substituto: primeira Driver Family real, `TpLinkStokLuciDriverFamily`/`admin/onemesh_network?form=mesh_topology`.
 *
 * **`READ_WIFI_RADIOS`** (issue #33): reaproveitada, não uma capability nova — já existia no
 * vocabulário sem implementação real. Passa a carregar canal em uso (`currentChannel`) e potência
 * de transmissão (`txPower`) por rádio, além dos mesmos campos já expostos por `READ_WIFI_STATUS`
 * (mesmo payload `CapabilityPayload.Wifi`/`WifiRadio`, mesma fonte de dado — `admin/status?form=all`
 * já lido pelo driver). Decisão: não criar uma terceira capability (`READ_WIFI_RADIO_DETAIL`) para
 * o mesmo dado.
 *
 * **`READ_DOS_PROTECTION_THRESHOLDS`** (issue #34): capability nova, leitura pura de configuração de
 * segurança já existente no equipamento (thresholds de rate-limit ICMP/SYN/UDP) — não altera nada,
 * não exige tratamento de Safety Guard além do já aplicado a qualquer leitura autenticada.
 *
 * **`RUN_NATIVE_DIAGNOSTIC_PING`** (issue #24, Feat #23): classificada como **AÇÃO**, não leitura
 * pura — dispara um teste de ping real a partir do próprio equipamento (não é config lida nem
 * gravada, mas também não é uma leitura passiva; o equipamento executa algo quando o comando chega).
 * Decisão de produto do Rafael: implementação restrita ao driver TP-Link Archer C6
 * (`TpLinkStokLuciDriverFamily`, `admin/diag?form=diag`) nesta rodada — a versão Nokia (issue #25)
 * fica pausada em backlog até revisão de segurança separada liberar. Não fluxui pelo
 * `DriverFamily.readCapability(id)`/`CapabilityEngine` genérico (que hoje é estritamente
 * `READ_ONLY` e sem parâmetro de request) — implementada como método dedicado na própria Driver
 * Family, ver `TpLinkStokLuciDriverFamily.runNativeDiagnosticPing`. Shape de request/resultado
 * agnóstico de vendor em `NativeDiagnosticPingRequest`/`NativeDiagnosticPingResult`.
 */
enum class CapabilityId {
    READ_DEVICE_INFO,
    READ_WAN_STATUS,
    READ_LAN_STATUS,
    READ_WIFI_STATUS,
    READ_WIFI_RADIOS,
    READ_CONNECTED_CLIENTS,
    READ_FIRMWARE,
    READ_UPTIME,
    READ_DNS,
    READ_DHCP,
    READ_CPU,
    READ_MEMORY,
    READ_SIGNAL,
    READ_MESH_STATUS,
    /**
     * Contadores de erro da camada GPON (FEC corrigido, erro de cabeçalho, pacotes descartados) —
     * issue #29. Distinto de `READ_SIGNAL`: sinal é potência/temperatura instantânea, isto é
     * contador cumulativo de degradação de linha óptica.
     */
    READ_GPON_ERROR_COUNTERS,
    /**
     * Status físico por porta LAN Ethernet (link up/down, velocidade negociada, erros por porta) —
     * issue #30. Capability genérica (não vendor-specific): qualquer equipamento com portas LAN
     * gerenciáveis pode implementá-la, hoje só o driver Nokia G-1425G-B tem parser real.
     */
    READ_LAN_PORT_STATUS,
    READ_MESH_TOPOLOGY,
    READ_DOS_PROTECTION_THRESHOLDS,
    SET_WIFI_SSID,
    SET_WIFI_PASSWORD,
    SET_WIFI_CHANNEL,
    SET_WIFI_BANDWIDTH,
    SET_WIFI_ENABLED,
    SET_DNS,
    REBOOT_DEVICE,
    RESTART_WIFI,
    RUN_NATIVE_DIAGNOSTIC_PING,
}

enum class CapabilityState {
    AVAILABLE,
    UNAVAILABLE,
    REQUIRES_AUTH,
    EXPERIMENTAL,
    UNSAFE,
    UNKNOWN,
}

data class Capability(
    val id: CapabilityId,
    val state: CapabilityState,
    val confidence: Double,
    val reason: String? = null,
)
