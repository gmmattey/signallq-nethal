package com.nethal.core.discovery

import com.nethal.core.protocol.PrivateIpRanges
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * Heurística de duplo NAT (SIG-317, spec §8.1 e driver-adoption-strategy.md "UPnP/IGD
 * genérico"): busca o descritor UPnP IGD do gateway, localiza o serviço WAN
 * (`WANIPConnection`/`WANPPPConnection`) e invoca `GetExternalIPAddress`. Se o IP externo
 * devolvido também for RFC 1918, há indício de equipamento adicional a montante (ex.: ONT em
 * modo router atrás do roteador do usuário).
 *
 * IMPORTANTE — pendência de validação de campo: esta heurística nunca foi testada contra
 * hardware real (CGNAT de operadora, ONT em bridge/router, roteador com UPnP desabilitado).
 * Os testes automatizados cobrem parsing e a regra RFC 1918, não o comportamento real de
 * equipamento. Ver nota de validação com Diego antes de promover a heurística além de sinal
 * informativo na UI.
 */
interface UpnpIgdProbe {
    /**
     * Retorna `null` quando o probe falha ou o gateway não expõe UPnP IGD — isso nunca deve
     * bloquear o fluxo normal de discovery (SIG-317), apenas deixar de sinalizar duplo NAT.
     */
    suspend fun probeExternalIp(descriptorUrl: String): String?

    companion object {
        val WAN_SERVICE_TYPES = listOf(
            "urn:schemas-upnp-org:service:WANIPConnection:1",
            "urn:schemas-upnp-org:service:WANIPConnection:2",
            "urn:schemas-upnp-org:service:WANPPPConnection:1",
        )
    }
}

class DefaultUpnpIgdProbe(
    private val connectTimeoutMillis: Int = 3_000,
    private val readTimeoutMillis: Int = 3_000,
) : UpnpIgdProbe {

    override suspend fun probeExternalIp(descriptorUrl: String): String? = withContext(Dispatchers.IO) {
        try {
            if (!isLanUrl(descriptorUrl)) return@withContext null
            val descriptorXml = fetch(descriptorUrl) ?: return@withContext null
            val wanService = resolveWanIpConnectionService(descriptorXml, descriptorUrl)
                ?: return@withContext null
            if (!isLanUrl(wanService.controlUrl)) return@withContext null
            invokeGetExternalIpAddress(wanService.controlUrl, wanService.serviceType)
        } catch (_: Exception) {
            null
        }
    }

    private fun fetch(url: String): String? {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.requestMethod = "GET"
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.bufferedReader().use(BufferedReader::readText)
        } finally {
            connection.disconnect()
        }
    }

    private fun invokeGetExternalIpAddress(controlUrl: String, serviceType: String): String? {
        val soapAction = "\"$serviceType#GetExternalIPAddress\""
        val soapBody = """<?xml version="1.0"?>
            |<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
            |  <s:Body>
            |    <u:GetExternalIPAddress xmlns:u="$serviceType" />
            |  </s:Body>
            |</s:Envelope>
        """.trimMargin()

        val connection = URL(controlUrl).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            connection.setRequestProperty("SOAPAction", soapAction)
            connection.outputStream.use { it.write(soapBody.toByteArray(Charsets.UTF_8)) }

            if (connection.responseCode !in 200..299) return null
            val responseXml = connection.inputStream.bufferedReader().use(BufferedReader::readText)
            EXTERNAL_IP_REGEX.find(responseXml)?.groupValues?.get(1)?.trim()
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        val EXTERNAL_IP_REGEX = Regex("<NewExternalIPAddress>(.*?)</NewExternalIPAddress>")
    }
}

internal data class WanIpConnectionService(
    val serviceType: String,
    val controlUrl: String,
)

/**
 * Parsing por regex, não XML DOM completo — os descritores IGD são pequenos e o objetivo é
 * extrair `serviceType`/`controlURL` de um `WANIPConnection`/`WANPPPConnection`, não validar
 * o XML inteiro. Função livre (não presa a `DefaultUpnpIgdProbe`) para ficar testável sem
 * subir HTTP de verdade. O `serviceType` retornado é o que foi de fato encontrado no
 * descritor — necessário para montar o SOAPAction correto (a action é `serviceType +
 * "#GetExternalIPAddress"`, e usar o serviceType errado leva o gateway a rejeitar a chamada).
 */
internal fun resolveWanIpConnectionService(descriptorXml: String, descriptorUrl: String): WanIpConnectionService? {
    val serviceBlocks = SERVICE_REGEX.findAll(descriptorXml).map { it.value }.toList()
    val wanServiceBlock = serviceBlocks.firstOrNull { block ->
        UpnpIgdProbe.WAN_SERVICE_TYPES.any { serviceType -> block.contains(serviceType) }
    } ?: return null

    val serviceType = UpnpIgdProbe.WAN_SERVICE_TYPES.first { wanServiceBlock.contains(it) }
    val controlUrlMatch = CONTROL_URL_REGEX.find(wanServiceBlock) ?: return null
    val controlUrl = resolveAgainstBase(descriptorUrl, controlUrlMatch.groupValues[1].trim())

    return WanIpConnectionService(serviceType = serviceType, controlUrl = controlUrl)
}

private fun resolveAgainstBase(baseUrl: String, relativeOrAbsolute: String): String {
    return try {
        URI(baseUrl).resolve(relativeOrAbsolute).toString()
    } catch (_: Exception) {
        relativeOrAbsolute
    }
}

/**
 * Mitigação de SSRF apontada por Marisa na revisão de segurança da Feat 2: a `LOCATION` do
 * SSDP e o `controlURL` do descritor vêm de um dispositivo não confiável na LAN. Sem essa
 * checagem, um gateway comprometido poderia anunciar uma URL apontando para um host público e
 * o probe faria requisições HTTP para fora da rede local usando a permissão `INTERNET`. Só
 * prossegue quando o host resolvido é um IP RFC 1918 — hostnames e IPs públicos são rejeitados
 * por padrão (falha segura).
 */
internal fun isLanUrl(url: String): Boolean {
    val host = try {
        URL(url).host
    } catch (_: Exception) {
        return false
    }
    return PrivateIpRanges.isPrivate(host)
}

private val SERVICE_REGEX = Regex("<service>.*?</service>", RegexOption.DOT_MATCHES_ALL)
private val CONTROL_URL_REGEX = Regex("<controlURL>(.*?)</controlURL>")
