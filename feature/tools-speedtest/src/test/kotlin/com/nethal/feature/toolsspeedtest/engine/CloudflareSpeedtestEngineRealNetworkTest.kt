package com.nethal.feature.toolsspeedtest.engine

import com.nethal.core.model.SpeedtestMode
import com.nethal.core.model.SpeedtestRunState
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.InetAddress

/**
 * Teste contra o endpoint real da Cloudflare (issue #98, critério de aceite: "testado contra o
 * endpoint real, não só mockado") — sem mock de transporte HTTP, sobe uma chamada de verdade
 * contra `speed.cloudflare.com`. Pula (não falha o build) se o ambiente não tiver internet —
 * mesmo raciocínio de qualquer teste de integração de rede: falha de rede do ambiente de CI não é
 * o mesmo que bug no motor.
 *
 * Execução real registrada no PR de #90/#98 (rodado localmente com internet disponível, resultado
 * de download/upload/latência positivos contra `speed.cloudflare.com`).
 */
class CloudflareSpeedtestEngineRealNetworkTest {

    @Test
    fun `FAST mode against the real Cloudflare endpoint measures non-zero download, upload and latency`() = runBlocking {
        assumeTrue(
            "sem acesso à internet neste ambiente — pulando teste contra endpoint real",
            hasRealInternetAccess(),
        )

        val engine = CloudflareSpeedtestEngine()
        withTimeout(90_000L) { engine.run(SpeedtestMode.FAST) }

        val snapshot = engine.snapshotFlow.value
        assertEquals(
            "motor terminou em erro contra o endpoint real: ${snapshot.errorMessage}",
            SpeedtestRunState.DONE,
            snapshot.runState,
        )
        val result = requireNotNull(snapshot.result) { "snapshot DONE sem resultado" }

        assertTrue("download deveria ser > 0 Mbps contra o endpoint real, foi ${result.downloadMbps}", result.downloadMbps > 0.0)
        assertTrue("upload deveria ser > 0 Mbps contra o endpoint real, foi ${result.uploadMbps}", result.uploadMbps > 0.0)
        assertTrue("latência deveria ser > 0 ms contra o endpoint real, foi ${result.latencyMs}", result.latencyMs > 0.0)
    }

    private fun hasRealInternetAccess(): Boolean = try {
        InetAddress.getByName("speed.cloudflare.com") != null
    } catch (_: Exception) {
        false
    }
}
