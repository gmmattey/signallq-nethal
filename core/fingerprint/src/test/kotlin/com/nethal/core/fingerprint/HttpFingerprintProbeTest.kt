package com.nethal.core.fingerprint

import org.junit.Assert.assertEquals
import org.junit.Test

class IsProbeAllowedTest {

    @Test
    fun `allows rfc1918 private ips`() {
        assertEquals(true, isProbeAllowed("192.168.1.1"))
        assertEquals(true, isProbeAllowed("10.0.0.5"))
        assertEquals(true, isProbeAllowed("172.16.5.5"))
    }

    @Test
    fun `rejects public ips - ssrf via manual ip entry`() {
        assertEquals(false, isProbeAllowed("8.8.8.8"))
        assertEquals(false, isProbeAllowed("201.17.45.90"))
    }

    @Test
    fun `rejects malformed input instead of throwing`() {
        assertEquals(false, isProbeAllowed("not-an-ip"))
    }
}
