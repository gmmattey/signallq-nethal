package com.nethal.lab.ui.capabilities

import com.nethal.core.catalog.CapabilityReadResult
import com.nethal.core.model.CapabilityId
import com.nethal.core.model.CapabilityPayload
import com.nethal.core.model.CapabilityState

/**
 * Rótulo em português de cada [CapabilityId] — vocabulário de exibição da Tela 4/6 (spec §11,
 * exemplo: "Ler Wi-Fi: disponível", "Trocar senha: indisponível (...)"). Cobre TODO o enum
 * (inclusive `SET_*`/`REBOOT_*`, ainda sem nenhuma Driver Family com escrita implementada nesta
 * fase) porque a Tela 4 lista o vocabulário oficial completo de capabilities, não só as que a
 * plataforma atual sabe ler — é a própria Driver Family/`CapabilityEngine` quem decide, por item,
 * se está disponível ou não; esta função só nomeia o item para exibição, nunca decide estado.
 */
fun capabilityLabel(id: CapabilityId): String = when (id) {
    CapabilityId.READ_DEVICE_INFO -> "Informações do equipamento"
    CapabilityId.READ_WAN_STATUS -> "Ler WAN"
    CapabilityId.READ_LAN_STATUS -> "Ler LAN"
    CapabilityId.READ_WIFI_STATUS -> "Ler Wi-Fi"
    CapabilityId.READ_WIFI_RADIOS -> "Rádios Wi-Fi"
    CapabilityId.READ_CONNECTED_CLIENTS -> "Clientes conectados"
    CapabilityId.READ_FIRMWARE -> "Versão de firmware"
    CapabilityId.READ_UPTIME -> "Tempo ligado"
    CapabilityId.READ_DNS -> "Servidores DNS"
    CapabilityId.READ_DHCP -> "Configuração DHCP"
    CapabilityId.READ_CPU -> "Uso de CPU"
    CapabilityId.READ_MEMORY -> "Uso de memória"
    CapabilityId.READ_SIGNAL -> "Sinal"
    CapabilityId.READ_MESH_STATUS -> "Status de mesh"
    CapabilityId.READ_MESH_TOPOLOGY -> "Topologia mesh"
    CapabilityId.READ_DOS_PROTECTION_THRESHOLDS -> "Proteção contra DoS"
    CapabilityId.READ_GPON_ERROR_COUNTERS -> "Contadores de erro GPON"
    CapabilityId.READ_LAN_PORT_STATUS -> "Status das portas LAN"
    CapabilityId.SET_WIFI_SSID -> "Alterar nome da rede (SSID)"
    CapabilityId.SET_WIFI_PASSWORD -> "Trocar senha"
    CapabilityId.SET_WIFI_CHANNEL -> "Alterar canal"
    CapabilityId.SET_WIFI_BANDWIDTH -> "Alterar largura de banda"
    CapabilityId.SET_WIFI_ENABLED -> "Ativar/desativar Wi-Fi"
    CapabilityId.SET_DNS -> "Alterar DNS"
    CapabilityId.REBOOT_DEVICE -> "Reiniciar"
    CapabilityId.RESTART_WIFI -> "Reiniciar Wi-Fi"
    // Capability de AÇÃO (dispara teste real no equipamento), não leitura -- rótulo aqui é só
    // para a exaustividade do enum/vocabulário de exibição; a Tela 4 ainda não tem fluxo de
    // execução de ação, ver KDoc de CapabilityId.RUN_NATIVE_DIAGNOSTIC_PING no core.
    CapabilityId.RUN_NATIVE_DIAGNOSTIC_PING -> "Ping nativo do equipamento"
}

/** Rótulo em português de [CapabilityState] — mesmo vocabulário do exemplo da spec §11 ("disponível", "requer login", "experimental"). */
fun capabilityStateLabel(state: CapabilityState): String = when (state) {
    CapabilityState.AVAILABLE -> "disponível"
    CapabilityState.UNAVAILABLE -> "indisponível"
    CapabilityState.REQUIRES_AUTH -> "requer login"
    CapabilityState.EXPERIMENTAL -> "experimental"
    CapabilityState.UNSAFE -> "não seguro"
    CapabilityState.UNKNOWN -> "desconhecido"
}

/**
 * Linha de status de um item da Tela 4 ("Ler Wi-Fi: disponível", "Trocar senha: indisponível
 * (driver não suporta esta ação neste modelo)") — traduz [CapabilityReadResult] para o formato do
 * mockup da spec §11 sem esconder nenhum motivo: todo estado que não for `Success`/`AVAILABLE`
 * mostra o `reason` entre parênteses.
 */
fun capabilityStatusLine(item: CapabilityItem): String {
    val label = capabilityLabel(item.id)
    return when (val result = item.result) {
        is CapabilityReadResult.Success -> {
            val stateText = capabilityStateLabel(result.capability.state)
            val reason = result.capability.reason
            if (reason.isNullOrBlank()) "$label: $stateText" else "$label: $stateText ($reason)"
        }
        is CapabilityReadResult.Unavailable -> "$label: indisponível (${result.reason})"
        is CapabilityReadResult.Failure -> "$label: falha ao ler (${result.reason})"
        is CapabilityReadResult.SessionExpired -> "$label: sessão expirada (${result.reason})"
    }
}

