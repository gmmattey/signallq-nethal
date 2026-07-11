package com.nethal.feature.toolstraceroute.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Cobre os dois formatos reais de saída de `ping -c 1 -t <ttl>` no Android (issue #102, critério "parsing de hop testado"). */
class PingLineParserTest {

    @Test
    fun `extracts address from intermediate hop TTL exceeded line`() {
        val output = "PING 8.8.8.8 (8.8.8.8): 56 data bytes\n" +
            "From 192.168.1.1: icmp_seq=1 Time to live exceeded\n"

        assertEquals("192.168.1.1", PingLineParser.extractAddress(output))
    }

    @Test
    fun `extracts address from successful echo reply line`() {
        val output = "PING 8.8.8.8 (8.8.8.8): 56 data bytes\n" +
            "64 bytes from 8.8.8.8: icmp_seq=1 ttl=53 time=18.3 ms\n" +
            "\n--- 8.8.8.8 ping statistics ---\n1 packets transmitted, 1 packets received, 0% packet loss\n"

        assertEquals("8.8.8.8", PingLineParser.extractAddress(output))
    }

    @Test
    fun `lowercase from is also matched`() {
        val output = "from 10.220.4.1: icmp_seq=2 Time to live exceeded\n"

        assertEquals("10.220.4.1", PingLineParser.extractAddress(output))
    }

    @Test
    fun `returns null for output without any recognizable address line (timeout)`() {
        val output = "PING 8.8.8.8 (8.8.8.8): 56 data bytes\n\n--- 8.8.8.8 ping statistics ---\n" +
            "1 packets transmitted, 0 packets received, 100% packet loss\n"

        assertNull(PingLineParser.extractAddress(output))
    }

    @Test
    fun `returns null for empty output`() {
        assertNull(PingLineParser.extractAddress(""))
    }
}
