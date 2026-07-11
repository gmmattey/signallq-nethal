package com.nethal.core.driver.family.tplink.stokluci.tooling

import com.nethal.core.catalog.DefaultDriverRegistry
import com.nethal.core.catalog.DriverFamilyRegistry
import com.nethal.core.catalog.loadEmbeddedCatalogResource
import com.nethal.core.driver.family.tplink.stokluci.TpLinkStokLuciDriverFamily
import com.nethal.core.driver.family.tplink.stokluci.TpLinkStokLuciDriverFamilyFactory
import com.nethal.core.driver.family.tplink.stokluci.TpLinkStokLuciLoginOutcome
import com.nethal.core.driver.family.tplink.stokluci.TpLinkStokLuciPingOutcome
import com.nethal.core.driver.family.tplink.stokluci.TpLinkStokLuciSnapshotOutcome
import com.nethal.core.driver.family.tplink.stokluci.TpLinkStokLuciStatusOutcome
import com.nethal.core.model.NativeDiagnosticPingRequest
import com.nethal.core.protocol.http.DefaultHttpTransport
import com.nethal.core.protocol.http.HttpTransportConfig
import kotlinx.coroutines.runBlocking
import java.net.URL

/**
 * Diagnóstico manual do TP-Link Archer C6, plataforma stok/luci (`tplink_archer_c6_stok_v1`) contra
 * hardware físico na LAN — task `:drivers:tplink-stok-luci:tplinkC6StokManualCheck`.
 *
 * Extraído do `ManualCheckRunner` unificado na modularização da ADR 0002. No lugar do antigo
 * `defaultDriverFamilyRegistry()` (composition root, hoje em `:app`), monta um `DriverFamilyRegistry`
 * local só com a factory deste módulo.
 *
 * ATENÇÃO: o protocolo foi corrigido a partir de evidência ao vivo (interceptação de XMLHttpRequest
 * em login real bem-sucedido + leitura estrutural dos scripts JS reais do equipamento) — ver KDoc de
 * TpLinkStokLuciAuthenticationClient/TpLinkStokLuciCrypto. Qualquer falha aqui deve ser documentada
 * no catálogo (fingerprintEvidence[] com confidenceLevel apropriado), nunca contornada manualmente.
 *
 * A senha nunca deve ser passada como argumento de linha de comando nem digitada numa sessão do
 * Claude Code — sempre via prompt interativo, num terminal próprio.
 */
