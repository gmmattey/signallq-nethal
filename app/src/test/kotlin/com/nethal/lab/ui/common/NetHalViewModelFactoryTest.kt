package com.nethal.lab.ui.common

import androidx.lifecycle.ViewModel
import com.nethal.core.designsystem.theme.ThemeMode
import com.nethal.core.designsystem.theme.ThemeModeRepository
import com.nethal.feature.settings.SettingsViewModel
import com.nethal.lab.FakeConsentRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A factory ficou só com `SettingsViewModel` (issue #113): os ViewModels de onboarding
 * (`Welcome`/`BetaOptIn`) e as telas Capabilities/Report saíram na unificação do NavHost. O `when`
 * de `create()` resolve o único case conhecido e lança `IllegalArgumentException` para o resto.
 */
class NetHalViewModelFactoryTest {

    private val factory = NetHalViewModelFactory(
        consentRepository = FakeConsentRepository(),
        themeModeRepository = FakeThemeModeRepository(),
    )

    @Test
    fun `create resolves SettingsViewModel`() {
        val viewModel: ViewModel = factory.create(SettingsViewModel::class.java)
        assertTrue(viewModel is SettingsViewModel)
    }

    @Test
    fun `create throws for unknown ViewModel class`() {
        assertThrows(IllegalArgumentException::class.java) {
            factory.create(UnknownProbeViewModel::class.java)
        }
    }

    private class UnknownProbeViewModel : androidx.lifecycle.ViewModel()

    private class FakeThemeModeRepository : ThemeModeRepository {
        private val state = MutableStateFlow(ThemeMode.SYSTEM)
        override fun observeThemeMode(): Flow<ThemeMode> = state
        override suspend fun setThemeMode(mode: ThemeMode) {
            state.value = mode
        }
    }
}
