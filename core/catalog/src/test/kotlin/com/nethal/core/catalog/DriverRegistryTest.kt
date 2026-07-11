package com.nethal.core.catalog

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DriverRegistryTest {

    @Test
    fun `loads embedded manifest with the six real profiles`() {
        val registry = DefaultDriverRegistry(embeddedManifestLoader = ::loadEmbeddedCatalogResource)

        assertEquals("2026.07.29", registry.manifestVersion())
        assertEquals(6, registry.profiles().size)
    }

    // Nota: "TP-Link"/"Archer C6" tem dois profiles no catálogo (ver teste de ambiguidade abaixo).
    // Este teste cobre só o case-insensitive match, não a resolução de ambiguidade.
    @Test
    fun `finds profile by vendor and model case-insensitively`() {
        val registry = DefaultDriverRegistry(embeddedManifestLoader = ::loadEmbeddedCatalogResource)

        val nokia = registry.findProfile("nokia", "g-1425g-b")
        val tplink = registry.findProfile("TP-Link", "Archer C6")

        assertEquals("nokia_g1425gb_v1", nokia?.profileId)
        assertEquals("tplink_archer_c6_v1", tplink?.profileId)
    }

    @Test
    fun `returns null when profile is not in catalog`() {
        val registry = DefaultDriverRegistry(embeddedManifestLoader = ::loadEmbeddedCatalogResource)

        assertNull(registry.findProfile("Huawei", "HG8245"))
    }

    /**
     * Reproduz o gap documentado em `docs/architecture/hal-layering-model.md` §9.1: o catálogo
     * embarcado tem dois profiles reais para o mesmo vendor+modelo (TP-Link/Archer C6) —
     * `tplink_archer_c6_v1` (mecanismo antigo, REFUTED) e `tplink_archer_c6_stok_v1` (mecanismo
     * novo, DISCOVERY_ONLY). `findProfiles` deve devolver os dois, sem descartar nenhum.
     */
    @Test
    fun `findProfiles returns every profile that matches vendor and model, including ambiguous TP-Link Archer C6 case`() {
        val registry = DefaultDriverRegistry(embeddedManifestLoader = ::loadEmbeddedCatalogResource)

        val matches = registry.findProfiles("TP-Link", "Archer C6")

        assertEquals(2, matches.size)
        assertEquals(
            setOf("tplink_archer_c6_v1", "tplink_archer_c6_stok_v1"),
            matches.map { it.profileId }.toSet(),
        )
    }

    @Test
    fun `findProfile keeps returning a single result for the ambiguous TP-Link Archer C6 case`() {
        val registry = DefaultDriverRegistry(embeddedManifestLoader = ::loadEmbeddedCatalogResource)

        val tplink = registry.findProfile("TP-Link", "Archer C6")

        assertEquals("tplink_archer_c6_v1", tplink?.profileId)
    }

    @Test
    fun `sync failure never replaces the local manifest`() = runTest {
        val registry = DefaultDriverRegistry(
            embeddedManifestLoader = ::loadEmbeddedCatalogResource,
            remoteCatalogSource = object : RemoteCatalogSource {
                override suspend fun fetchLatestManifest(): CompatibilityManifest =
                    throw RuntimeException("timeout simulado")
            },
        )

        val result = registry.sync()

        assertTrue(result is CatalogSyncResult.Failed)
        assertEquals(6, registry.profiles().size)
        assertEquals("2026.07.29", registry.manifestVersion())
    }

    @Test
    fun `sync with no-op remote source reports not attempted and keeps local manifest`() = runTest {
        val registry = DefaultDriverRegistry(embeddedManifestLoader = ::loadEmbeddedCatalogResource)

        val result = registry.sync()

        assertTrue(result is CatalogSyncResult.NotAttempted)
        assertEquals("2026.07.29", registry.manifestVersion())
    }

    /**
     * Rede de segurança contra o bug corrigido em `loadEmbeddedCatalogResource`: o default da
     * função apontava para `catalog-2026.07.07.json` enquanto o diretório de recursos já tinha
     * manifestos mais novos, tornando profiles/drivers recém adicionados (ex.:
     * `tplink_archer_c20_v1`, `tplink_archer_c6_stok_v1`) inalcançáveis em runtime real — só os
     * testes que apontavam manualmente para o arquivo certo enxergavam o catálogo verdadeiro.
     *
     * Este teste lê os nomes de arquivo reais em `core/src/main/resources/catalog/` (mesma pasta
     * usada pelo classpath em runtime) e compara com o manifesto que
     * `loadEmbeddedCatalogResource()` carrega por padrão. Se alguém adicionar um
     * `catalog-YYYY.MM.DD.json` novo e esquecer de atualizar o default, o teste falha imediatamente
     * — mesma classe de drift silencioso que motivou este teste.
     */
    @Test
    fun `default embedded manifest is the newest catalog file in resources`() {
        val catalogDir = resolveCatalogResourcesDir()
        val newestFileName = catalogDir
            .listFiles { file -> file.isFile && file.name.matches(Regex("""catalog-\d{4}\.\d{2}\.\d{2}\.json""")) }
            ?.map { it.name }
            ?.maxOrNull()
            ?: error("Nenhum manifesto encontrado em $catalogDir")

        val loadedManifest = loadEmbeddedCatalogResource()
        val json = Json { ignoreUnknownKeys = true }
        val manifest = json.decodeFromString(CompatibilityManifest.serializer(), loadedManifest)

        val newestVersion = newestFileName.removePrefix("catalog-").removeSuffix(".json")
        assertEquals(
            "loadEmbeddedCatalogResource() está desatualizada: o manifesto mais recente em " +
                "resources é '$newestFileName', mas o default carregado é a versão " +
                "'${manifest.manifestVersion}'. Atualize o valor default de resourceName em " +
                "loadEmbeddedCatalogResource().",
            newestVersion,
            manifest.manifestVersion,
        )
    }

    // Localiza a pasta "catalog/" no classpath de teste, que no módulo core mapeia 1:1 para
    // core/src/main/resources/catalog em tempo de build (sem jar empacotado nos testes).
    private fun resolveCatalogResourcesDir(): File {
        val catalogDirUrl = object {}.javaClass.classLoader?.getResource("catalog")
            ?: error("Pasta de recursos 'catalog' não encontrada no classpath de teste")
        return File(catalogDirUrl.toURI())
    }
}
