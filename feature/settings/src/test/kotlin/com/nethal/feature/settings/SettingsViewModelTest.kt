package com.nethal.feature.settings

import com.nethal.core.consent.ConsentScope
import com.nethal.core.designsystem.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `isBetaProgramActive reflects granted consent`() = runTest {
        val repository = FakeConsentRepository()
        repository.grant(ConsentScope.TELEMETRY_BETA, grantedAtEpochMillis = 0L)
        val viewModel = SettingsViewModel(repository, FakeThemeModeRepository())

        backgroundScope.launch { viewModel.isBetaProgramActive.collect {} }
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.isBetaProgramActive.value)
    }

    @Test
    fun `leaveBetaProgram revokes telemetry beta consent`() = runTest {
        val repository = FakeConsentRepository()
        repository.grant(ConsentScope.TELEMETRY_BETA, grantedAtEpochMillis = 0L)
        val viewModel = SettingsViewModel(repository, FakeThemeModeRepository())
        backgroundScope.launch { viewModel.isBetaProgramActive.collect {} }
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.leaveBetaProgram()
        dispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.isBetaProgramActive.value)
        assertFalse(repository.isGranted(ConsentScope.TELEMETRY_BETA))
    }

    @Test
    fun `isBetaProgramActive defaults to false without consent`() = runTest {
        val repository = FakeConsentRepository()
        val viewModel = SettingsViewModel(repository, FakeThemeModeRepository())

        backgroundScope.launch { viewModel.isBetaProgramActive.collect {} }
        dispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.isBetaProgramActive.value)
    }

    @Test
    fun `themeMode reflects persisted value`() = runTest {
        val themeRepository = FakeThemeModeRepository(initial = ThemeMode.LIGHT)
        val viewModel = SettingsViewModel(FakeConsentRepository(), themeRepository)

        backgroundScope.launch { viewModel.themeMode.collect {} }
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(ThemeMode.LIGHT, viewModel.themeMode.value)
    }

    @Test
    fun `setThemeMode persists the chosen mode`() = runTest {
        val themeRepository = FakeThemeModeRepository()
        val viewModel = SettingsViewModel(FakeConsentRepository(), themeRepository)
        backgroundScope.launch { viewModel.themeMode.collect {} }

        viewModel.setThemeMode(ThemeMode.DARK)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(ThemeMode.DARK, themeRepository.current())
        assertEquals(ThemeMode.DARK, viewModel.themeMode.value)
    }

    @Test
    fun `chosen theme survives a restart`() = runTest {
        // Mesmo repositório (persistência) entre dois ViewModels = simula reabrir o app.
        val themeRepository = FakeThemeModeRepository()
        val first = SettingsViewModel(FakeConsentRepository(), themeRepository)
        backgroundScope.launch { first.themeMode.collect {} }
        first.setThemeMode(ThemeMode.LIGHT)
        dispatcher.scheduler.advanceUntilIdle()

        val afterRestart = SettingsViewModel(FakeConsentRepository(), themeRepository)
        backgroundScope.launch { afterRestart.themeMode.collect {} }
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(ThemeMode.LIGHT, afterRestart.themeMode.value)
    }
}
