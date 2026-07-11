package com.nethal.core.discovery

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * Um dispositivo respondendo a M-SEARCH SSDP (spec §8.1). `location` é a URL do descritor
 * (`rootDesc.xml`/`igd.xml` etc.), usada depois pelo probe de duplo NAT (SIG-317).
 */
data class SsdpResponse(
    val sourceIp: String,
    val location: String?,
    val server: String?,
    val searchTarget: String?,
    val usn: String?,
)

/**
 * Descoberta SSDP (UDP multicast 239.255.255.250:1900), usada para enumerar outros
 * equipamentos na sub-rede (mesh nodes, APs adicionais) e localizar o descritor UPnP IGD do
 * gateway. Só deve ser chamada com a rede Wi-Fi ativa (checagem de `NetworkCapabilities` é
 * responsabilidade de quem chama, no módulo app — ver /regras-android-nethal).
 */
interface SsdpDiscoverer {
    suspend fun discover(): List<SsdpResponse>
}

/**
 * Implementação em `java.net` puro — não depende de Android, roda igual em teste JVM.
 */
class DefaultSsdpDiscoverer(
    private val timeoutMillis: Int = 2_000,
    private val searchTarget: String = "ssdp:all",
) : SsdpDiscoverer {

    override suspend fun discover(): List<SsdpResponse> = withContext(Dispatchers.IO) {
        val responses = mutableListOf<SsdpResponse>()
        val multicastAddress = InetAddress.getByName(SSDP_MULTICAST_ADDRESS)
        val request = buildMSearchRequest(searchTarget)
        val requestBytes = request.toByteArray(Charsets.US_ASCII)

        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMillis
            val packet = DatagramPacket(
                requestBytes,
                requestBytes.size,
                InetSocketAddress(multicastAddress, SSDP_PORT),
            )
            socket.send(packet)

            val buffer = ByteArray(4096)
            val deadline = System.currentTimeMillis() + timeoutMillis
            while (System.currentTimeMillis() < deadline) {
                val responsePacket = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(responsePacket)
                } catch (_: SocketTimeoutException) {
                    break
                }
                val raw = String(
                    responsePacket.data,
                    0,
                    responsePacket.length,
                    Charsets.US_ASCII,
                )
                responses += parseResponse(raw, responsePacket.address.hostAddress ?: "")
            }
        }

        responses
    }

    private fun buildMSearchRequest(searchTarget: String): String {
        return "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: $SSDP_MULTICAST_ADDRESS:$SSDP_PORT\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: 2\r\n" +
            "ST: $searchTarget\r\n" +
            "\r\n"
    }

    private fun parseResponse(raw: String, sourceIp: String): SsdpResponse {
        val headers = raw.lineSequence()
            .drop(1)
            .mapNotNull { line ->
                val separatorIndex = line.indexOf(':')
                if (separatorIndex <= 0) return@mapNotNull null
                val key = line.substring(0, separatorIndex).trim().uppercase()
                val value = line.substring(separatorIndex + 1).trim()
                key to value
            }
            .toMap()

        return SsdpResponse(
            sourceIp = sourceIp,
            location = headers["LOCATION"],
            server = headers["SERVER"],
            searchTarget = headers["ST"],
            usn = headers["USN"],
        )
    }

    private companion object {
        const val SSDP_MULTICAST_ADDRESS = "239.255.255.250"
        const val SSDP_PORT = 1900
    }
}
