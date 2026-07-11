package com.nethal.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nethal.core.designsystem.R
import com.nethal.core.designsystem.theme.LocalNetHalExtendedColors
import com.nethal.core.designsystem.theme.ThemeMode
import com.nethal.core.navigation.AdvancedToolDestination

/**
 * Tela "Configurações" (issue #85), layout dos protótipos `3c`/`3f`
 * (`docs/design/prototypes.dc.html`). Reaproveita `SettingsViewModel` (movido de `:app` para este
 * módulo, ADR 0002 Fase 2) para o toggle de saída do programa beta — decisão #66: o opt-in em si
 * fica no onboarding "Notificações" (#71), aqui só referencia o mesmo estado.
 *
 * Seção `EQUIPAMENTO` do protótipo **não entra nesta entrega**: exige uma sessão de equipamento
 * ativa cross-tab, que não existe nesta arquitetura (o `BottomNavHost`/#67 não carrega
 * `CapabilityEngine`/`NetworkTarget` nenhum para as abas) e capabilities de escrita que nenhum
 * driver declara hoje (`READ_ONLY_ALPHA` em todos). Implementar botões que não fazem nada, ou que
 * fingem uma sessão que não existe, é pior que não ter a seção — fica registrada como gap
 * conhecido (não peça de UI escondida); Rafael decide se abre issue de acompanhamento quando a
 * base existir.
 *
 * `FERRAMENTAS AVANÇADAS` (issue #136) reintroduzida: [availableTools] chega já filtrada por quem
 * monta o grafo (`SettingsGraph.settingsGraph`, consultando `navController.graph` — nenhuma
 * dependência direta deste módulo em `:feature:tools-*`, regra de dependência única da ADR 0002).
 * Uma entrada só existe na lista se a rota correspondente já estiver registrada de fato no
 * `NavHost` compartilhado — sem hardcode das 7 entradas do protótipo de uma vez, sem link morto: a
 * seção inteira desaparece (nunca um cabeçalho vazio) enquanto nenhuma ferramenta estiver
 * registrada.
 *
 * Itens sem dado real (Notificações/Idioma sem tela própria, Termos de uso e Licenças de código
 * aberto sem conteúdo) aparecem como linha não-interativa com rótulo "Em breve" — nunca um número
 * ou destino inventado. "Idioma: Português (Brasil)" é fato real do app hoje (só pt-BR), não dado
 * fabricado. "Aparência" passou a ser um seletor real (Claro/Escuro/Sistema, issue #132).
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    appVersionLabel: String,
    availableTools: List<AdvancedToolDestination> = emptyList(),
    onOpenPrivacy: () -> Unit,
    onOpenTool: (String) -> Unit = {},
) {
    val isBetaProgramActive by viewModel.isBetaProgramActive.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()
    var showThemeDialog by remember { mutableStateOf(false) }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 22.dp, vertical = 14.dp)
                .verticalScroll(rememberScrollState())
                .testTag("home_settings_screen"),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                text = "Configurações",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            )

            BetaProgramSection(
                isActive = isBetaProgramActive,
                onLeaveClick = viewModel::leaveBetaProgram,
            )

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SettingsSectionHeader("APLICATIVO")
                SettingsSectionCard {
                    SettingsRow(
                        title = "Notificações",
                        trailingText = "Em breve",
                        showChevron = false,
                    )
                    SettingsRow(
                        title = "Aparência",
                        trailingText = themeMode.label(),
                        onClick = { showThemeDialog = true },
                    )
                    SettingsRow(
                        title = "Idioma",
                        trailingText = "Português (Brasil)",
                        showChevron = false,
                        showDivider = false,
                    )
                }
            }

            if (availableTools.isNotEmpty()) {
                AdvancedToolsSection(tools = availableTools, onOpenTool = onOpenTool)
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SettingsSectionHeader("SOBRE")
                SettingsSectionCard {
                    SettingsRow(
                        title = "Política de privacidade",
                        onClick = onOpenPrivacy,
                    )
                    SettingsRow(
                        title = "Termos de uso",
                        trailingText = "Em breve",
                        showChevron = false,
                    )
                    SettingsRow(
                        title = "Licenças de código aberto",
                        trailingText = "Em breve",
                        showChevron = false,
                    )
                    SettingsRow(
                        title = "Versão do app",
                        trailingText = appVersionLabel,
                        showChevron = false,
                        showDivider = false,
                    )
                }
            }
        }

        if (showThemeDialog) {
            ThemeModeDialog(
                selected = themeMode,
                onSelect = {
                    viewModel.setThemeMode(it)
                    showThemeDialog = false
                },
                onDismiss = { showThemeDialog = false },
            )
        }
    }
}

/** Rótulo pt-BR de cada modo de tema para a linha "Aparência" e para o diálogo do seletor. */
private fun ThemeMode.label(): String = when (this) {
    ThemeMode.LIGHT -> "Claro"
    ThemeMode.DARK -> "Escuro"
    ThemeMode.SYSTEM -> "Sistema"
}

