package com.nethal.core.driver.nokia

/**
 * Fake determinístico de [NokiaHttpTransport] — não há hardware real disponível neste ambiente,
 * então os testes do cliente/driver Nokia validam o protocolo (handshake, err_t, retry) contra
 * respostas fabricadas, não contra a unidade física. Isso fica como próximo passo do Luiz.
 */
internal class FakeNokiaHttpTransport(
    private val loginPageBody: String,
    private val loginResponses: MutableList<NokiaHttpResponse>,
    private val authenticatedPages: Map<String, String> = emptyMap(),
) : NokiaHttpTransport {

    var getCallCount = 0
        private set
    var postCallCount = 0
        private set

    override fun get(url: String, extraHeaders: Map<String, String>): NokiaHttpResponse {
        getCallCount++
        for ((path, body) in authenticatedPages) {
            if (url.endsWith(path)) return NokiaHttpResponse(200, body, emptyMap(), emptyMap())
        }
        return NokiaHttpResponse(200, loginPageBody, emptyMap(), emptyMap())
    }

    override fun post(url: String, body: String, initCookies: Map<String, String>): NokiaHttpResponse {
        postCallCount++
        check(loginResponses.isNotEmpty()) { "FakeNokiaHttpTransport: nenhuma resposta de login configurada para esta chamada" }
        return loginResponses.removeAt(0)
    }
}

internal fun sampleNokiaLoginPageHtml(): String = """
    <script>
    var pubkey = '-----BEGIN PUBLIC KEY-----\
    MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQC7VJTUt9Us8cKjMzEfYyjiWA4R\
    4/M2bS1GB4t7NXp98C3SC6dVMvDuictGeurT8jNbvJZHtCSuYEvuNMoSfm76oqFv\
    ap5UZBw==\
    -----END PUBLIC KEY-----';
    var nonce = "test-nonce";
    var token = "test-csrf-token";
    </script>
""".trimIndent()

internal fun successfulLoginResponse(sid: String = "abc123sessionid"): NokiaHttpResponse = NokiaHttpResponse(
    statusCode = 299,
    body = "",
    headers = mapOf("x-sid" to sid),
    cookies = mapOf("sid" to sid, "lsid" to "legacy-$sid", "lang" to "eng"),
)

internal fun errorLoginResponse(errT: Int): NokiaHttpResponse = NokiaHttpResponse(
    statusCode = 200,
    body = "err_t = [$errT]",
    headers = emptyMap(),
    cookies = emptyMap(),
)

internal fun sampleHomeNetworkingHtml(): String = """
    <table>
      <tr>
        <th>Connection Type</th>
        <th>Connected Devices</th>
        <th>Setting</th>
      </tr>
      <tr>
        <td>Ethernet</td>
        <td>11</td>
      </tr>
    </table>
    <table>
      <tr>
        <th>Status</th>
        <th>Connection Type</th>
        <th>Device Name</th>
        <th>IPv4 Address</th>
        <th>Hardware Address</th>
        <th>IP Address Allocation</th>
        <th>Lease Remaining</th>
        <th>Last Active Time</th>
        <th>Delete</th>
      </tr>
      <tr>
        <td><span>Active</span></td>
        <td>Ethernet</td>
        <td>Notebook da TIM</td>
        <td>192.168.1.71</td>
        <td>08:b4:d2:34:ac:d8</td>
        <td>DHCP</td>
        <td>15 hours 8 min 38 sec</td>
        <td>01/01/1970 12:23:59 AM</td>
        <td></td>
      </tr>
      <tr>
        <td>Inactive</td>
        <td>Wireless (5GHz)</td>
        <td>Luiz-PC</td>
        <td>192.168.1.89</td>
        <td>90:de:80:91:1a:1a</td>
        <td>DHCP</td>
        <td>16 hours 7 min 22 sec</td>
        <td>06/01/1970 12:42:20 AM</td>
        <td><a href="?act=del&amp;oid=1">Delete</a></td>
      </tr>
    </table>
""".trimIndent()
