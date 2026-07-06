package com.nethal.core.catalog

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DriverRegistryTest {

    @Test
    fun `loads embedded manifest with the two real profiles`() {
        val registry = DefaultDriverRegistry(embeddedManifestLoader = ::loadEmbeddedCatalogResource)

        assertEquals("2026.07.06", registry.manifestVersion())
        assertEquals("2026-07-06T00:00:00Z", registry.generatedAt())
        assertEquals(2, registry.profiles().size)
    }

    @Test
    fun `finds profile by vendor and model case-insensitively`() {
        val registry = DefaultDriverRegistry(embeddedManifestLoader = ::loadEmbeddedCatalogResource)

        val nokia = registry.findProfile("nokia", "g-1425g-a")
        val tplink = registry.findProfile("TP-Link", "Archer C6")

        assertEquals("nokia_g1425ga_v1", nokia?.profileId)
        assertEquals("tplink_archer_c6_v1", tplink?.profileId)
    }

    @Test
    fun `returns null when profile is not in catalog`() {
        val registry = DefaultDriverRegistry(embeddedManifestLoader = ::loadEmbeddedCatalogResource)

        assertNull(registry.findProfile("Huawei", "HG8245"))
    }

    @Test
    fun `both embedded profiles are in DRAFT stage`() {
        val registry = DefaultDriverRegistry(embeddedManifestLoader = ::loadEmbeddedCatalogResource)

        assertTrue(registry.profiles().all { it.stage == DriverStage.DRAFT })
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
        assertEquals(2, registry.profiles().size)
        assertEquals("2026.07.06", registry.manifestVersion())
    }

    @Test
    fun `sync with no-op remote source reports not attempted and keeps local manifest`() = runTest {
        val registry = DefaultDriverRegistry(embeddedManifestLoader = ::loadEmbeddedCatalogResource)

        val result = registry.sync()

        assertTrue(result is CatalogSyncResult.NotAttempted)
        assertEquals("2026.07.06", registry.manifestVersion())
    }
}
