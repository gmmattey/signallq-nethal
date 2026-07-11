package com.nethal.core.driver.tplink.tooling

import com.nethal.core.catalog.DefaultDriverRegistry
import com.nethal.core.catalog.DriverFamilyRegistry
import com.nethal.core.catalog.loadEmbeddedCatalogResource
import com.nethal.core.driver.family.tplink.legacycgi.TpLinkLegacyCgiDriverFamily
import com.nethal.core.driver.family.tplink.legacycgi.TpLinkLegacyCgiDriverFamilyFactory
import com.nethal.core.driver.family.tplink.legacycgi.TpLinkLegacyCgiReadOutcome
import com.nethal.core.driver.tplink.TplinkCipherVariant
import com.nethal.core.driver.tplink.TplinkDriverResult
import com.nethal.core.driver.tplink.TplinkOntDriver
import com.nethal.core.protocol.http.DefaultHttpTransport
import com.nethal.core.protocol.http.HttpTransportConfig
import kotlinx.coroutines.runBlocking
import java.net.URL

/**
 * Diagnóstico manual dos dois drivers TP-Link deste módulo contra hardware físico na LAN:
 *
 * - Archer C6 "encrypted-web" (`tplink_archer_c6_v1`, caminho direto via [TplinkOntDriver]) —
 *   task `:drivers:tplink-legacy-cgi:tplinkManualCheck`;
 * - Archer C20 dispatcher `/cgi` (`tplink_archer_c20_v1`, via `DriverFamilyRegistry`) —
 *   task `:drivers:tplink-legacy-cgi:tplinkC20ManualCheck`.
 *
 * Extraído do `ManualCheckRunner` unificado na modularização da ADR 0002. Cada task Gradle injeta o
 * `profileId` correto via `argumentProviders` (anexado *depois* dos argumentos de `--args`), então o
 * profile é localizado por valor em qualquer posição de [args] — os demais argumentos continuam
 * `<ip> <usuario> [cbc|gcm]`, exatamente como antes. No lugar do antigo `defaultDriverFamilyRegistry()`
 * (composition root, hoje em `:app`), este runner monta um `DriverFamilyRegistry` local com apenas a
 * factory deste módulo — não precisa das demais Driver Families.
 *
 * A senha nunca deve ser passada como argumento de linha de comando nem digitada numa sessão do
 * Claude Code — sempre via prompt interativo, num terminal próprio.
 */
private enum class LegacyProfile(val profileId: String) {
    C6("tplink_archer_c6_v1"),
    C20("tplink_archer_c20_v1"),
    ;

    companion object {
        fun fromArg(arg: String): LegacyProfile? = entries.firstOrNull { it.profileId == arg }
    }
}

fun main(args: Array<String>) {
    val profileArgIndex = args.indexOfFirst { LegacyProfile.fromArg(it) != null }
    if (profileArgIndex == -1) {
        println("Nenhum profile reconhecido em: ${args.toList()}")
        printUsage()
        return
    }
    val profile = LegacyProfile.fromArg(args[profileArgIndex])!!
    val remainingArgs = args.toMutableList().also { it.removeAt(profileArgIndex) }

    if (remainingArgs.size < 2) {
        printUsage()
        return
    }

    val ip = remainingArgs[0]
    val username = remainingArgs[1]

    when (profile) {
        LegacyProfile.C6 -> runTplinkC6(ip, username, cipherVariantArg = remainingArgs.getOrNull(2))
        LegacyProfile.C20 -> runTplinkC20(ip, username)
    }
}

private fun printUsage() {
    println("Uso: gradlew :drivers:tplink-legacy-cgi:tplinkManualCheck --args=\"<ip> <usuario> [cbc|gcm]\"")
    println("   ou gradlew :drivers:tplink-legacy-cgi:tplinkC20ManualCheck --args=\"<ip> <usuario>\"")
    println("(cada task já injeta o profileId correto; não é preciso digitá-lo)")
    println("A senha é pedida depois, de forma interativa — nunca via argumento.")
}

// --- TP-Link Archer C6 (tplink_archer_c6_v1) — caminho direto, ainda não é Driver Family. ---

