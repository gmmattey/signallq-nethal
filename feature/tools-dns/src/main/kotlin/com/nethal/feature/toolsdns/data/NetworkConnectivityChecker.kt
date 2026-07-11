package com.nethal.feature.toolsdns.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Checa se há conectividade de internet validada antes de disparar uma consulta DoH — evita tentativa às cegas sem rede. */
fun interface NetworkConnectivityChecker {
    fun hasInternet(): Boolean
}

/**
 * Implementação Android via `ConnectivityManager` (mesmo binding de plataforma usado por
 * `AndroidLanNetworkEnvironmentReader` em `:feature:devices`, não reaproveitado diretamente porque
 * um `:feature:*` não pode depender de outro `:feature:*`, ADR 0002 — duplicação pequena e
 * deliberada, só o binding, ~15 linhas).
 */
class AndroidNetworkConnectivityChecker(
    private val context: Context,
) : NetworkConnectivityChecker {

    override fun hasInternet(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return false
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
