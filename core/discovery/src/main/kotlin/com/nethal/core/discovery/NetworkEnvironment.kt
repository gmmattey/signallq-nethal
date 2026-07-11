package com.nethal.core.discovery

/**
 * Estado de rede do próprio aparelho, obtido pela plataforma (Android `ConnectivityManager`/
 * `LinkProperties` — ver /regras-android-nethal). O core não sabe ler isso sozinho; quem
 * implementa é o módulo app (`AndroidNetworkEnvironmentReader`).
 */
data class NetworkEnvironment(
    val localIp: String?,
    val gatewayIp: String?,
    val subnetPrefixLength: Int?,
    val dnsServers: List<String>,
    val isWifi: Boolean,
)

/**
 * Fonte de dados de plataforma para o Discovery Engine. Implementação real depende de Android
 * (`ConnectivityManager.getLinkProperties`); aqui fica só o contrato para o core permanecer
 * testável sem Android.
 */
interface NetworkEnvironmentReader {
    suspend fun read(): NetworkEnvironment?
}
