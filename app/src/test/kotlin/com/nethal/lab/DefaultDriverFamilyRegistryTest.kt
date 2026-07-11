package com.nethal.lab

import com.nethal.core.catalog.DefaultDriverRegistry
import com.nethal.core.catalog.loadEmbeddedCatalogResource
import com.nethal.core.protocol.http.HttpTransport
import com.nethal.core.protocol.http.HttpTransportResponse
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Prova de que o composition root real (`defaultDriverFamilyRegistry()`, em `:app`) tem as
 * factories esperadas registradas e resolve os profiles maduros do catálogo sem lançar
 * `UnknownDriverFamilyException`.
 *
 * Herda a parte de wiring do agregador do antigo `DriverFamilyRegistryIntegrationTest` (`:core`):
 * como as classes concretas de cada Driver Family são `internal` aos seus módulos `:drivers:*`,
 * este teste em `:app` verifica só que `resolve` produz uma instância (não nula, sem exceção) —
 * as provas de leitura ponta a ponta com cast concreto vivem no teste de cada driver.
 */
class DefaultDriverFamilyRegistryTest {

    private object NoopTransport : HttpTransport {
        override fun get(url: String, extraHeaders: Map<String, String>) =
            HttpTransportResponse(404, "", emptyMap(), emptyMap())

        override fun post(
            url: String,
            body: String,
            cookies: Map<String, String>,
            extraHeaders: Map<String, String>,
        ) = HttpTransportResponse(200, "[error]0", emptyMap(), emptyMap())
    }

    private val catalog = DefaultDriverRegistry(embeddedManifestLoader = ::loadEmbeddedCatalogResource)

    @Test
    fun `default registry resolves tplink-legacy-cgi factory for Archer C20`() {
        val profile = requireNotNull(catalog.findProfile(vendor = "TP-Link", model = "Archer C20"))
        val driverFamily = defaultDriverFamilyRegistry().resolve(profile, "192.168.0.1", NoopTransport)
        assertNotNull("agregador deveria ter a factory tplink-legacy-cgi-driver registrada", driverFamily)
    }

    @Test
    fun `default registry resolves nokia-ont-gpon factory for G-1425G-B`() {
        val profile = requireNotNull(catalog.findProfile(vendor = "Nokia", model = "G-1425G-B"))
        val driverFamily = defaultDriverFamilyRegistry().resolve(profile, "192.168.1.254", NoopTransport)
        assertNotNull("agregador deveria ter a factory nokia-ont-gpon-driver registrada", driverFamily)
    }
}