/** `true` só para leitura bem-sucedida com dado real — usado para separar "dados lidos" de "capabilities"/"erros" na Tela 6. */
fun CapabilityItem.isSuccess(): Boolean = result is CapabilityReadResult.Success

/** `true` para falha de leitura de verdade (rede/protocolo/sessão) — distinto de "capability não suportada" ([CapabilityReadResult.Unavailable]), que não é um erro operacional. */
fun CapabilityItem.isReadError(): Boolean = result is CapabilityReadResult.Failure || result is CapabilityReadResult.SessionExpired

/**
 * Resumo em português do dado lido de um payload bem-sucedido — usado tanto pela Tela 4 (detalhe
 * do item) quanto pela Tela 6 ("dados lidos"). Dado bruto, sem mascaramento (ver
 * `docs/architecture/adr/0001-fronteira-sanitizacao-telemetria.md`: sanitização é fronteira de
 * exportação de um futuro Telemetry Collector, não deste dado local).
 */
fun capabilityPayloadSummary(payload: CapabilityPayload): String = when (payload) {
    is CapabilityPayload.Wifi -> if (payload.status.radios.isEmpty()) {
        "nenhum rádio Wi-Fi"
    } else {
        payload.status.radios.joinToString("; ") { radio ->
            val ssid = radio.ssid ?: "SSID não lido"
            val channel = radio.channel?.let { "canal $it" } ?: "canal não lido"
            "$ssid (${radio.band}, $channel)"
        }
    }
    is CapabilityPayload.Lan -> {
        val mac = payload.status.macAddress ?: "MAC não lido"
        val ip = payload.status.ipv4Address ?: "IP não lido"
        "MAC $mac, IP $ip"
    }
    is CapabilityPayload.Wan -> "IP ${payload.status.ipv4Address ?: "não lido"}"
    is CapabilityPayload.ConnectedClients -> {
        val clients = payload.clients.clients
        if (clients.isEmpty()) {
            "nenhum cliente conectado"
        } else {
            "${clients.size} cliente(s): " + clients.joinToString("; ") { client ->
                client.hostname ?: client.ipAddress ?: client.macAddress ?: "dispositivo sem identificação"
            }
        }
    }
    is CapabilityPayload.DeviceInfo -> {
        val info = payload.info
        val model = listOfNotNull(info.vendor, info.model).joinToString(" ").ifBlank { "modelo não lido" }
        val firmware = info.firmware?.let { "firmware $it" } ?: "firmware não lido"
        "$model, $firmware"
    }
    is CapabilityPayload.Signal -> {
        val status = payload.status
        val rx = status.rxPowerDbm?.let { "RX ${it} dBm" } ?: "RX não lido"
        val tx = status.txPowerDbm?.let { "TX ${it} dBm" } ?: "TX não lido"
        val margin = status.rxPowerMarginToLowerThresholdDb?.let { ", margem ${it} dB até o limite inferior" }.orEmpty()
        "$rx, $tx$margin"
    }
    is CapabilityPayload.GponErrorCounters -> {
        val c = payload.counters
        val fec = c.fecErrorCount?.toString() ?: "não lido"
        val hec = c.hecErrorCount?.toString() ?: "não lido"
        val drop = c.dropPacketsCount?.toString() ?: "não lido"
        "FEC $fec, HEC $hec, descartes $drop"
    }
    is CapabilityPayload.LanPorts -> {
        val ports = payload.status.ports
        if (ports.isEmpty()) {
            "nenhuma porta lida"
        } else {
            ports.joinToString("; ") { port ->
                val state = if (port.isUp) "up" else "sem link"
                val speed = port.linkSpeedMbps?.let { "$it Mbps" } ?: "velocidade não lida"
                "porta ${port.portNumber}: $state, $speed"
            }
        }
    }
    is CapabilityPayload.MeshTopology -> {
        val topology = payload.topology
        val clientCount = topology.clients.size
        val router = listOfNotNull(topology.routerModel, topology.routerName).joinToString(" ").ifBlank { "roteador não identificado" }
        val satellites = if (topology.satelliteNodeCount > 0) ", ${topology.satelliteNodeCount} extensor(es)" else ""
        "$router: $clientCount cliente(s) na malha$satellites"
    }
    is CapabilityPayload.DosProtectionThresholds -> {
        val t = payload.thresholds
        fun describe(label: String, threshold: com.nethal.core.model.DosProtectionThreshold): String {
            val low = threshold.low?.toString() ?: "?"
            val middle = threshold.middle?.toString() ?: "?"
            val high = threshold.high?.toString() ?: "?"
            return "$label $low/$middle/$high"
        }
        listOf(describe("ICMP", t.icmp), describe("SYN", t.syn), describe("UDP", t.udp)).joinToString("; ")
    }
}
