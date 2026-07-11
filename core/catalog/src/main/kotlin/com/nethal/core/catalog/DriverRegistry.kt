package com.nethal.core.catalog

import kotlinx.serialization.json.Json

/**
 * Driver Registry (spec §8.5). Carrega o manifesto de compatibilidade embarcado como recurso
 * do módulo `core` — esse arquivo É o fallback local, não uma feature futura (sync remoto
 * ainda não existe; a interface abaixo só reserva o espaço para isso, sem implementação real).
 */
interface DriverRegistry {
    fun manifestVersion(): String
    fun generatedAt(): String
    fun profiles(): List<CompatibilityProfile>

    /**
     * Devolve TODOS os profiles que casam com vendor+modelo (case-insensitive). O catálogo não
     * garante unicidade por vendor+modelo — dois profiles reais podem coexistir para o mesmo
     * vendor/modelo comercial quando representam plataformas distintas (ex.: `tplink_archer_c6_v1`
     * vs. `tplink_archer_c6_stok_v1`, ver `docs/architecture/hal-layering-model.md` §9.1). Qualquer
     * chamador que precise decidir entre profiles ambíguos (ex.: fluxo de identificação manual,
     * Tela 3 da spec §11) deve usar este método, não `findProfile`.
     */
    fun findProfiles(vendor: String, model: String): List<CompatibilityProfile>

    /**
     * Atalho para o caso comum de vendor+modelo com um único profile no catálogo. Quando há mais
     * de um profile para a mesma combinação, devolve o primeiro do manifesto (ordem arbitrária) —
     * isso é uma escolha silenciosa, não uma resolução de ambiguidade. Prefira `findProfiles`
     * sempre que o chamador precisar decidir entre profiles concorrentes.
     */
    fun findProfile(vendor: String, model: String): CompatibilityProfile?

    fun profilesForVendor(vendor: String): List<CompatibilityProfile>
}

/**
 * Resultado de uma tentativa de sincronização remota. Nenhuma implementação real ainda
 * (sem cliente HTTP de sync nesta entrega) — existe só para o Driver Registry já expor o
 * formato de retorno esperado quando o sync remoto for implementado, sem exigir mudança de
 * assinatura pública depois.
 */
sealed interface CatalogSyncResult {
    data object NotAttempted : CatalogSyncResult
    data class UpToDate(val manifestVersion: String) : CatalogSyncResult
    data class Updated(val fromVersion: String, val toVersion: String) : CatalogSyncResult

    /**
     * Falha de sync NUNCA bloqueia o uso do catálogo local — o Driver Registry continua
     * respondendo com o manifesto embarcado/carregado por último.
     */
    data class Failed(val reason: String) : CatalogSyncResult
}

/**
 * Fonte de um manifesto remoto mais novo. Sem implementação real nesta entrega (não há
 * backend de catálogo ainda) — a interface existe para o Driver Registry poder aceitar uma
 * implementação futura sem mudar o contrato público de `sync()`.
 */
interface RemoteCatalogSource {
    suspend fun fetchLatestManifest(): CompatibilityManifest?
}

class NoOpRemoteCatalogSource : RemoteCatalogSource {
    override suspend fun fetchLatestManifest(): CompatibilityManifest? = null
}

class DefaultDriverRegistry(
    private val embeddedManifestLoader: () -> String,
    private val remoteCatalogSource: RemoteCatalogSource = NoOpRemoteCatalogSource(),
) : DriverRegistry {

    private val json = Json { ignoreUnknownKeys = true }

    // O catálogo embarcado é sempre carregado de forma síncrona no construtor: é um recurso
    // local pequeno (dois profiles hoje) e o registry precisa estar pronto para uso sem
    // depender de rede, mesmo antes de qualquer tentativa de sync.
    private var currentManifest: CompatibilityManifest = json.decodeFromString(
        CompatibilityManifest.serializer(),
        embeddedManifestLoader(),
    )

    override fun manifestVersion(): String = currentManifest.manifestVersion

    override fun generatedAt(): String = currentManifest.generatedAt

    override fun profiles(): List<CompatibilityProfile> = currentManifest.profiles

    override fun findProfiles(vendor: String, model: String): List<CompatibilityProfile> =
        currentManifest.profiles.filter {
            it.vendor.equals(vendor, ignoreCase = true) && it.model.equals(model, ignoreCase = true)
        }

    override fun findProfile(vendor: String, model: String): CompatibilityProfile? =
        findProfiles(vendor, model).firstOrNull()

    override fun profilesForVendor(vendor: String): List<CompatibilityProfile> =
        currentManifest.profiles.filter { it.vendor.equals(vendor, ignoreCase = true) }

    /**
     * Tenta sincronizar contra uma versão remota mais nova. Sem implementação real de
     * transporte nesta entrega (`RemoteCatalogSource` default é no-op) — qualquer falha,
     * timeout ou ausência de fonte remota resulta em `Failed`/`NotAttempted` e nunca
     * substitui nem invalida o manifesto local já carregado.
     */
    suspend fun sync(): CatalogSyncResult {
        val remote = try {
            remoteCatalogSource.fetchLatestManifest()
        } catch (e: Exception) {
            return CatalogSyncResult.Failed(reason = e.message ?: "erro desconhecido no sync remoto")
        } ?: return CatalogSyncResult.NotAttempted

        val localVersion = currentManifest.manifestVersion
        return if (remote.manifestVersion == localVersion) {
            CatalogSyncResult.UpToDate(manifestVersion = localVersion)
        } else {
            currentManifest = remote
            CatalogSyncResult.Updated(fromVersion = localVersion, toVersion = remote.manifestVersion)
        }
    }
}

/**
 * Carrega o manifesto embarcado como recurso do classpath do módulo `core`. Função livre (não
 * presa a `DefaultDriverRegistry`) para o registry poder ser testado com um manifesto de teste
 * sem depender do classpath de recursos real.
 */
fun loadEmbeddedCatalogResource(resourceName: String = "catalog/catalog-2026.07.26.json"): String {
    val stream = object {}.javaClass.classLoader?.getResourceAsStream(resourceName)
        ?: error("Recurso de catálogo embarcado não encontrado: $resourceName")
    return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
}
