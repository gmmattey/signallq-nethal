package com.nethal.core.consent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsentStateTest {

    @Test
    fun `empty state has no scope granted`() {
        val state = ConsentState.empty()

        ConsentScope.entries.forEach { scope ->
            assertFalse(state.isGranted(scope))
        }
    }

    @Test
    fun `scope not granted when record says false`() {
        val state = ConsentState(
            mapOf(
                ConsentScope.READ_STATUS to ConsentRecord(
                    scope = ConsentScope.READ_STATUS,
                    granted = false,
                    grantedAtEpochMillis = null,
                ),
            ),
        )

        assertFalse(state.isGranted(ConsentScope.READ_STATUS))
    }

    @Test
    fun `scope granted when record says true`() {
        val state = ConsentState(
            mapOf(
                ConsentScope.NETWORK_AUTHORIZATION to ConsentRecord(
                    scope = ConsentScope.NETWORK_AUTHORIZATION,
                    granted = true,
                    grantedAtEpochMillis = 1L,
                ),
            ),
        )

        assertTrue(state.isGranted(ConsentScope.NETWORK_AUTHORIZATION))
        assertFalse(state.isGranted(ConsentScope.WRITE_CONFIGURATION))
    }
}
