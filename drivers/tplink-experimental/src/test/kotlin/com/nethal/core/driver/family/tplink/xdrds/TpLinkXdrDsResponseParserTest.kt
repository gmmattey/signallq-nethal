package com.nethal.core.driver.family.tplink.xdrds

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TpLinkXdrDsResponseParserTest {

    @Test
    fun `parseErrorCode reads the top-level error_code field`() {
        assertEquals(0, TpLinkXdrDsResponseParser.parseErrorCode("""{"error_code":0}"""))
        assertEquals(1, TpLinkXdrDsResponseParser.parseErrorCode("""{"error_code":1,"msg":"nope"}"""))
    }

    @Test
    fun `parseErrorCode never throws on malformed or unrelated json`() {
        assertNull(TpLinkXdrDsResponseParser.parseErrorCode(""))
        assertNull(TpLinkXdrDsResponseParser.parseErrorCode("not json"))
        assertNull(TpLinkXdrDsResponseParser.parseErrorCode("""{"other":true}"""))
    }
}
