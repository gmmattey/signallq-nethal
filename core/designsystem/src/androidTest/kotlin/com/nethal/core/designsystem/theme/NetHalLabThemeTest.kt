package com.nethal.core.designsystem.theme

import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test

/**
 * Cobre a resolução de tema de `NetHalLabTheme` (issue #132): LIGHT/DARK forçam o esquema certo e
 * SYSTEM segue `isSystemInDarkTheme()`. Prova, num teste de Compose, que a mesma tela renderiza
 * cores diferentes entre os modos — pré-requisito para o toggle funcionar de verdade.
 */
class NetHalLabThemeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun lightResolvesLightScheme() {
        var background = Color.Unspecified
        var extendedSuccess = Color.Unspecified
        composeRule.setContent {
            NetHalLabTheme(themeMode = ThemeMode.LIGHT) {
                background = MaterialTheme.colorScheme.background
                extendedSuccess = LocalNetHalExtendedColors.current.success
            }
        }

        composeRule.runOnIdle {
            assertEquals(BackgroundLight, background)
            assertEquals(SuccessChipLight, extendedSuccess)
        }
    }

    @Test
    fun darkResolvesDarkScheme() {
        var background = Color.Unspecified
        var extendedSuccess = Color.Unspecified
        composeRule.setContent {
            NetHalLabTheme(themeMode = ThemeMode.DARK) {
                background = MaterialTheme.colorScheme.background
                extendedSuccess = LocalNetHalExtendedColors.current.success
            }
        }

        composeRule.runOnIdle {
            assertEquals(BackgroundDark, background)
            assertEquals(SuccessDark, extendedSuccess)
        }
    }

    @Test
    fun lightAndDarkRenderDifferentColors() {
        var lightBackground = Color.Unspecified
        var darkBackground = Color.Unspecified
        composeRule.setContent {
            NetHalLabTheme(themeMode = ThemeMode.LIGHT) {
                lightBackground = MaterialTheme.colorScheme.background
            }
            NetHalLabTheme(themeMode = ThemeMode.DARK) {
                darkBackground = MaterialTheme.colorScheme.background
            }
        }

        composeRule.runOnIdle {
            assertNotEquals(lightBackground, darkBackground)
        }
    }

    @Test
    fun systemFollowsNightMode() {
        var nightBackground = Color.Unspecified
        var dayBackground = Color.Unspecified
        composeRule.setContent {
            CompositionLocalProvider(LocalConfiguration provides nightConfiguration()) {
                NetHalLabTheme(themeMode = ThemeMode.SYSTEM) {
                    nightBackground = MaterialTheme.colorScheme.background
                }
            }
            CompositionLocalProvider(LocalConfiguration provides dayConfiguration()) {
                NetHalLabTheme(themeMode = ThemeMode.SYSTEM) {
                    dayBackground = MaterialTheme.colorScheme.background
                }
            }
        }

        composeRule.runOnIdle {
            assertEquals(BackgroundDark, nightBackground)
            assertEquals(BackgroundLight, dayBackground)
        }
    }

    private fun nightConfiguration() = Configuration().apply {
        uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL
    }

    private fun dayConfiguration() = Configuration().apply {
        uiMode = Configuration.UI_MODE_NIGHT_NO or Configuration.UI_MODE_TYPE_NORMAL
    }
}
