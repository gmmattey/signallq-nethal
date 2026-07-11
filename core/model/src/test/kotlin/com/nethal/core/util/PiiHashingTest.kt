package com.nethal.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PiiHashingTest {

    @Test
    fun `sha256Hex never returns the raw input`() {
        val raw = "ALCLXXXXXXXX"

        val hashed = PiiHashing.sha256Hex(raw)

        assertNotEquals(raw, hashed)
    }

    @Test
    fun `sha256Hex is deterministic for the same input`() {
        assertEquals(PiiHashing.sha256Hex("ALCLXXXXXXXX"), PiiHashing.sha256Hex("ALCLXXXXXXXX"))
    }

    @Test
    fun `sha256Hex produces a 64 char lowercase hex digest`() {
        val hashed = PiiHashing.sha256Hex("ALCLXXXXXXXX")

        assertEquals(64, hashed.length)
        assertEquals(hashed, hashed.lowercase())
    }
}
