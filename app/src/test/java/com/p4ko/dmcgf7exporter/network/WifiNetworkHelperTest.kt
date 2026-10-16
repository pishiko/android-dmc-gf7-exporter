package com.p4ko.dmcgf7exporter.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiNetworkHelperTest {

    @Test
    fun shouldUsePrefixMatch_identifiesPrefixesAndExactSsids() {
        // Prefixes
        assertTrue(WifiNetworkHelper.shouldUsePrefixMatch(""))
        assertTrue(WifiNetworkHelper.shouldUsePrefixMatch("   "))
        assertTrue(WifiNetworkHelper.shouldUsePrefixMatch("MYCAM"))
        assertTrue(WifiNetworkHelper.shouldUsePrefixMatch("mycam"))
        assertTrue(WifiNetworkHelper.shouldUsePrefixMatch("GF7-"))
        assertTrue(WifiNetworkHelper.shouldUsePrefixMatch("GF7"))
        assertTrue(WifiNetworkHelper.shouldUsePrefixMatch("gf7-"))
        assertTrue(WifiNetworkHelper.shouldUsePrefixMatch("DMC-GF7"))

        // Exact SSIDs
        assertFalse(WifiNetworkHelper.shouldUsePrefixMatch("MYCAM-123456"))
        assertFalse(WifiNetworkHelper.shouldUsePrefixMatch("GF7-123456"))
        assertFalse(WifiNetworkHelper.shouldUsePrefixMatch("GF7-ABCDEF"))
        assertFalse(WifiNetworkHelper.shouldUsePrefixMatch("MyLumixCamera"))

        org.junit.Assert.assertEquals("MYCAM", WifiNetworkHelper.DEFAULT_SSID_PREFIX)
    }
}
