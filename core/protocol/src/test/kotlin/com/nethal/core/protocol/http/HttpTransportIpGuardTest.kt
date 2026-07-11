package com.nethal.core.protocol.http

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Cobertura direta do guard descrito na issue #55 — inclui os casos que o [DefaultHttpTransportTest]
 * já cobre implicitamente (127.0.0.1 do harness local), mas isolados aqui para não depender de subir
 * um `HttpServer` de verdade.
 */
class HttpTransportIpGuardTest {

    @Test
    fun `accepts rfc1918 private ip`() {
        assertEquals(true, HttpTransportIpGuard.isAllowedHost("10.0.0.1"))
        assertEquals(true, HttpTransportIpGuard.isAllowedHost("172.16.0.1"))
        assertEquals(true, HttpTransportIpGuard.isAllowedHost("172.31.255.255"))
        assertEquals(true, HttpTransportIpGuard.isAllowedHost("192.168.1.1"))
    }

    @Test
    fun `accepts loopback - harness de teste local usa 127-0-0-1 para simular equipamento real`() {
        assertEquals(true, HttpTransportIpGuard.isAllowedHost("127.0.0.1"))
        assertEquals(true, HttpTransportIpGuard.isAllowedHost("127.10.20.30"))
    }

    @Test
    fun `rejects public ip explicitly - escopo do produto e so LAN local`() {
        assertEquals(false, HttpTransportIpGuard.isAllowedHost("8.8.8.8"))
        assertEquals(false, HttpTransportIpGuard.isAllowedHost("201.17.45.90"))
        assertEquals(false, HttpTransportIpGuard.isAllowedHost("172.32.0.1"))
    }

    @Test
    fun `rejects hostname instead of literal ip - evita depender de resolucao dns`() {
        assertEquals(false, HttpTransportIpGuard.isAllowedHost("router.attacker.example.com"))
    }

    @Test
    fun `rejects malformed ip instead of throwing`() {
        assertEquals(false, HttpTransportIpGuard.isAllowedHost("999.1.1.1"))
        assertEquals(false, HttpTransportIpGuard.isAllowedHost("not-an-ip"))
        assertEquals(false, HttpTransportIpGuard.isAllowedHost(""))
    }

    @Test
    fun `rejects ipv6 literals - PrivateIpRanges so reconhece ipv4, falha segura`() {
        assertEquals(false, HttpTransportIpGuard.isAllowedHost("::1"))
        assertEquals(false, HttpTransportIpGuard.isAllowedHost("fe80::1"))
        assertEquals(false, HttpTransportIpGuard.isAllowedHost("2001:4860:4860::8888"))
    }
}
