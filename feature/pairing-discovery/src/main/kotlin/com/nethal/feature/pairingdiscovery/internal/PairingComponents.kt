package com.nethal.feature.pairingdiscovery.internal

import com.nethal.core.designsystem.theme.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * "Status chip" do design system (`/nethal-design`): outline colorido (nunca fill), 600/11,
 * padding 4x10, raio pill. Reaproveitado por 2b (confiança do fingerprint) e 2i (estágio do
 * driver por modelo).
 */
@Composable
internal fun StatusChip(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(color = color.copy(alpha = 0.12f), shape = RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = label,
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * Trilha (breadcrumb) usada em 2h/2i: pill preenchido accent para a(s) etapa(s) já escolhida(s),
 * texto neutro terciário para a etapa atual — igual ao protótipo (`prototypes.dc.html`, 2h/2i).
 */
@Composable
internal fun BreadcrumbTrail(steps: List<String>, currentLabel: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        steps.forEach { step ->
            Box(
                modifier = Modifier
                    .background(color = NetHalAccent.copy(alpha = 0.12f), shape = RoundedCornerShape(999.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(text = step, color = NetHalAccent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        if (currentLabel.isNotEmpty()) {
            Text(
                text = currentLabel,
                color = LocalNetHalExtendedColors.current.onSurfaceTertiary,
                fontSize = 11.5.sp,
            )
        }
    }
}

/**
 * Item de lista com "estado desabilitado" do design system: opacidade 38-45% no elemento
 * inteiro, permanece tocável e abre diálogo explicando o motivo — nunca escondido, nunca mudo.
 */
@Composable
internal fun SelectableListRow(
    title: String,
    subtitle: String? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    highlighted: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.4f)
            .background(
                if (highlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                else Color.Transparent,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leadingIcon?.invoke()
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = if (highlighted) NetHalAccent else MaterialTheme.colorScheme.onBackground,
                fontSize = 14.5.sp,
                fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Normal,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            }
        }
        trailingContent?.invoke()
    }
}

/**
 * Diálogo padrão do design system: título 15/700, corpo 11.5-13/400, no máx. 2 ações à direita.
 * Usado tanto para "sem driver ainda" (2g, tipo desabilitado) quanto para "continuar mesmo
 * assim" (2i, modelo em pesquisa).
 */
@Composable
internal fun PairingInfoDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    dismissLabel: String? = null,
    onDismiss: (() -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = { (onDismiss ?: onConfirm)() },
        title = { Text(text = title, fontWeight = FontWeight.Bold, fontSize = 15.sp) },
        text = {
            Text(text = body, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel) }
        },
        dismissButton = if (dismissLabel != null && onDismiss != null) {
            { TextButton(onClick = onDismiss) { Text(dismissLabel) } }
        } else {
            null
        },
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onBackground,
    )
}

@Composable
internal fun AvatarInitial(letter: String, highlighted: Boolean) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .background(
                color = if (highlighted) NetHalAccent.copy(alpha = 0.14f)
                else MaterialTheme.colorScheme.background,
                shape = RoundedCornerShape(9.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = letter,
            color = if (highlighted) NetHalAccent else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
        )
    }
}
