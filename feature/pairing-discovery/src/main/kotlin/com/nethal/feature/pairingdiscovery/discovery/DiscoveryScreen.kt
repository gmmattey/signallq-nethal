package com.nethal.feature.pairingdiscovery.discovery

import com.nethal.core.designsystem.theme.*
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.nethal.core.model.NetworkTarget
import com.nethal.feature.pairingdiscovery.internal.RouterGlyph

/**
 * Tela 2a — Buscando roteador (spec §11, protótipo `prototypes.dc.html` #2a). Primeira vez que o
 * app de fato solicita `ACCESS_FINE_LOCATION` em runtime. Delega para Tela 2b/2c conforme o
 * resultado do Discovery Engine, ou segue direto quando há exatamente um candidato sem indício
 * de duplo NAT — lógica preservada de `DiscoveryViewModel` (issue #74, só o visual muda).
 */
@Composable
fun DiscoveryScreen(
    viewModel: DiscoveryViewModel,
    onSingleCandidateReady: (NetworkTarget) -> Unit,
    onCandidateChosen: (NetworkTarget) -> Unit,
    onSelectManually: () -> Unit,
    onEnterIpManually: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val manualTargetError by viewModel.manualTargetError.collectAsState()
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted -> viewModel.onLocationPermissionResult(granted) }

    LaunchedEffect(Unit) {
        val alreadyGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

        if (alreadyGranted) {
            viewModel.onLocationPermissionResult(true)
        } else {
            permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    LaunchedEffect(uiState) {
        val state = uiState
        if (state is DiscoveryUiState.SingleCandidateReady) {
            onSingleCandidateReady(state.device)
        }
    }

    when (val state = uiState) {
        is DiscoveryUiState.AwaitingLocationPermission -> ScanningContent(onSelectManually = onSelectManually)
        is DiscoveryUiState.Scanning -> ScanningContent(onSelectManually = onSelectManually)
        is DiscoveryUiState.SingleCandidateReady -> ScanningContent(onSelectManually = onSelectManually)
        is DiscoveryUiState.Failed -> DiscoveryFailedScreen(
            reason = state.reason,
            onRetry = { viewModel.retry() },
            onSelectManually = onSelectManually,
            onEnterIpManually = onEnterIpManually,
        )
        is DiscoveryUiState.MultipleCandidates -> MultipleCandidatesScreen(
            state = state,
            manualTargetError = manualTargetError,
            onCandidateChosen = onCandidateChosen,
            onAddManualTarget = { ip -> viewModel.addManualTarget(ip) },
        )
    }
}

/**
 * Visual da tela 2a (protótipo): radar pulsante + ícone de roteador central, título/corpo, link
 * "Não encontrou? Selecionar manualmente" levando ao cluster de seleção manual (2g), que também
 * é o caminho documentado de entrada de IP manual quando a descoberta automática falhar por
 * completo (ver decisão registrada na spec de #80, seção "entrada manual por IP").
 */
// `internal` (não `private`) só para permitir teste de UI direto do estado 2a sem precisar
// simular o fluxo completo de permissão em runtime (`GrantPermissionRule` não cobre o
// `LaunchedEffect` de forma determinística em todos os emuladores) — ver `ScanningContentTest`.
@Composable
internal fun ScanningContent(onSelectManually: () -> Unit) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding)
                .padding(horizontal = 26.dp, vertical = 28.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                RadarIndicator()
                Text(
                    text = "Procurando seu roteador…",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    text = "Certifique-se de que o roteador está ligado e você está na mesma rede Wi-Fi.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            TextButton(onClick = onSelectManually, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Não encontrou? Selecionar manualmente",
                    color = NetHalAccent,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                )
            }
        }
    }
}

@Composable
private fun RadarIndicator() {
    val transition = rememberInfiniteTransition(label = "radar")
    val pulse by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "radar-pulse",
    )

    Box(modifier = Modifier.size(140.dp), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(140.dp)
                .border(width = 1.dp, color = MaterialTheme.colorScheme.outline, shape = CircleShape),
        )
        Box(
            modifier = Modifier
                .size(96.dp)
                .border(width = 1.dp, color = MaterialTheme.colorScheme.outline, shape = CircleShape),
        )
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(color = NetHalAccent.copy(alpha = 0.14f * pulse), shape = CircleShape)
                .border(width = 1.5.dp, color = NetHalAccent.copy(alpha = pulse), shape = CircleShape),
        )
        RouterGlyph(tint = NetHalAccent, size = 26.dp)
    }
}
