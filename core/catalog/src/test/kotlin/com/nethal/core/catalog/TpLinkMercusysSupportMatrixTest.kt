package com.nethal.core.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TpLinkMercusysSupportMatrixTest {

    @Test
    fun `loads embedded TP-Link Mercusys matrix with every analyzed entry`() {
        val registry = DefaultTpLinkMercusysSupportMatrixRegistry()

        assertEquals("2026.07.07", registry.manifestVersion())
        assertEquals(163, registry.entries().size)
    }

    @Test
    fun `maps stok luci, GDPR CGI and XDR DS entries to the implemented driver families`() {
        val registry = DefaultTpLinkMercusysSupportMatrixRegistry()

        val c6u = registry.findEntries("TP-Link", "Archer C6U").single()
        val c50 = registry.findEntries("TP-Link", "Archer C50").single()
        val xdr = registry.findEntries("TP-Link", "TL-XDR3010").single()

        assertEquals("tplink-stok-luci-driver", c6u.driverFamilyId)
        assertEquals(SupportConfidenceLevel.CODE_REFERENCED, c6u.confidenceLevel)
        assertEquals("tplink-gdpr-cgi-driver", c50.driverFamilyId)
        assertEquals(SupportConfidenceLevel.CODE_REFERENCED, c50.confidenceLevel)
        assertEquals("tplink-xdr-ds-driver", xdr.driverFamilyId)
        assertEquals(SupportConfidenceLevel.CODE_REFERENCED, xdr.confidenceLevel)
    }

    @Test
    fun `marks out of core categories as unsupported and protocol outliers as experimental`() {
        val registry = DefaultTpLinkMercusysSupportMatrixRegistry()

        val eap = registry.findEntries("TP-Link", "EAP115").single()
        val sg108e = registry.findEntries("TP-Link", "TL-SG108E").single()
        val c5400x = registry.findEntries("TP-Link", "Archer C5400X").single()

        assertEquals(SupportConfidenceLevel.UNSUPPORTED, eap.confidenceLevel)
        assertEquals(SupportConfidenceLevel.UNSUPPORTED, sg108e.confidenceLevel)
        assertEquals(SupportConfidenceLevel.EXPERIMENTAL, c5400x.confidenceLevel)
        assertTrue(c5400x.driverFamilyId == null)
    }
}
