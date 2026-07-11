package com.nethal.feature.onboarding.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

/** Cobre a issue #69 — tela `1b` nunca dispara permissão real, só educa e libera a navegação. */
class OnboardingLocationScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun exibeTituloECopyHonestaSobreLocalizacao() {
        composeTestRule.setContent {
            OnboardingLocationScreen(onContinue = {})
        }

        composeTestRule.onNodeWithTag(OnboardingLocationScreenTestTags.TITLE).assertExists()
        composeTestRule
            .onNodeWithText("não coleta nem usa sua localização geográfica", substring = true)
            .assertExists()
    }

    @Test
    fun botaoPrimarioNaoPrometeAcaoQueNaoExecuta() {
        composeTestRule.setContent {
            OnboardingLocationScreen(onContinue = {})
        }

        // CTA é "Entendi, continuar" (não "Permitir localização") — esta tela não chama
        // requestPermissions. "Agora não" não deve existir — não há nada a recusar aqui.
        composeTestRule.onNodeWithText("Entendi, continuar").assertExists()
        composeTestRule.onNodeWithText("Agora não").assertDoesNotExist()
    }

    @Test
    fun continuarAcionaCallbackDeNavegacao() {
        var continued = false

        composeTestRule.setContent {
            OnboardingLocationScreen(onContinue = { continued = true })
        }

        composeTestRule.onNodeWithTag(OnboardingLocationScreenTestTags.CONTINUE_BUTTON).performClick()

        assert(continued)
    }
}
