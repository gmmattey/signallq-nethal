package com.nethal.core.telemetry

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private val UUID_V4_REGEX = Regex(
    "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
    RegexOption.IGNORE_CASE,
)

class TelemetryDeviceIdTest {

    @Test
    fun `generate produz UUID v4 valido`() {
        val id = TelemetryDeviceId.generate()

        assertTrue("device_id '$id' não parece um UUID v4", UUID_V4_REGEX.matches(id))
    }

    @Test
    fun `generate nunca repete o mesmo valor entre chamadas - nao derivado de hardware fixo`() {
        val ids = (1..50).map { TelemetryDeviceId.generate() }.toSet()

        assertNotEquals(
            "device_id deveria ser aleatório (UUID v4) — se fosse derivado de hardware, repetiria sempre",
            1,
            ids.size,
        )
        assertTrue("todas as 50 gerações deveriam ser únicas", ids.size == 50)
    }
}
