package com.nethal.core.driver.family.tplink.stokluci

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Testes de [TpLinkStokLuciDosThresholdsParser] — puros, sem rede, cobrindo `admin/security_settings?form=dos_setting`. */
class TpLinkStokLuciDosThresholdsParserTest {

    private fun sampleBody(): String = """
        {
          "success": true,
          "data": {
            "icmp_low": 100,
            "icmp_middle": 200,
            "icmp_high": 300,
            "syn_low": 50,
            "syn_middle": 100,
            "syn_high": 150,
            "udp_low": 200,
            "udp_middle": 400,
            "udp_high": 600
          }
        }
    """.trimIndent()

    @Test
    fun `maps all three tiers for icmp, syn and udp`() {
        val thresholds = TpLinkStokLuciDosThresholdsParser.parse(sampleBody())

        assertEquals(100, thresholds.icmp.low)
        assertEquals(200, thresholds.icmp.middle)
        assertEquals(300, thresholds.icmp.high)
        assertEquals(50, thresholds.syn.low)
        assertEquals(100, thresholds.syn.middle)
        assertEquals(150, thresholds.syn.high)
        assertEquals(200, thresholds.udp.low)
        assertEquals(400, thresholds.udp.middle)
        assertEquals(600, thresholds.udp.high)
    }

    @Test
    fun `missing fields never throw and produce null thresholds`() {
        val thresholds = TpLinkStokLuciDosThresholdsParser.parse("""{"success":true,"data":{}}""")

        assertNull(thresholds.icmp.low)
        assertNull(thresholds.syn.middle)
        assertNull(thresholds.udp.high)
    }

    @Test
    fun `malformed JSON never throws`() {
        val thresholds = TpLinkStokLuciDosThresholdsParser.parse("not a json at all")

        assertNull(thresholds.icmp.low)
    }

    @Test
    fun `accepts flat body without the data envelope`() {
        val flatBody = """{"icmp_low": 10}"""

        val thresholds = TpLinkStokLuciDosThresholdsParser.parse(flatBody)

        assertEquals(10, thresholds.icmp.low)
    }
}