fun main(args: Array<String>) {
    if (args.size < 2) {
        println("Uso: gradlew :drivers:tplink-stok-luci:tplinkC6StokManualCheck --args=\"<ip> <usuario>\"")
        println("A senha é pedida depois, de forma interativa — nunca via argumento.")
        return
    }

    val ip = args[0]
    val username = args[1]

    val password = readPasswordInteractively("TP-Link Archer C6 (plataforma stok/luci)")
    if (password.isBlank()) {
        println("Senha vazia, abortando.")
        return
    }

    val registry = DefaultDriverRegistry(
        embeddedManifestLoader = { loadEmbeddedCatalogResource() },
    )
    val profile = registry.findProfiles(vendor = "TP-Link", model = "Archer C6")
        .firstOrNull { it.profileId == "tplink_archer_c6_stok_v1" }
    if (profile == null) {
        println("Profile tplink_archer_c6_stok_v1 não encontrado no catálogo embarcado — catálogo desatualizado?")
        return
    }

    println("Conectando em $ip como \"$username\" (profile=${profile.profileId}, driverFamilyId=${profile.driverFamilyId})...")
    println("AVISO: protocolo corrigido a partir de evidência ao vivo (login real interceptado + leitura de JS do equipamento). A causa real do INVALID_CREDENTIALS/403 anterior foi identificada: o campo h= do envelope sign usa md5(username+password), nao md5(password). Reporte o resultado (sucesso ou falha) para atualizar o catálogo.")

    val driverFamilyRegistry = DriverFamilyRegistry(listOf(TpLinkStokLuciDriverFamilyFactory()))
    val httpTransport = DefaultHttpTransport(
        HttpTransportConfig(
            connectTimeoutMillis = 10_000,
            getReadTimeoutMillis = 20_000,
            postReadTimeoutMillis = 20_000,
            getAcceptHeader = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            postAcceptHeader = "application/json, text/javascript, */*; q=0.01",
            postContentType = "application/x-www-form-urlencoded; charset=UTF-8",
            extraPostHeaders = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
            ),
            postRefererProvider = { url ->
                val base = URL(url)
                val root = "${base.protocol}://${base.host}${if (base.port !in listOf(-1, 80, 443)) ":${base.port}" else ""}"
                if (url.contains("/cgi-bin/luci/;stok=/login")) {
                    "$root/webpages/login.html?t=1693386897767"
                } else {
                    "$root/webpages/index.1693386897767.html"
                }
            },
            followRedirectsManually = false,
        ),
    )

    val driver = try {
        driverFamilyRegistry.resolve(profile, ip, httpTransport) as TpLinkStokLuciDriverFamily
    } catch (e: IllegalArgumentException) {
        println("Host recusado: ${e.message}")
        return
    }

    val loginResult = runBlocking { driver.login(username, password) }
    when (loginResult) {
        is TpLinkStokLuciLoginOutcome.Success -> {
            println()
            println("--- Login bem-sucedido ---")
            println("stok=${loginResult.session.stok.take(6)}... (truncado, nunca logar o token completo)")
            println("(copie o resultado deste teste — sucesso ou falha — para o catálogo de compatibilidade, profile tplink_archer_c6_stok_v1)")

            println()
            println("Tentando leitura de status geral...")
            val statusResult = runBlocking { driver.readStatusRaw(username, password) }
            when (statusResult) {
                is TpLinkStokLuciStatusOutcome.Success -> {
                    println("--- Status (corpo bruto, JSON) ---")
                    println(statusResult.rawBody)
                    println("(schema já mapeado por TpLinkStokLuciStatusParser abaixo — antes de colar este corpo bruto no catálogo, mascare SSID, MAC completo e IP público, mesma regra de sanitização da spec §8.9)")
                }
                is TpLinkStokLuciStatusOutcome.Failure -> {
                    println("Falha na leitura de status: ${statusResult.reason} — ${statusResult.message}")
                }
            }

            println()
            println("Tentando leitura estruturada (readSnapshot)...")
            val snapshotResult = runBlocking { driver.readSnapshot(username, password) }
            when (snapshotResult) {
                is TpLinkStokLuciSnapshotOutcome.Success -> {
                    val snapshot = snapshotResult.snapshot
                    println("--- Wi-Fi (READ_WIFI_STATUS) ---")
                    if (snapshot.wifi.isEmpty()) println("(nenhum rádio interpretado)") else snapshot.wifi.forEach(::println)
                    println("--- LAN (READ_LAN_STATUS) ---")
                    println(snapshot.lan?.toString() ?: "(não disponível / campo ausente no payload)")
                    println("--- WAN (READ_WAN_STATUS) ---")
                    println(snapshot.wan?.toString() ?: "(não disponível / campo ausente no payload)")
                    println("--- Clientes conectados (READ_CONNECTED_CLIENTS) ---")
                    if (snapshot.connectedClients.isEmpty()) println("(nenhum cliente interpretado)") else snapshot.connectedClients.forEach(::println)
                    println("(dados já sanitizados — SSID em hash, MAC mascarado, senha do Wi-Fi nunca lida — seguros para colar no catálogo de compatibilidade)")
                }
                is TpLinkStokLuciSnapshotOutcome.Failure -> {
                    println("Falha na leitura estruturada: ${snapshotResult.reason} — ${snapshotResult.message}")
                }
            }

            println()
            println("Tentando leituras autenticadas via readCapability/session (issue #31-#34: topologia mesh, thresholds DoS)...")
            val authResult = runBlocking { driver.authenticate(username, password) }
            if (authResult is com.nethal.core.catalog.DriverFamilyAuthResult.Success) {
                val mesh = runBlocking { driver.readCapability(com.nethal.core.model.CapabilityId.READ_MESH_TOPOLOGY) }
                println("--- READ_MESH_TOPOLOGY ---")
                println(mesh)
                val dos = runBlocking { driver.readCapability(com.nethal.core.model.CapabilityId.READ_DOS_PROTECTION_THRESHOLDS) }
                println("--- READ_DOS_PROTECTION_THRESHOLDS ---")
                println(dos)
                val radios = runBlocking { driver.readCapability(com.nethal.core.model.CapabilityId.READ_WIFI_RADIOS) }
                println("--- READ_WIFI_RADIOS (canal em uso/potência) ---")
                println(radios)
                println("(copie o resultado — sucesso ou falha, mascarando SSID/MAC completo/IP público antes — para o catálogo de compatibilidade)")
            } else {
                println("authenticate() falhou para as leituras via session: $authResult")
            }

            println()
            println("--- Diagnóstico nativo de ping (issue #26, RUN_NATIVE_DIAGNOSTIC_PING) — AÇÃO REAL no equipamento ---")
            print("Rodar ping nativo agora? Alvo (ex.: 8.8.8.8) ou ENTER para pular: ")
            val pingTarget = readlnOrNull()?.trim().orEmpty()
            if (pingTarget.isNotBlank()) {
                println("Disparando ping nativo contra \"$pingTarget\" a partir do próprio Archer C6 (admin/diag?form=diag)...")
                val pingOutcome = runBlocking {
                    driver.runNativeDiagnosticPing(username, password, NativeDiagnosticPingRequest(targetHost = pingTarget, packetCount = 4))
                }
                when (pingOutcome) {
                    is TpLinkStokLuciPingOutcome.Success -> {
                        println("--- Resultado do ping nativo ---")
                        println(pingOutcome.result)
                        println("(copie rawResultText para o catálogo — é a evidência do formato real de `result` deste firmware)")
                    }
                    is TpLinkStokLuciPingOutcome.Failure -> {
                        println("Falha no ping nativo: ${pingOutcome.reason} — ${pingOutcome.message}")
                    }
                }
            } else {
                println("Ping nativo pulado.")
            }
        }
        is TpLinkStokLuciLoginOutcome.Failure -> {
            println("Falha: ${loginResult.reason} — ${loginResult.message}")
            println("Se a falha for de resposta inesperada, capture com uma ferramenta de rede (DevTools do navegador contra a WebUI real) o corpo de resposta dos endpoints form=keys/form=auth/form=login e reporte para corrigir o catálogo/driver — não tente contornar autenticação manualmente.")
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
