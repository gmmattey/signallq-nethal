package com.nethal.feature.toolsdns

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.nethal.feature.toolsdns.data.AndroidNetworkConnectivityChecker
import com.nethal.feature.toolsdns.data.CloudflareDohDnsLookupClient

/**
 * Rota do módulo `:feature:tools-dns` (issue #93/#101) — não faz parte da `NavigationBar` inferior
 * ([com.nethal.core.navigation.BottomNavDestination]), então define a própria rota (mesmo padrão de
 * `SettingsRoutes.PRIVACY` em `:feature:settings`: sub-rota interna a um módulo, string local, não
 * um contrato compartilhado).
 *
 * **Integração pendente** (fora de escopo desta issue, ver PR): hoje nenhum outro módulo navega
 * para [ROOT] — o protótipo `4e` entra por Configurações → Ferramentas avançadas
 * (`docs/design/prototypes.dc.html`), mas `:feature:settings`/`SettingsScreen.kt` não foi tocado
 * por instrução explícita desta tarefa. Quem monta o composition root (`:app`) precisa: (1) chamar
 * `toolsDnsGraph` dentro do `NavHost` raiz, e (2) adicionar a linha "DNS Lookup" à lista de
 * Ferramentas avançadas em Configurações navegando para [ROOT].
 */
object ToolsDnsRoutes {
    const val ROOT = "tools/dns"
}

fun NavGraphBuilder.toolsDnsGraph(
    context: Context,
    onBack: () -> Unit,
) {
    composable(ToolsDnsRoutes.ROOT) {
        val viewModel: DnsLookupViewModel = viewModel(
            factory = dnsLookupViewModelFactory(context.applicationContext),
        )
        DnsLookupScreen(viewModel = viewModel, onBack = onBack)
    }
}

private fun dnsLookupViewModelFactory(context: Context): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == DnsLookupViewModel::class.java) { "Unknown ViewModel class: $modelClass" }
            return DnsLookupViewModel(
                client = CloudflareDohDnsLookupClient(),
                connectivityChecker = AndroidNetworkConnectivityChecker(context),
            ) as T
        }
    }
