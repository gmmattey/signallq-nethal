package com.nethal.lab.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.nethal.core.capability.CapabilityEngine
import com.nethal.core.designsystem.R
import com.nethal.core.navigation.BottomNavDestination
import com.nethal.feature.devices.devicesGraph
import com.nethal.feature.settings.settingsGraph
import com.nethal.feature.status.statusGraph
import com.nethal.feature.toolsdns.toolsDnsGraph
import com.nethal.feature.toolsping.toolsPingGraph
import com.nethal.feature.toolsrebootwan.rebootWanGraph
import com.nethal.feature.toolsspeedtest.speedtestGraph
import com.nethal.feature.toolstraceroute.tracerouteGraph
import com.nethal.feature.wifinetwork.wifiNetworkGraph
import com.nethal.lab.BuildConfig
import com.nethal.lab.ui.common.NetHalViewModelFactory

/**
 * Host de navegação inferior do NetHAL Lab ("modo uso diário", #67) — composition root montado em
 * `:app` (ADR 0002 Fase 2). Consolidação da issue #147: as quatro abas montam o `NavGraphBuilder`
 * real de cada módulo (`statusGraph`, `wifiNetworkGraph`, `devicesGraph`, `settingsGraph`) — nenhuma
 * delas usa mais composable placeholder. O mesmo `NavHost`/`NavHostController` também hospeda os 5
 * grafos de "Ferramentas avançadas" (Ping+Porta, Teste de velocidade, DNS Lookup, Traceroute,
 * Reiniciar WAN), irmãos de `settingsGraph` — é o que `SettingsGraph.kt` consulta via
 * `navController.graph.findNode(...)` para decidir quais entradas mostrar em Configurações.
 *
 * [capabilityEngine] é a sessão autenticada ao vivo do equipamento pareado nesta execução (`null`
 * quando não há sessão) e [pairedDeviceIp] é o IP desse equipamento — ambos vêm de
 * `NetHalNavHost` (handoff do pareamento) e são repassados tal qual para quem consome (Status, Rede,
 * Ping/Verificação de porta, Reiniciar WAN). Nenhuma aba abre ou fecha essa sessão — o dono do ciclo
 * de vida é `NetHalNavHost` (`DisposableEffect` na composable de `Routes.HOME`).
 *
 * Layout e comportamento (altura 80dp, padding 12/16dp, ícone 24dp, indicador pill 64×32dp raio
 * 16dp, rótulo 12sp 600/400) usam o default do `NavigationBar` Material 3 — bate com a spec sem
 * precisar de layout manual (levantamento prévio da issue #67).
 *
 * Nota para a Vera: o ícone da aba "Dispositivos" no protótipo (fluxo 3, telas 3i/3j) não bate com
 * nenhum SVG do set oficial (`docs/design/assets/icons/{dark,light}/`) — nem `router.svg` nem
 * `switch.svg`. Usamos `router.svg` (`ic_nav_devices`, em `:core:designsystem`) como placeholder
 * temporário; precisa de confirmação/ícone novo antes de qualquer entrega além da casca.
 */
@Composable
fun BottomNavHost(
    viewModelFactory: NetHalViewModelFactory,
    capabilityEngine: CapabilityEngine?,
    pairedDeviceIp: String?,
    navController: NavHostController = rememberNavController(),
) {
    Scaffold(
        bottomBar = {
            val backStackEntry by navController.currentBackStackEntryAsState()
            val currentRoute = backStackEntry?.destination?.route

            NavigationBar {
                bottomNavItems.forEach { item ->
                    NavigationBarItem(
                        selected = currentRoute == item.destination.route,
                        onClick = {
                            navController.navigate(item.destination.route) {
                                // Preserva estado das outras abas ao trocar (padrão M3 de bottom
                                // nav com múltiplos back stacks) — não recria a aba do zero a cada
                                // toque.
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            Icon(
                                painter = painterResource(item.iconRes),
                                contentDescription = item.label,
                            )
                        },
                        label = { Text(item.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        val context = LocalContext.current
        NavHost(
            navController = navController,
            startDestination = BottomNavDestination.STATUS.route,
            modifier = Modifier.padding(innerPadding),
            // "Fade through" do design system (seção Motion): fade out 90ms + fade in 110ms,
            // com leve scale 0.96→1 na entrada.
            enterTransition = {
                fadeIn(animationSpec = tween(durationMillis = 110, delayMillis = 90)) +
                    scaleIn(
                        initialScale = 0.96f,
                        animationSpec = tween(durationMillis = 110, delayMillis = 90),
                    )
            },
            exitTransition = { fadeOut(animationSpec = tween(durationMillis = 90)) },
        ) {
            statusGraph(capabilityEngine)
            wifiNetworkGraph { capabilityEngine }
            devicesGraph()
            settingsGraph(
                navController = navController,
                viewModelFactory = viewModelFactory,
                appVersionLabel = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            )

            // ── Ferramentas avançadas (issue #147) — mesmo NavHost/NavHostController de settingsGraph,
            // é o que torna `navController.graph.findNode(route)` verdadeiro para cada entrada em
            // AdvancedToolDestination e faz a seção aparecer em Configurações. ──────────────────────
            toolsPingGraph(navController = navController, defaultTargetHost = pairedDeviceIp)
            speedtestGraph(navController)
            toolsDnsGraph(context = context, onBack = { navController.popBackStack() })
            tracerouteGraph(navController)
            rebootWanGraph(navController) { capabilityEngine }
        }
    }
}

private data class BottomNavItem(
    val destination: BottomNavDestination,
    val label: String,
    val iconRes: Int,
)

private val bottomNavItems = listOf(
    BottomNavItem(BottomNavDestination.STATUS, "Status", R.drawable.ic_nav_status),
    BottomNavItem(BottomNavDestination.NETWORK, "Rede", R.drawable.ic_nav_network),
    BottomNavItem(BottomNavDestination.DEVICES, "Dispositivos", R.drawable.ic_nav_devices),
    BottomNavItem(BottomNavDestination.SETTINGS, "Configurações", R.drawable.ic_nav_settings),
)
