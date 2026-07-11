package com.nethal.core.discovery

import com.nethal.core.model.DiscoveryResult
import com.nethal.core.protocol.PrivateIpRanges
import com.nethal.core.model.NetworkTarget
import com.nethal.core.model.TargetRole
import com.nethal.core.model.TargetSource
import kotlinx.coroutines.coroutineScope

/**
 * Discovery Engine (spec §8.1). Nunca assume um único alvo: sempre devolve `DiscoveryResult`
 * com uma lista de candidatos, mesmo vazia (Tela 2b) ou com um único item (segue direto para
 * o próximo passo — decisão de navegação é do app, não deste engine).
 */
interface DiscoveryEngine {
    suspend fun discover(): DiscoveryResult
}

class DefaultDiscoveryEngine(
    private val networkEnvironmentReader: NetworkEnvironmentReader,
    private val ssdpDiscoverer: SsdpDiscoverer,
    private val upnpIgdProbe: UpnpIgdProbe,
) : DiscoveryEngine {

    override suspend fun discover(): DiscoveryResult = coroutineScope {
        val environment = networkEnvironmentReader.read()
        val gatewayIp = environment?.gatewayIp

        if (environment == null || !environment.isWifi || gatewayIp == null) {
            return@coroutineScope DiscoveryResult(devices = emptyList(), possibleDoubleNat = false)
        }

        val ssdpResponses = runCatching { ssdpDiscoverer.discover() }.getOrDefault(emptyList())

        val devices = buildDeviceList(gatewayIp, ssdpResponses)
        val possibleDoubleNat = detectDoubleNat(gatewayIp, ssdpResponses)

        DiscoveryResult(devices = devices, possibleDoubleNat = possibleDoubleNat)
    }

    private fun buildDeviceList(
        gatewayIp: String,
        ssdpResponses: List<SsdpResponse>,
    ): List<NetworkTarget> {
        val devices = LinkedHashMap<String, NetworkTarget>()
        devices[gatewayIp] = NetworkTarget(
            ip = gatewayIp,
            role = TargetRole.PRIMARY_GATEWAY,
            source = TargetSource.GATEWAY,
        )

        ssdpResponses
            .mapNotNull { it.sourceIp.takeIf(String::isNotBlank) }
            .distinct()
            .filter { it != gatewayIp }
            .forEach { ip ->
                devices[ip] = NetworkTarget(
                    ip = ip,
                    role = TargetRole.MESH_NODE,
                    source = TargetSource.SSDP,
                )
            }

        return devices.values.toList()
    }

    /**
     * Só sinaliza duplo NAT quando o probe consegue de fato ler o IP externo do gateway e
     * ele é privado (RFC 1918) ou CGNAT (RFC 6598) — falha ou ausência de UPnP no gateway
     * nunca bloqueia o discovery normal (SIG-317). Ver aviso de validação de campo em
     * `UpnpIgdProbe`.
     */
    private suspend fun detectDoubleNat(gatewayIp: String, ssdpResponses: List<SsdpResponse>): Boolean {
        val igdDescriptorUrl = ssdpResponses
            .firstOrNull { response -> response.sourceIp == gatewayIp && !response.location.isNullOrBlank() }
            ?.location
            ?: return false

        val externalIp = upnpIgdProbe.probeExternalIp(igdDescriptorUrl) ?: return false
        return isCgnatOrPrivate(externalIp)
    }

    /**
     * Heurística de duplo NAT (SIG-317): IP externo reportado pelo gateway via UPnP IGD que
     * cai em RFC 1918 (`PrivateIpRanges.isPrivate`) OU em CGNAT (RFC 6598, `100.64.0.0/10` —
     * segundo octeto entre 64 e 127) é sinal de que existe uma camada de NAT adicional entre
     * o gateway e a internet pública. CGNAT é operado pela operadora (comum no Brasil), não
     * pelo usuário, mas o efeito prático para o diagnóstico de rede é o mesmo de um duplo NAT
     * doméstico: o "IP externo" do gateway não é roteável publicamente.
     *
     * Importante — isto é *só* a heurística de negócio do duplo NAT, não um guard de
     * segurança: `PrivateIpRanges.isPrivate()` continua restrita a RFC 1918 e é a única
     * checagem usada como guarda de SSRF (`UpnpIgdProbe.isLanUrl`,
     * `HttpFingerprintProbe.isProbeAllowed`, `NokiaOntDriver`) — CGNAT não deve ampliar o que
     * é considerado "seguro para fazer request", só o que é considerado "indício de NAT
     * adicional" aqui em `detectDoubleNat`.
     */
    private fun isCgnatOrPrivate(ip: String): Boolean {
        if (PrivateIpRanges.isPrivate(ip)) return true

        val octets = ip.trim().split(".").mapNotNull { it.toIntOrNull() }
        if (octets.size != 4 || octets.any { it !in 0..255 }) return false

        return octets[0] == 100 && octets[1] in 64..127
    }
}
