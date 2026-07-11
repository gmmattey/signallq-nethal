package com.nethal.lab.ui.onboarding

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.nethal.feature.onboarding.OnboardingPermissionsState

/**
 * Lê o estado real das permissões relevantes ao onboarding (issue #72) no momento em que a tela `1e`
 * é composta — `:feature:onboarding` é agnóstico de Android e só recebe o [OnboardingPermissionsState]
 * já resolvido, então esta leitura (`ContextCompat.checkSelfPermission`) fica no composition root
 * (`:app`), como manda o KDoc de `onboardingGraph`.
 *
 * `POST_NOTIFICATIONS` só é permissão de runtime a partir do Android 13 (API 33) — abaixo disso a
 * notificação é concedida por padrão, então reportamos `true`.
 */
fun onboardingPermissionsState(context: Context): OnboardingPermissionsState {
    val locationGranted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

    val notificationsGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        true
    }

    return OnboardingPermissionsState(
        locationGranted = locationGranted,
        notificationsGranted = notificationsGranted,
    )
}
