package com.nethal.lab.ui.discovery

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.nethal.core.model.NetworkTarget

/**
 * Tela 2 — Descoberta (spec §11). Primeira vez que o app de fato solicita
 * `ACCESS_FINE_LOCATION` em runtime (SIG-312/313 já cobriram a explicação prévia na Tela 1).
 * Delega para Tela 2b/2c conforme o resultado do Discovery Engine, ou segue direto quando há
 * exatamente um candidato sem indício de duplo NAT.
 */
@Composable
fun DiscoveryScreen(
    viewModel: DiscoveryViewModel,
    onSingleCandidateReady: (NetworkTarget) -> Unit,
    onCandidateChosen: (NetworkTarget) -> Unit,
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
        is DiscoveryUiState.AwaitingLocationPermission -> ScanningContent()
        is DiscoveryUiState.Scanning -> ScanningContent()
        is DiscoveryUiState.SingleCandidateReady -> ScanningContent()
        is DiscoveryUiState.Failed -> DiscoveryFailedScreen(
            reason = state.reason,
            manualTargetError = manualTargetError,
            onRetry = { viewModel.retry() },
            onAddManualTarget = { ip -> viewModel.addManualTarget(ip) },
        )
        is DiscoveryUiState.MultipleCandidates -> MultipleCandidatesScreen(
            state = state,
            manualTargetError = manualTargetError,
            onCandidateChosen = onCandidateChosen,
            onAddManualTarget = { ip -> viewModel.addManualTarget(ip) },
        )
    }
}

@Composable
private fun ScanningContent() {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            CircularProgressIndicator(modifier = Modifier.padding(bottom = 8.dp))
            Text(
                text = "Procurando o gateway da sua rede...",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}
