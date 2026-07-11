package com.nethal.feature.onboarding.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import com.nethal.core.consent.ConsentRepository
import com.nethal.core.consent.ConsentScope
import kotlinx.coroutines.launch

/** Test tags estáveis para Compose UI Test — issue #71. */
object OnboardingNotificationsScreenTestTags {
    const val TITLE = "onboarding_notifications_title"
    const val ACTIVATE_BUTTON = "onboarding_notifications_activate"
    const val SKIP_BUTTON = "onboarding_notifications_skip"
    const val TELEMETRY_OVERLINE = "onboarding_notifications_telemetry_overline"
    const val TELEMETRY_CHECKBOX = "onboarding_notifications_telemetry_checkbox"
    const val RATIONALE = "onboarding_notifications_rationale"
}

/**
 * Tela `1d` — Onboarding: Notificações (issue #71).
 *
 * **Tela nova quanto ao design** — a spec da Vera não cobre `1a`/`1d`. Implementada direto do
 * protótipo (`docs/design/prototypes.dc.html` `1d`), com a fusão registrada na decisão
 * `docs/product/decisions/0001-telas-orfas-redesenho.md` (`BetaOptInScreen` fundida aqui, mesmo
 * momento de consentimento): abaixo do pedido de permissão de notificação, mantém integralmente o
 * texto de coleta de dados hoje em `app/.../ui/onboarding/BetaOptInScreen.kt` (spec §8.9) e grava
 * o opt-in em `ConsentScope.TELEMETRY_BETA` via [ConsentRepository] — mesma semântica de
 * `BetaOptInViewModel.optIn`/`optOut`, preservada aqui para não regredir consentimento
 * silenciosamente. O toggle de saída do programa beta continua em Configurações (issue #85),
 * referenciando o mesmo estado.
 *
 * Solicita `POST_NOTIFICATIONS` de verdade (API 33+, `Build.VERSION_CODES.TIRAMISU`) ao tocar
 * "Ativar notificações" — este é o momento contextual da permissão (`/regras-android-nethal`),
 * diferente de `1b`/`1c` que só educam. Abaixo de API 33 a permissão já é concedida na instalação,
 * sem prompt — segue direto. Rationale exibido se o usuário já negou antes
 * (`ActivityCompat.shouldShowRequestPermissionRationale`).
 */
@Composable
fun OnboardingNotificationsScreen(
    consentRepository: ConsentRepository,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var telemetryOptIn by remember { mutableStateOf(false) }
    var showRationale by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                context.findActivity()?.let {
                    ActivityCompat.shouldShowRequestPermissionRationale(
                        it,
                        Manifest.permission.POST_NOTIFICATIONS,
                    )
                } == true,
        )
    }

    fun proceed() {
        coroutineScope.launch {
            if (telemetryOptIn) {
                consentRepository.grant(ConsentScope.TELEMETRY_BETA, System.currentTimeMillis())
            } else {
                consentRepository.revoke(ConsentScope.TELEMETRY_BETA)
            }
            onContinue()
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) showRationale = true
        proceed()
    }

    fun activateNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            proceed()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(OnboardingColors.Background)
            .padding(PaddingValues(horizontal = 26.dp, vertical = 28.dp))
            .verticalScroll(rememberScrollState()),
    ) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            OnboardingProgressDots(activeIndex = 3)
        }

        Spacer(modifier = Modifier.height(20.dp))

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            OnboardingConcentricHero(ringCount = 2) {
                BellGlyph(modifier = Modifier.size(34.dp))
            }

            Spacer(modifier = Modifier.height(28.dp))

            BellGlyph(modifier = Modifier.size(26.dp))

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Ativar notificações",
                color = OnboardingColors.TextPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(OnboardingNotificationsScreenTestTags.TITLE),
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Receba alertas de queda de conexão, novos dispositivos e atualizações " +
                    "de firmware.",
                color = OnboardingColors.TextSecondary,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }

        Spacer(modifier = Modifier.height(28.dp))

        TelemetryBetaOptInSection(
            checked = telemetryOptIn,
            onCheckedChange = { telemetryOptIn = it },
        )

        if (showRationale) {
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = "Você negou notificações antes. Sem elas, o NetHAL não consegue avisar " +
                    "sobre queda de conexão ou novos dispositivos — você pode ativar depois em " +
                    "Configurações.",
                color = OnboardingColors.TextTertiary,
                fontSize = 11.5.sp,
                modifier = Modifier.testTag(OnboardingNotificationsScreenTestTags.RATIONALE),
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        OnboardingPrimaryButton(
            text = "Ativar notificações",
            onClick = ::activateNotifications,
            modifier = Modifier.testTag(OnboardingNotificationsScreenTestTags.ACTIVATE_BUTTON),
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Agora não",
            color = OnboardingColors.TextSecondary,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = ::proceed)
                .padding(vertical = 6.dp)
                .testTag(OnboardingNotificationsScreenTestTags.SKIP_BUTTON),
        )
    }
}

/**
 * Conteúdo fundido de `BetaOptInScreen.kt` (spec §8.9) — o que é coletado, preservado
 * integralmente, e o que nunca é coletado. Opt-in real (não pré-marcado): participar do programa
 * beta é escolha explícita, nunca padrão.
 */
@Composable
private fun TelemetryBetaOptInSection(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "PROGRAMA DE TESTES BETA (OPCIONAL)",
            color = OnboardingColors.TextTertiary,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.2.sp,
            modifier = Modifier.testTag(OnboardingNotificationsScreenTestTags.TELEMETRY_OVERLINE),
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Você pode ajudar a melhorar a compatibilidade do NetHAL enviando " +
                "relatórios anônimos sobre os equipamentos testados.",
            color = OnboardingColors.TextSecondary,
            fontSize = 12.5.sp,
        )

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = "O que é coletado, quando você participa:",
            color = OnboardingColors.TextPrimary,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "• Fabricante, modelo e firmware do equipamento\n" +
                "• Protocolo e capabilities detectadas\n" +
                "• Resultado da autenticação, sem senha\n" +
                "• Código de erro e tempo de resposta\n" +
                "• Hash anônimo da instalação\n" +
                "• País/região e operadora, apenas se você informar manualmente",
            color = OnboardingColors.TextSecondary,
            fontSize = 12.sp,
        )

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = "O NetHAL nunca coleta senha, SSID em claro, MAC completo, IP público " +
                "completo ou qualquer dado pessoal.",
            color = OnboardingColors.TextSecondary,
            fontSize = 12.sp,
        )

        Spacer(modifier = Modifier.height(14.dp))

        OnboardingCheckboxRow(
            label = "Quero participar do programa de testers beta e enviar relatórios anônimos.",
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(OnboardingNotificationsScreenTestTags.TELEMETRY_CHECKBOX),
        )
    }
}

/** Percorre a cadeia de [ContextWrapper] até achar a [Activity] hospedeira, ou `null` se nenhuma. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
