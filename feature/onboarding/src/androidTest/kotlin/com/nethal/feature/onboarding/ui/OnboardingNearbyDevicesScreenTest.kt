package com.nethal.feature.onboarding.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

/** Cobre a issue #70 — copy honesta sobre escopo LAN-only, sem nenhuma referência a Bluetooth. */
class OnboardingNearbyDevicesScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun exibeTituloERemoveReferenciaAoBluetoothDoPrototipoAntigo() {
        composeTestRule.setContent {
            OnboardingNearbyDevicesScreen(onContinue = {})
        }

        composeTestRule.onNodeWithTag(OnboardingNearbyDevicesScreenTestTags.TITLE).assertExists()
        composeTestRule.onNodeWithText("Sua rede Wi-Fi local").assertExists()
        composeTestRule.onNodeWithText("Bluetooth", substring = true).assertDoesNotExist()
    }

    @Test
    fun copyReforcaLimiteDeLan() {
        composeTestRule.setContent {
            OnboardingNearbyDevicesScreen(onContinue = {})
        }

        composeTestRule
            .onNodeWithText("nunca fora dela, nunca na internet", substring = true)
            .assertExists()
    }

    @Test
    fun continuarAcionaCallbackDeNavegacao() {
        var continued = false

        composeTestRule.setContent {
            OnboardingNearbyDevicesScreen(onContinue = { continued = true })
        }

        composeTestRule.onNodeWithTag(OnboardingNearbyDevicesScreenTestTags.CONTINUE_BUTTON).performClick()

        assert(continued)
    }
}