/**
 * Seletor de tema (issue #132): sub-diálogo com 3 opções de rádio. Escolhido em vez de
 * `SegmentedButton`/`DropdownMenu` por caber no padrão de linha-abre-detalhe já usado nesta tela e
 * por deixar espaço para a legenda de "Sistema" sem apertar o layout. A troca aplica na hora —
 * `onSelect` persiste e o composition root em `:app` recompõe o tema.
 */
@Composable
private fun ThemeModeDialog(
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("theme_mode_dialog"),
        title = { Text(text = "Aparência", fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.selectableGroup()) {
                ThemeMode.entries.forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = mode == selected,
                                role = Role.RadioButton,
                                onClick = { onSelect(mode) },
                            )
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = mode == selected, onClick = null)
                        Text(
                            text = mode.label(),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Fechar") }
        },
    )
}

/**
 * Seção "FERRAMENTAS AVANÇADAS" (issue #136, protótipos `3c`/`3f` linha ~709) — [tools] já chega
 * filtrada por rota registrada (ver [SettingsScreen]); esta função só desenha o que recebe, nunca
 * decide o que é "disponível".
 */
@Composable
private fun AdvancedToolsSection(tools: List<AdvancedToolDestination>, onOpenTool: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SettingsSectionHeader("FERRAMENTAS AVANÇADAS")
        SettingsSectionCard {
            tools.forEachIndexed { index, tool ->
                SettingsRow(
                    title = tool.label,
                    leadingIcon = {
                        Icon(
                            painter = painterResource(tool.iconRes()),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    showDivider = index != tools.lastIndex,
                    onClick = { onOpenTool(tool.route) },
                )
            }
        }
    }
}

private fun AdvancedToolDestination.iconRes(): Int = when (this) {
    AdvancedToolDestination.PING -> R.drawable.ic_tool_ping
    AdvancedToolDestination.PORT_CHECK -> R.drawable.ic_tool_port_check
    AdvancedToolDestination.SPEEDTEST -> R.drawable.ic_tool_speedtest
    AdvancedToolDestination.DNS_LOOKUP -> R.drawable.ic_tool_dns
    AdvancedToolDestination.TRACEROUTE -> R.drawable.ic_tool_traceroute
    AdvancedToolDestination.REBOOT_WAN -> R.drawable.ic_tool_reboot_wan
}

@Composable
private fun BetaProgramSection(isActive: Boolean, onLeaveClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SettingsSectionHeader("PROGRAMA BETA")
        SettingsSectionCard {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = if (isActive) {
                        "Você está participando do programa de testers beta."
                    } else {
                        "Você não está participando do programa de testers beta."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )

                if (isActive) {
                    Text(
                        text = "Sair interrompe o envio de novos relatórios. Relatórios já " +
                            "enviados são anônimos e não podem ser removidos individualmente.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    OutlinedButton(
                        onClick = onLeaveClick,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "Sair do programa beta",
                            color = LocalNetHalExtendedColors.current.error,
                        )
                    }
                }
            }
        }
    }
}
