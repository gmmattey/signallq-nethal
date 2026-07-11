package com.nethal.feature.toolsrebootwan

import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nethal.core.designsystem.theme.NetHalLabTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cobre os critérios visuais das issues #95/#103: o diálogo de confirmação aparece sempre que o
 * estado é [RebootWanUiState.ConfirmationPending] (nunca é possível pular direto para execução),
 * "Cancelar" nunca dispara [onConfirm], e "Reiniciar" dispara [onConfirm] exatamente uma vez.
 * Exercita [RebootWanContent] direto com estado construído à mão — mesma estratégia de
 * `WifiNetworkScreenTest`.
 */
@RunWith(AndroidJUnit4::class)
class RebootWanScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun confirmationPendingSempreMostraODialogoDeConfirmacao() {
        composeRule.setContent {
            NetHalLabTheme {
                RebootWanContent(uiState = RebootWanUiState.ConfirmationPending, onConfirm = {}, onCancel = {})
            }
        }

        composeRule.onNodeWithTag("reboot_wan_confirmation_dialog").assertIsDisplayed()
        composeRule.onNodeWithText("Reiniciar interface WAN?").assertIsDisplayed()
        composeRule.onNodeWithText(
            "A conexão com a internet fica indisponível por ~20 segundos. Dispositivos no Wi-Fi " +
                "não são desconectados.",
        ).assertIsDisplayed()
    }

    @Test
    fun tocarCancelarChamaOnCancelENuncaOnConfirm() {
        var confirmCalled = false
        var cancelCalled = false
        composeRule.setContent {
            NetHalLabTheme {
                RebootWanContent(
                    uiState = RebootWanUiState.ConfirmationPending,
                    onConfirm = { confirmCalled = true },
                    onCancel = { cancelCalled = true },
                )
            }
        }

        composeRule.onNodeWithTag("reboot_wan_dialog_cancel").performClick()

        assertTrue(cancelCalled)
        assertFalse(confirmCalled)
    }

    @Test
    fun tocarReiniciarChamaOnConfirmENuncaOnCancel() {
        var confirmCalled = false
        var cancelCalled = false
        composeRule.setContent {
            NetHalLabTheme {
                RebootWanContent(
                    uiState = RebootWanUiState.ConfirmationPending,
                    onConfirm = { confirmCalled = true },
                    onCancel = { cancelCalled = true },
                )
            }
        }

        composeRule.onNodeWithTag("reboot_wan_dialog_confirm").performClick()

        assertTrue(confirmCalled)
        assertFalse(cancelCalled)
    }

    @Test
    fun estadoSemSessaoMostraMotivoENuncaMostraODialogo() {
        composeRule.setContent {
            NetHalLabTheme {
                RebootWanContent(
                    uiState = RebootWanUiState.SessionUnavailable(
                        reason = "Nenhuma sessão autenticada chegou até esta tela.",
                    ),
                    onConfirm = {},
                    onCancel = {},
                )
            }
        }

        composeRule.onNodeWithTag("reboot_wan_session_unavailable").assertIsDisplayed()
        composeRule.onNodeWithText("Nenhuma sessão autenticada chegou até esta tela.").assertIsDisplayed()
        composeRule.onNodeWithTag("reboot_wan_confirmation_dialog").assertDoesNotExist()
    }

    @Test
    fun estadoEmAndamentoMostraProgresso() {
        composeRule.setContent {
            NetHalLabTheme {
                RebootWanContent(uiState = RebootWanUiState.InProgress, onConfirm = {}, onCancel = {})
            }
        }

        composeRule.onNodeWithTag("reboot_wan_in_progress").assertIsDisplayed()
    }

    @Test
    fun estadoDeSucessoMostraConfirmacaoDeSucesso() {
        composeRule.setContent {
            NetHalLabTheme {
                RebootWanContent(uiState = RebootWanUiState.Success, onConfirm = {}, onCancel = {})
            }
        }

        composeRule.onNodeWithTag("reboot_wan_success").assertIsDisplayed()
        composeRule.onNodeWithText("Interface WAN reiniciada").assertIsDisplayed()
    }

    @Test
    fun estadoDeFalhaMostraMotivoReal() {
        composeRule.setContent {
            NetHalLabTheme {
                RebootWanContent(
                    uiState = RebootWanUiState.Failure(reason = "Motivo de teste: driver não implementa REBOOT_DEVICE."),
                    onConfirm = {},
                    onCancel = {},
                )
            }
        }

        composeRule.onNodeWithTag("reboot_wan_failure").assertIsDisplayed()
        composeRule.onNodeWithText("Motivo de teste: driver não implementa REBOOT_DEVICE.").assertIsDisplayed()
    }
}
