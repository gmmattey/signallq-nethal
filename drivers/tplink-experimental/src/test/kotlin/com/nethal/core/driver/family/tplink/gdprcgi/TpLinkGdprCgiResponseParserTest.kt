package com.nethal.core.driver.family.tplink.gdprcgi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TpLinkGdprCgiResponseParser.parseStackFields]/[TpLinkGdprCgiResponseParser.isStackSuccess] —
 * fixtures sintéticas derivadas do formato de dispatcher clássico `/cgi` documentado em
 * `TpLinkLegacyCgiResponseParser` (confirmado ao vivo para `tplink-legacy-cgi`, reaproveitado aqui
 * por analogia — ver KDoc de [TpLinkGdprCgiCapabilitySection]), nunca captura real contra o ramo
 * `/cgi_gdpr`.
 */
class TpLinkGdprCgiResponseParserTest {

    @Test
    fun `parseStackFields extracts key=value pairs ignoring bracketed markers`() {
        val body = "[1,1,0,0,0,0]0\r\nname=wlan0\r\nSSID=Casa-2.4G\r\n[error]0"

        val fields = TpLinkGdprCgiResponseParser.parseStackFields(body)

        assertEquals(mapOf("name" to "wlan0", "SSID" to "Casa-2.4G"), fields)
    }

    @Test
    fun `parseStackFields never throws on empty or malformed body`() {
        assertEquals(emptyMap<String, String>(), TpLinkGdprCgiResponseParser.parseStackFields(""))
        assertEquals(emptyMap<String, String>(), TpLinkGdprCgiResponseParser.parseStackFields("garbage without equals"))
        assertEquals(emptyMap<String, String>(), TpLinkGdprCgiResponseParser.parseStackFields("[error]0"))
    }

    @Test
    fun `isStackSuccess reads the trailing error marker`() {
        assertTrue(TpLinkGdprCgiResponseParser.isStackSuccess("[1,0,0,0,0,0]0\r\nfoo=bar\r\n[error]0"))
        assertFalse(TpLinkGdprCgiResponseParser.isStackSuccess("[1,0,0,0,0,0]0\r\nfoo=bar\r\n[error]9003"))
        assertFalse(TpLinkGdprCgiResponseParser.isStackSuccess("corpo sem marcador de erro"))
    }
}
