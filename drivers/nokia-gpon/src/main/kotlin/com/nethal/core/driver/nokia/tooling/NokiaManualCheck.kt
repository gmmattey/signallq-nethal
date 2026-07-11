package com.nethal.core.driver.nokia.tooling

import com.nethal.core.driver.nokia.NokiaAuthenticationClient
import com.nethal.core.driver.nokia.NokiaLoginException
import com.nethal.core.driver.nokia.NokiaResponseParser
import com.nethal.core.protocol.PrivateIpRanges

/**
 * Diagnóstico manual do driver Nokia G-1425G-B (`nokia_g1425gb_v1`) contra hardware físico na LAN.
 * Não é chamado por nenhum outro código do produto e nunca roda em `test`/CI — só via
 * `./gradlew :drivers:nokia-gpon:nokiaManualCheck --args="<ip> <usuario>"`, disparado manualmente
 * pelo próprio usuário no terminal dele.
 *
 * Extraído do `ManualCheckRunner` unificado (antigo `core/tooling`) na modularização da ADR 0002 —
 * cada driver passou a carregar sua própria ferramenta de diagnóstico, sem um runner central que
 * dependesse de todas as Driver Families. Nome da task (`nokiaManualCheck`) e forma de uso
 * (`--args="<ip> <usuario>"`) preservados de propósito.
 *
 * A senha nunca deve ser passada como argumento de linha de comando (ficaria no histórico do
 * shell) nem digitada numa sessão do Claude Code — sempre via prompt interativo, num terminal
 * próprio, fora de qualquer transcript de conversa.
 *
 * Diagnóstico: em vez de `NokiaOntDriver.readSnapshot` (que só expõe o snapshot já parseado), usa
 * `NokiaAuthenticationClient` diretamente para imprimir o corpo bruto de cada um dos 5 endpoints
 * lado a lado com o resultado do `NokiaResponseParser` — único jeito de saber se uma falha de
 * parsing é campo renomeado (variante de firmware) ou sessão rejeitada pelo equipamento. Login
 * único, uma leitura por endpoint — não repete a sessão para não martelar o equipamento real.
 *
 * Nunca imprime a senha nem o `sid`/`X-SID` da sessão. O corpo bruto impresso pode conter SSID em
 * claro, MAC completo, IP público ou serial — mascare antes de colar em qualquer lugar (catálogo,
 * issue, chat), mesma regra de sanitização da spec §8.9.
 */
fun main(args: Array<String>) {
    if (args.size < 2) {
        println("Uso: gradlew :drivers:nokia-gpon:nokiaManualCheck --args=\"<ip> <usuario>\"")
        println("A senha é pedida depois, de forma interativa — nunca via argumento.")
        return
    }

    val ip = args[0]
    val username = args[1]

    val password = readPasswordInteractively("Nokia")
    if (password.isBlank()) {
        println("Senha vazia, abortando.")
        return
    }

    if (!PrivateIpRanges.isPrivate(ip)) {
        println("Host recusado: só opera contra IP privado (RFC 1918) da LAN; host recebido: $ip")
        return
    }

    println("Conectando em $ip como \"$username\"...")

    val client = NokiaAuthenticationClient(ip)
    try {
        client.login(username, password)
    } catch (e: NokiaLoginException) {
        println("Falha no login: ${e.reason} — ${e.message}")
        return
    } catch (e: Exception) {
        println("Falha no login: ${e.message}")
        return
    }

    println()
    println("--- Evidência de fingerprint (Tela de login) ---")
    val evidence = client.loginPageEvidence
    println("Título HTML: ${evidence?.httpTitle ?: "(não capturado)"}")
    println("Header Server: ${evidence?.serverHeader ?: "(não capturado / ausente na resposta)"}")
    println("(dados não sensíveis — sem credencial; copie estes dois valores para o catálogo de compatibilidade)")

    val endpoints = listOf(
        "GPON" to "/wan_status.cgi?gpon",
        "WAN" to "/show_wan_status.cgi?ipv4",
        "PPP" to "/index.cgi?getppp",
        "Device Info" to "/device_status.cgi",
        "Clientes conectados" to "/lan_status.cgi?wlan",
    )

    val bodies = mutableMapOf<String, String>()
    for ((label, path) in endpoints) {
        println()
        println("--- $label ($path) ---")
        val raw = try {
            client.fetchAuthenticated(path)
        } catch (e: Exception) {
            println("Falha ao buscar: ${e.message}")
            continue
        }
        bodies[label] = raw
        println("Corpo bruto (${raw.length} chars — mascare SSID/MAC completo/IP público/serial antes de colar em qualquer lugar):")
        println(raw.take(3000))
        if (raw.length > 3000) println("... (truncado, ${raw.length - 3000} chars a mais)")
    }

    println()
    println("--- Comparação com NokiaResponseParser (o que o driver de produção conseguiria extrair deste corpo) ---")
    bodies["GPON"]?.let { println("GPON: ${NokiaResponseParser.parseGponStatus(it) ?: "(parser não encontrou os campos esperados neste corpo)"}") }
    bodies["WAN"]?.let { println("WAN: ${NokiaResponseParser.parseWanStatus(it) ?: "(parser não encontrou os campos esperados neste corpo)"}") }
    bodies["PPP"]?.let { println("PPP: ${NokiaResponseParser.parsePppStatus(it) ?: "(parser não encontrou os campos esperados neste corpo)"}") }
    bodies["Device Info"]?.let { println("Device Info: ${NokiaResponseParser.parseDeviceInfo(it) ?: "(parser não encontrou os campos esperados neste corpo)"}") }
    bodies["Clientes conectados"]?.let {
        val clients = NokiaResponseParser.parseConnectedClients(it)
        println("Clientes conectados: ${if (clients.isEmpty()) "(nenhum interpretado)" else clients.toString()}")
    }
    println("(reporte esta comparação bruto x parseado para corrigir NokiaResponseParser se for divergência de formato — não decida promoção de estágio sozinho)")
}

/**
 * Lê a senha via `System.console()`. Sem console interativo (comum ao rodar via IDE/Gradle
 * Daemon), cai para `readlnOrNull()` avisando que o valor pode ficar visível no terminal.
 */
private fun readPasswordInteractively(promptLabel: String): String {
    val console = System.console()
    return if (console != null) {
        String(console.readPassword("Senha do $promptLabel (não aparece na tela): "))
    } else {
        println("Aviso: console interativo não detectado (comum ao rodar via IDE/Gradle Daemon).")
        println("A senha pode ficar visível neste terminal. Prefira rodar via `gradlew` direto num shell.")
        print("Senha do $promptLabel: ")
        readlnOrNull().orEmpty()
    }
}
