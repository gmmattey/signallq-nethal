package com.nethal.feature.onboarding.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.nethal.core.catalog.DefaultDriverRegistry
import com.nethal.core.catalog.loadEmbeddedCatalogResource
import org.junit.Rule
import org.junit.Test

/**
 * Cobre a issue #73 usando o [DefaultDriverRegistry] real (não mockado), carregando o mesmo
 * manifesto embarcado (`catalog/catalog-2026.07.26.json`) que o app usa em produção — critério
 * de aceite explícito: a lista precisa vir do catálogo real, e o teste precisa provar isso.
 */
class OnboardingCompatibleDevicesScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val realDriverRegistry = DefaultDriverRegistry(embeddedManifestLoader = ::loadEmbeddedCatalogResource)

    @Test
    fun listaOsTresDriversEmProducaoNoGrupoLeituraDeDados() {
        composeTestRule.setContent {
            OnboardingCompatibleDevicesScreen(
                driverRegistry = realDriverRegistry,
                onBack = {},
                onRecommendModel = {},
            )
        }

        composeTestRule.onNodeWithText("Nokia G-1425G-B").assertExists()
        composeTestRule.onNodeWithText("TP-Link Archer C6").assertExists()
        composeTestRule.onNodeWithText("TP-Link Archer C20").assertExists()
    }

    @Test
    fun archerC6ApareceUmaUnicaVezMesmoTendoDoisProfilesNoCatalogo() {
        composeTestRule.setContent {
            OnboardingCompatibleDevicesScreen(
                driverRegistry = realDriverRegistry,
                onBack = {},
                onRecommendModel = {},
            )
        }

        composeTestRule.onAllNodesWithText("TP-Link Archer C6").assertCountEquals(1)
    }

    @Test
    fun exibeBannerDeEscopoParcialObrigatorio() {
        composeTestRule.setContent {
            OnboardingCompatibleDevicesScreen(
                driverRegistry = realDriverRegistry,
                onBack = {},
                onRecommendModel = {},
            )
        }

        composeTestRule
            .onNodeWithText("O NetHAL não funciona com qualquer roteador", substring = true)
            .assertExists()
    }

    @Test
    fun grupoEmPesquisaExisteENuncaUsaVocabularioHomologadoOuBeta() {
        composeTestRule.setContent {
            OnboardingCompatibleDevicesScreen(
                driverRegistry = realDriverRegistry,
                onBack = {},
                onRecommendModel = {},
            )
        }

        composeTestRule.onNodeWithText("EM PESQUISA — AINDA NÃO FUNCIONA").assertExists()
        composeTestRule.onNodeWithText("HOMOLOGADOS").assertDoesNotExist()
        composeTestRule.onNodeWithText("SUPORTADOS (BETA)").assertDoesNotExist()
    }

    @Test
    fun botaoVoltarEBotaoRecomendarAcionamCallbacks() {
        var backClicked = false
        var recommendClicked = false

        composeTestRule.setContent {
            OnboardingCompatibleDevicesScreen(
                driverRegistry = realDriverRegistry,
                onBack = { backClicked = true },
                onRecommendModel = { recommendClicked = true },
            )
        }

        composeTestRule.onNodeWithTag(OnboardingCompatibleDevicesScreenTestTags.BACK_BUTTON).performClick()
        composeTestRule.onNodeWithTag(OnboardingCompatibleDevicesScreenTestTags.RECOMMEND_BUTTON).performClick()

        assert(backClicked)
        assert(recommendClicked)
    }
}
