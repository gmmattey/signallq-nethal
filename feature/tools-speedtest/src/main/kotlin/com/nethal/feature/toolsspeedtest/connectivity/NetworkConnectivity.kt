package com.nethal.feature.toolsspeedtest.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Checagem real de conectividade — usada como guarda antes de iniciar o teste (a ViewModel nunca
 * dispara o motor sem rede) e como motivo honesto do estado [com.nethal.feature.toolsspeedtest.SpeedtestUiState.NoConnectivity].
 * Nunca um `true` otimista: sem [ConnectivityManager] disponível ou sem rede ativa com capability
 * de internet, devolve `false`.
 */
fun hasInternetConnectivity(context: Context): Boolean {
    val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    val network = manager.activeNetwork ?: return false
    val capabilities = manager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}
