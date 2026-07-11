package com.nethal.core.catalog

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class SupportConfidenceLevel {
    LAB_VALIDATED,
    CODE_REFERENCED,
    FAMILY_INFERRED,
    EXPERIMENTAL,
    UNSUPPORTED,
}

@Serializable
data class TpLinkMercusysSupportMatrixManifest(
    val manifestVersion: String,
    val generatedAt: String,
    val sourceReportMarkdown: String,
    val sourceReportMatrix: String,
    val entries: List<TpLinkMercusysSupportMatrixEntry>,
)

@Serializable
data class TpLinkMercusysSupportMatrixEntry(
    val vendor: String,
    val model: String,
    val hardwareRevision: String,
    val protocolFamily: String,
    val driverFamilyId: String? = null,
    val authenticationSummary: String,
    val knownEndpointsSummary: String,
    val implementedCapabilitiesSummary: String,
    val confidenceLevel: SupportConfidenceLevel,
    val divergences: String,
    val evidenceReference: String,
)

class DefaultTpLinkMercusysSupportMatrixRegistry(
    embeddedManifestLoader: () -> String = ::loadEmbeddedTpLinkMercusysSupportMatrixResource,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val manifest = json.decodeFromString(
        TpLinkMercusysSupportMatrixManifest.serializer(),
        embeddedManifestLoader(),
    )

    fun manifestVersion(): String = manifest.manifestVersion

    fun entries(): List<TpLinkMercusysSupportMatrixEntry> = manifest.entries

    fun findEntries(vendor: String, model: String): List<TpLinkMercusysSupportMatrixEntry> =
        manifest.entries.filter {
            it.vendor.equals(vendor, ignoreCase = true) &&
                it.model.equals(model, ignoreCase = true)
        }

    fun entriesForDriverFamily(driverFamilyId: String): List<TpLinkMercusysSupportMatrixEntry> =
        manifest.entries.filter { it.driverFamilyId == driverFamilyId }

    fun countByConfidence(level: SupportConfidenceLevel): Int =
        manifest.entries.count { it.confidenceLevel == level }
}

fun loadEmbeddedTpLinkMercusysSupportMatrixResource(
    resourceName: String = "catalog/tplink-mercusys-support-matrix-2026.07.07.json",
): String {
    val stream = object {}.javaClass.classLoader?.getResourceAsStream(resourceName)
        ?: error("Recurso de matriz TP-Link/Mercusys não encontrado: $resourceName")
    return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
}
