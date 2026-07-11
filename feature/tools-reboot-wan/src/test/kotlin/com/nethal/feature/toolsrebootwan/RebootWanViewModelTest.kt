package com.nethal.feature.toolsrebootwan

import com.nethal.core.capability.CapabilityEngine
import com.nethal.core.catalog.CapabilityActionResult
import com.nethal.core.catalog.CapabilityReadResult
import com.nethal.core.catalog.DriverFamily
import com.nethal.core.catalog.DriverFamilyAuthResult
import com.nethal.core.model.Capability
import com.nethal.core.model.CapabilityId
import com.nethal.core.model.CapabilityState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Cobre o critério de aceite central das issues #95/#103: o diálogo de confirmação (representado
 * aqui por [RebootWanUiState.ConfirmationPending]) aparece sempre antes de qualquer execução,
 * cancelar nunca executa nada, e a ação real só dispara por [RebootWanViewModel.confirmReboot] —
 * mesmo padrão de fixture (`FakeDriverFamily`/`CapabilityEngine` real, sem mockar o motor) de
 * `StatusViewModelTest`/`CapabilitiesViewModelTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RebootWanViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** [DriverFamily] fake que conta quantas vezes [executeAction] foi chamado — usado para provar que cancelar nunca chega a executar. */
    private class CountingActionDriverFamily(
        private val actionResult: CapabilityActionResult,
    ) : DriverFamily {
        var actionCallCount = 0
            private set

        override suspend fun readCapability(id: CapabilityId): CapabilityReadResult =
            CapabilityReadResult.Unavailable("não usado neste teste")

        override suspend fun authenticate(username: String, password: String): DriverFamilyAuthResult =
            DriverFamilyAuthResult.Success

        override suspend fun executeAction(id: CapabilityId): CapabilityActionResult {
            actionCallCount++
            return actionResult
        }
    }

    private fun successAction(): CapabilityActionResult = CapabilityActionResult.Success(
        capability = Capability(id = CapabilityId.REBOOT_DEVICE, state = CapabilityState.AVAILABLE, confidence = 1.0),
    )

    @Test
    fun `without a session the initial state is honestly SessionUnavailable`() {
        val viewModel = RebootWanViewModel(capabilityEngine = null)

        assertTrue(viewModel.uiState.value is RebootWanUiState.SessionUnavailable)
    }

    @Test
    fun `with a session the initial state is always ConfirmationPending - never InProgress or Success`() {
        val driverFamily = CountingActionDriverFamily(successAction())
        val engine = CapabilityEngine(driverFamily, username = "admin", password = "secret")
        val viewModel = RebootWanViewModel(capabilityEngine = engine)

        assertTrue(viewModel.uiState.value is RebootWanUiState.ConfirmationPending)
        assertEquals(0, driverFamily.actionCallCount)
    }

    @Test
    fun `cancel never calls executeAction and never changes state`() {
        val driverFamily = CountingActionDriverFamily(successAction())
        val engine = CapabilityEngine(driverFamily, username = "admin", password = "secret")
        val viewModel = RebootWanViewModel(capabilityEngine = engine)

        viewModel.cancel()

        assertEquals(0, driverFamily.actionCallCount)
        assertTrue(viewModel.uiState.value is RebootWanUiState.ConfirmationPending)
    }

    @Test
    fun `confirmReboot executes exactly once and transitions ConfirmationPending to InProgress then Success`() = runTest {
        val driverFamily = CountingActionDriverFamily(successAction())
        val engine = CapabilityEngine(driverFamily, username = "admin", password = "secret")
        val viewModel = RebootWanViewModel(capabilityEngine = engine)

        viewModel.confirmReboot()
        // Transição síncrona para InProgress acontece antes de qualquer suspensão - já visível
        // mesmo sem avançar o dispatcher de teste.
        assertTrue(viewModel.uiState.value is RebootWanUiState.InProgress)

        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value is RebootWanUiState.Success)
        assertEquals(1, driverFamily.actionCallCount)
    }

    @Test
    fun `confirmReboot failure surfaces the driver reason without inventing success`() = runTest {
        val driverFamily = CountingActionDriverFamily(CapabilityActionResult.Unavailable("driver não implementa REBOOT_DEVICE"))
        val engine = CapabilityEngine(driverFamily, username = "admin", password = "secret")
        val viewModel = RebootWanViewModel(capabilityEngine = engine)

        viewModel.confirmReboot()
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is RebootWanUiState.Failure)
        assertTrue((state as RebootWanUiState.Failure).reason.contains("driver não implementa REBOOT_DEVICE"))
    }

    @Test
    fun `double confirm before the first execution finishes only triggers a single executeAction call`() = runTest {
        val driverFamily = CountingActionDriverFamily(successAction())
        val engine = CapabilityEngine(driverFamily, username = "admin", password = "secret")
        val viewModel = RebootWanViewModel(capabilityEngine = engine)

        viewModel.confirmReboot() // -> InProgress (síncrono)
        viewModel.confirmReboot() // guarda: estado já não é ConfirmationPending, ignorado

        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, driverFamily.actionCallCount)
    }

    @Test
    fun `confirmReboot without a session is a safe no-op`() {
        val viewModel = RebootWanViewModel(capabilityEngine = null)

        viewModel.confirmReboot()

        assertTrue(viewModel.uiState.value is RebootWanUiState.SessionUnavailable)
    }
}
