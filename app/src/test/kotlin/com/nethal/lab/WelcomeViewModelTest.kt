package com.nethal.lab

import com.nethal.core.consent.ConsentScope
import com.nethal.lab.ui.onboarding.WelcomeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WelcomeViewModelTest {

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
    fun `start button disabled until network authorization confirmed`() {
        val viewModel = WelcomeViewModel(FakeConsentRepository())

        assertFalse(viewModel.uiState.value.canStartDiagnosis)

        viewModel.onNetworkAuthorizationChanged(true)

        assertTrue(viewModel.uiState.value.canStartDiagnosis)
    }

    @Test
    fun `confirmAndProceed does nothing when not confirmed`() = runTest {
        val repository = FakeConsentRepository()
        val viewModel = WelcomeViewModel(repository)
        var proceeded = false

        viewModel.confirmAndProceed { proceeded = true }
        dispatcher.scheduler.advanceUntilIdle()

        assertFalse(proceeded)
        assertFalse(repository.isGranted(ConsentScope.NETWORK_AUTHORIZATION))
    }

    @Test
    fun `confirmAndProceed grants network authorization and read status when confirmed`() = runTest {
        val repository = FakeConsentRepository()
        val viewModel = WelcomeViewModel(repository)
        var proceeded = false

        viewModel.onNetworkAuthorizationChanged(true)
        viewModel.confirmAndProceed { proceeded = true }
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(proceeded)
        assertTrue(repository.isGranted(ConsentScope.NETWORK_AUTHORIZATION))
        assertTrue(repository.isGranted(ConsentScope.READ_STATUS))
    }
}
