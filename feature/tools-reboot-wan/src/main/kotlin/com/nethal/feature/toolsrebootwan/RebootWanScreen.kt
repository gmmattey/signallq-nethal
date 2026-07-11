package com.nethal.feature.toolsrebootwan

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nethal.core.designsystem.theme.LocalNetHalExtendedColors

/**
 * Tela "Reiniciar interface WAN" (issues #95/#103, protótipo `4h`) — casca de conteúdo, exposta ao
 * composition root só via [rebootWanGraph]; esta função (`@Composable` avulsa) não é chamada
 * diretamente por fora do módulo. `DisposableEffect` fecha a sessão ao sair de composição, mesmo
 * padrão de `WifiNetworkScreen`.
 *
 * @param onCancelled chamado quando o usuário cancela ou sai da tela sem confirmar — nunca chamado
 * depois de uma confirmação real. Quem monta o `NavHost` decide o que fazer (tipicamente voltar).
 */
@Composable
fun RebootWanScreen(viewModel: RebootWanViewModel, onCancelled: () -> Unit) {
    val uiState by viewModel.uiState.collectAsState()

    DisposableEffect(Unit) {
        onDispose { viewModel.closeSession() }
    }

    RebootWanContent(
        uiState = uiState,
        onConfirm = viewModel::confirmReboot,
        onCancel = {
            viewModel.cancel()
            onCancelled()
        },
    )
}

/**
 * Renderização pura a partir do estado — separada de [RebootWanScreen] para ser exercitada em
 * teste sem precisar de uma sessão/ViewModel real (mesma estratégia de `WifiNetworkContent`).
 *
 * O diálogo de confirmação ([RebootConfirmationDialog]) é exibido automaticamente sempre que
 * [uiState] é [RebootWanUiState.ConfirmationPending] — não existe nenhum outro gatilho de execução
 * nesta tela; [onConfirm] só é alcançável tocando "Reiniciar" dentro do diálogo.
 */
@Composable
fun RebootWanContent(
    uiState: RebootWanUiState,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Scaffold(modifier = Modifier.testTag("reboot_wan_screen")) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (uiState) {
                is RebootWanUiState.SessionUnavailable -> SessionUnavailableContent(uiState)
                is RebootWanUiState.ConfirmationPending -> {
                    // Fidelidade ao protótipo 4h: o cabeçalho "Ferramentas avançadas" a 35% de
                    // opacidade aparece só atrás do diálogo — nos demais estados desta tela (sem
                    // referência de design própria, inventados para cobrir progresso/sucesso/falha
                    // da ação real) o header some para não competir visualmente com o conteúdo.
                    AdvancedToolsHeaderPlaceholder()
                    RebootConfirmationDialog(onConfirm = onConfirm, onCancel = onCancel)
                }
                is RebootWanUiState.InProgress -> InProgressContent()
                is RebootWanUiState.Success -> SuccessContent()
                is RebootWanUiState.Failure -> FailureContent(uiState)
            }
        }
    }
}

/**
 * Cabeçalho "Ferramentas avançadas" a 35% de opacidade (fidelidade ao protótipo `4h`, que mostra a
 * lista de ferramentas por trás do scrim do diálogo) — só o cabeçalho, não a lista completa: o Hub
 * de Ferramentas em si é escopo de `:feature:tools-common` (issue #89, ainda não conectado), fora
 * do escopo desta tela (issue #95). Ver nota no PR sobre a linha "Reiniciar interface WAN" que
 * precisa entrar nesse hub/em Configurações quando ele existir.
 */
@Composable
private fun AdvancedToolsHeaderPlaceholder() {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 26.dp, vertical = 24.dp)
            .testTag("reboot_wan_advanced_tools_header"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(colors.surface.copy(alpha = 0.35f)),
        )
        Text(
            text = "Ferramentas avançadas",
            style = MaterialTheme.typography.titleLarge,
            color = colors.onBackground.copy(alpha = 0.35f),
        )
    }
}

@Composable
private fun SessionUnavailableContent(state: RebootWanUiState.SessionUnavailable) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .testTag("reboot_wan_session_unavailable"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = "Reiniciar interface WAN", style = MaterialTheme.typography.headlineSmall)
        Text(text = state.reason, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun InProgressContent() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .testTag("reboot_wan_in_progress"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text(
            text = "Reiniciando a interface WAN... a conexão fica indisponível por alguns instantes.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun SuccessContent() {
    val successColor = LocalNetHalExtendedColors.current.success
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .testTag("reboot_wan_success"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Interface WAN reiniciada",
            style = MaterialTheme.typography.headlineSmall,
            color = successColor,
        )
        Text(
            text = "O equipamento aceitou o comando de reinício. A conexão deve voltar em instantes.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun FailureContent(state: RebootWanUiState.Failure) {
    val errorColor = LocalNetHalExtendedColors.current.error
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .testTag("reboot_wan_failure"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Não foi possível reiniciar",
            style = MaterialTheme.typography.headlineSmall,
            color = errorColor,
        )
        Text(text = state.reason, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Diálogo de confirmação real (issue #95, design system seção 1n — `docs/design/design-system.dc.html`
 * `#1n`) — largura 280dp centralizada, raio 24dp, título 15/700, corpo 12.5/400, ações à direita.
 * "Reiniciar" é a ação de menor risco da lista de `/seguranca-nethal` ("ações permitidas com menor
 * risco, ainda assim com confirmação") — nunca apaga dado, então usa a cor de destaque
 * (`colorScheme.primary`), não a cor de erro (reservada, pelo design system, só para ações que
 * apagam dados). Fidelidade ao protótipo `4h`: título "Reiniciar interface WAN?", corpo avisando a
 * interrupção temporária, "Cancelar" à esquerda / "Reiniciar" à direita.
 *
 * Fechar tocando fora ou apertando voltar equivale a **cancelar** — nunca a confirmar; a única
 * forma de disparar [onConfirm] é tocar explicitamente em "Reiniciar" (`/seguranca-nethal`:
 * confirmação explícita, sem atalho).
 */
@Composable
fun RebootConfirmationDialog(
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(
            dismissOnClickOutside = true,
            dismissOnBackPress = true,
        ),
    ) {
        Column(
            modifier = modifier
                .widthIn(min = 280.dp, max = 340.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(colors.surfaceVariant)
                .padding(20.dp)
                .testTag("reboot_wan_confirmation_dialog"),
        ) {
            Text(
                text = "Reiniciar interface WAN?",
                color = colors.onBackground,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "A conexão com a internet fica indisponível por ~20 segundos. Dispositivos " +
                    "no Wi-Fi não são desconectados.",
                color = colors.onSurfaceVariant,
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
            )
            Spacer(modifier = Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Text(
                    text = "Cancelar",
                    color = colors.onSurfaceVariant,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clickable(onClickLabel = "Cancelar reinício da interface WAN", onClick = onCancel)
                        .padding(end = 20.dp)
                        .testTag("reboot_wan_dialog_cancel"),
                )
                Text(
                    text = "Reiniciar",
                    color = colors.primary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clickable(onClickLabel = "Confirmar reinício da interface WAN", onClick = onConfirm)
                        .testTag("reboot_wan_dialog_confirm"),
                )
            }
        }
    }
}
