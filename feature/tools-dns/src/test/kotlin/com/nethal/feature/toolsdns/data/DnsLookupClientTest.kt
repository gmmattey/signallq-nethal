package com.nethal.feature.toolsdns.data

import com.nethal.core.model.DnsLookupOutcome
import com.nethal.core.model.DnsRecordType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testes contra rede real (issue #101, critério de aceite "testado com hostname real, incluindo
 * caso de falha de resolução") — sem mock de sucesso: se a rede do executor não tiver saída para
 * `cloudflare-dns.com`, o teste de sucesso falha honestamente (não há stub/fake escondendo isso).
 */
class DnsLookupClientTest {

    private val client = CloudflareDohDnsLookupClient()

    @Test
    fun `resolve hostname real retorna registros A nao vazios`() = runTest {
        val outcome = client.lookup("example.com")

        val success = outcome as? DnsLookupOutcome.Success
            ?: error("esperava Success para example.com, obteve $outcome — checar conectividade do executor")

        val recordA = success.result.answers.first { it.type == DnsRecordType.A }
        assertTrue("esperava ao menos um endereço IPv4 para example.com", recordA.values.isNotEmpty())
        assertTrue(
            "endereço A deveria ter formato IPv4, obteve ${recordA.values}",
            recordA.values.all { it.matches(Regex("""\d{1,3}(\.\d{1,3}){3}""")) },
        )
        assertEquals("example.com", success.result.hostname)
        assertEquals("Cloudflare (1.1.1.1)", success.result.serverLabel)
    }

    @Test
    fun `hostname inexistente falha honestamente sem simular sucesso`() = runTest {
        val outcome = client.lookup("this-host-definitely-does-not-exist-nethal-test.invalid")

        val failure = outcome as? DnsLookupOutcome.Failure
            ?: error("esperava Failure (NXDOMAIN) para hostname inexistente, obteve $outcome")

        assertTrue(
            "mensagem de falha deveria mencionar o problema, obteve: ${failure.reason}",
            failure.reason.contains("NXDOMAIN", ignoreCase = true) ||
                failure.reason.contains("não encontrado", ignoreCase = true),
        )
    }

    @Test
    fun `hostname vazio falha sem tentar rede`() = runTest {
        val outcome = client.lookup("   ")

        assertTrue(outcome is DnsLookupOutcome.Failure)
    }
}