private fun runTplinkC6(ip: String, username: String, cipherVariantArg: String?) {
    val cipherVariant = when (cipherVariantArg?.lowercase()) {
        "gcm" -> TplinkCipherVariant.AES_GCM
        else -> TplinkCipherVariant.AES_CBC
    }

    val password = readPasswordInteractively("TP-Link Archer C6")
    if (password.isBlank()) {
        println("Senha vazia, abortando.")
        return
    }

    println("Conectando em $ip como \"$username\" (cifra: $cipherVariant)...")

    val driver = try {
        TplinkOntDriver(ip, cipherVariant = cipherVariant)
    } catch (e: IllegalArgumentException) {
        println("Host recusado: ${e.message}")
        return
    }

    val result = runBlocking { driver.readSnapshot(username, password) }

    when (result) {
        is TplinkDriverResult.Success -> {
            val snapshot = result.snapshot
            println()
            println("--- Device Info ---")
            println(snapshot.deviceInfo?.toString() ?: "(não disponível / falha ao interpretar resposta)")
            println("--- WAN ---")
            println(snapshot.wan?.toString() ?: "(não disponível / falha ao interpretar resposta)")
            println("--- Wi-Fi ---")
            if (snapshot.wifi.isEmpty()) println("(nenhuma banda interpretada)") else snapshot.wifi.forEach(::println)
            println("--- Clientes conectados ---")
            if (snapshot.connectedClients.isEmpty()) println("(nenhum cliente interpretado)") else snapshot.connectedClients.forEach(::println)
            println("(copie estes valores, e quaisquer diferenças de endpoint/campo observadas, para o catálogo de compatibilidade)")
        }
        is TplinkDriverResult.Failure -> {
            println("Falha: ${result.reason} — ${result.message}")
            if (cipherVariant == TplinkCipherVariant.AES_CBC) {
                println("Se a falha for de resposta inesperada durante o login, tente novamente com o argumento 'gcm' (firmware recente pode usar AES-GCM em vez de AES-CBC).")
            }
        }
    }
}

// --- TP-Link Archer C20 (tplink_archer_c20_v1) — via DriverRegistry/DriverFamilyRegistry. ---

private fun runTplinkC20(ip: String, username: String) {
    val password = readPasswordInteractively("TP-Link Archer C20")
    if (password.isBlank()) {
        println("Senha vazia, abortando.")
        return
    }

    val registry = DefaultDriverRegistry(
        embeddedManifestLoader = { loadEmbeddedCatalogResource() },
    )
    val profile = registry.findProfile(vendor = "TP-Link", model = "Archer C20")
    if (profile == null) {
        println("Profile tplink_archer_c20_v1 não encontrado no catálogo embarcado — catálogo desatualizado?")
        return
    }

    println("Conectando em $ip como \"$username\" (profile=${profile.profileId}, driverFamilyId=${profile.driverFamilyId})...")

    val driverFamilyRegistry = DriverFamilyRegistry(listOf(TpLinkLegacyCgiDriverFamilyFactory()))
    // Mesmos parâmetros de DefaultTplinkHttpTransport (driver/tplink/TplinkHttpTransport.kt) —
    // preserva exatamente o transporte usado antes desta reorganização.
    val httpTransport = DefaultHttpTransport(
        HttpTransportConfig(
            connectTimeoutMillis = 10_000,
            getReadTimeoutMillis = 20_000,
            postReadTimeoutMillis = 20_000,
            getAcceptHeader = "application/json, text/html,*/*;q=0.9",
            postAcceptHeader = "application/json, text/plain, */*",
            postContentType = "text/plain",
            postRefererProvider = { url ->
                val base = URL(url)
                "${base.protocol}://${base.host}${if (base.port !in listOf(-1, 80, 443)) ":${base.port}" else ""}/"
            },
            followRedirectsManually = false,
        ),
    )

    val driver = try {
        driverFamilyRegistry.resolve(profile, ip, httpTransport) as TpLinkLegacyCgiDriverFamily
    } catch (e: IllegalArgumentException) {
        println("Host recusado: ${e.message}")
        return
    }

    val result = runBlocking { driver.readSnapshot(username, password) }

    when (result) {
        is TpLinkLegacyCgiReadOutcome.Success -> {
            val snapshot = result.snapshot
            println()
            println("--- Device Info ---")
            println(snapshot.deviceInfo?.toString() ?: "(não disponível / falha ao interpretar resposta)")
            println("--- WAN ---")
            println("(READ_WAN_STATUS ainda é UNKNOWN — seção real não capturada, não implementada)")
            println("--- Wi-Fi ---")
            if (snapshot.wifi.isEmpty()) println("(nenhuma banda interpretada)") else snapshot.wifi.forEach(::println)
            println("--- Clientes conectados ---")
            if (snapshot.connectedClients.isEmpty()) println("(nenhum cliente interpretado)") else snapshot.connectedClients.forEach(::println)
            println("(copie estes valores, e quaisquer diferenças de seção/campo observadas, para o catálogo de compatibilidade — profile tplink_archer_c20_v1)")
        }
        is TpLinkLegacyCgiReadOutcome.Failure -> {
            println("Falha: ${result.reason} — ${result.message}")
            println("Se a falha for de credencial/resposta inesperada, capture com uma ferramenta de rede (DevTools do navegador contra a WebUI real) o corpo de resposta e reporte para corrigir o catálogo/driver — não tente contornar autenticação manualmente.")
        }
    }
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
