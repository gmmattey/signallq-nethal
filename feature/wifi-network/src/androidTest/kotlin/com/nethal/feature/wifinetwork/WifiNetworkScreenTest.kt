package com.nethal.feature.wifinetwork

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nethal.core.designsystem.theme.NetHalLabTheme
import com.nethal.core.model.CapabilityId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cobre os 3 estados da tela "Wi-Fi & Rede" (issue #84): disponível (rádios lidos com sucesso),
 * indisponível (sem sessão) e erro (falha/indisponibilidade ao ler `READ_WIFI_STATUS`). Exercita
 * [WifiNetworkContent] direto com estado construído à mão — não depende de uma sessão real
 * (`CapabilityEngine`)/driver, mesma estratégia dos demais testes de tela deste repositório.
 */
@RunWith(AndroidJUnit4::class)
class WifiNetworkScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun estadoDisponivelMostraCardsDeRadioEAcoesDaRede() {
        composeRule.setContent {
            NetHalLabTheme {
                WifiNetworkContent(uiState = loadedStateComRadios())
            }
        }

        composeRule.onNodeWithTag("network_radio_GHZ_5").assertIsDisplayed()
        composeRule.onNodeWithText("Rede-Casa-5G").assertIsDisplayed()
        composeRule.onNodeWithText("Alterar canal Wi-Fi").assertIsDisplayed()
        composeRule.onNodeWithText("Renomear rede (SSID)").assertIsDisplayed()
        composeRule.onNodeWithText("Alterar senha").assertIsDisplayed()
    }

    @Test
    fun tocarAcaoIndisponivelAbreExplicacaoComMotivo() {
        composeRule.setContent {
            NetHalLabTheme {
                WifiNetworkContent(uiState = loadedStateComRadios())
            }
        }

        composeRule.onNodeWithText("Alterar canal Wi-Fi").performClick()

        composeRule.onNodeWithText("Motivo de teste: driver não implementa escrita.").assertIsDisplayed()
        composeRule.onNodeWithText("Entendi").assertIsDisplayed()
    }

    @Test
    fun estadoIndisponivelMostraMotivoDeSessaoAusente() {
        composeRule.setContent {
            NetHalLabTheme {
                WifiNetworkContent(
                    uiState = WifiNetworkUiState.SessionUnavailable(
                        reason = "Nenhuma sessão autenticada chegou até esta tela.",
                    ),
                )
            }
        }

        composeRule.onNodeWithTag("network_session_unavailable").assertIsDisplayed()
        composeRule.onNodeWithText("Nenhuma sessão autenticada chegou até esta tela.").assertIsDisplayed()
    }

    @Test
    fun estadoDeErroMostraMotivoDaFalhaDeLeitura() {
        composeRule.setContent {
            NetHalLabTheme {
                WifiNetworkContent(
                    uiState = WifiNetworkUiState.Loaded(
                        radios = emptyList(),
                        radiosUnavailableReason = "Falha ao ler o status de Wi-Fi: tempo esgotado.",
                        actions = emptyList(),
                    ),
                )
            }
        }

        composeRule.onNodeWithTag("network_radios_unavailable").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Não foi possível ler o status de Wi-Fi: Falha ao ler o status de Wi-Fi: tempo esgotado.",
        ).assertIsDisplayed()
    }

    private fun loadedStateComRadios() = WifiNetworkUiState.Loaded(
        radios = listOf(
            WifiRadioUiModel(
                bandLabel = WifiRadioBandLabel.GHZ_5,
                ssid = "Rede-Casa-5G",
                channel = "36",
                bandwidth = "80 MHz",
                security = "WPA3",
                clientCount = "6",
                enabled = true,
            ),
        ),
        radiosUnavailableReason = null,
        actions = listOf(
            WifiNetworkActionUiModel(
                id = CapabilityId.SET_WIFI_CHANNEL,
                label = "Alterar canal Wi-Fi",
                available = false,
                reason = "Motivo de teste: driver não implementa escrita.",
            ),
            WifiNetworkActionUiModel(
                id = CapabilityId.SET_WIFI_SSID,
                label = "Renomear rede (SSID)",
                available = false,
                reason = "Motivo de teste: driver não implementa escrita.",
            ),
            WifiNetworkActionUiModel(
                id = CapabilityId.SET_WIFI_PASSWORD,
                label = "Alterar senha",
                available = false,
                reason = "Motivo de teste: driver não implementa escrita.",
            ),
        ),
    )
}
