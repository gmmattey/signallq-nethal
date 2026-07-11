package com.nethal.feature.toolsspeedtest.engine

import com.nethal.core.model.BufferbloatSeverity
import com.nethal.core.model.SpeedtestBottleneck
import com.nethal.core.model.SpeedtestUsageVerdict
import org.junit.Assert.assertEquals
import org.junit.Test

/** Thresholds portados de `io.signallq.app.feature.speedtest.SpeedtestQualityClassifierTest` (SignallQ) — mesmos limiares, sem alteração de comportamento (ver KDoc de `SpeedtestQualityClassifier`). */
class SpeedtestQualityClassifierTest {

    @Test
    fun `bufferbloat thresholds are stable`() {
        assertEquals(BufferbloatSeverity.NONE, SpeedtestQualityClassifier.classifyBufferbloat(0.0))
        assertEquals(BufferbloatSeverity.NONE, SpeedtestQualityClassifier.classifyBufferbloat(4.9))
        assertEquals(BufferbloatSeverity.MILD, SpeedtestQualityClassifier.classifyBufferbloat(5.0))
        assertEquals(BufferbloatSeverity.MILD, SpeedtestQualityClassifier.classifyBufferbloat(30.0))
        assertEquals(BufferbloatSeverity.MODERATE, SpeedtestQualityClassifier.classifyBufferbloat(30.01))
        assertEquals(BufferbloatSeverity.MODERATE, SpeedtestQualityClassifier.classifyBufferbloat(100.0))
        assertEquals(BufferbloatSeverity.SEVERE, SpeedtestQualityClassifier.classifyBufferbloat(100.01))
    }

    @Test
    fun `quality classifier returns expected verdicts and primary bottleneck for a good connection`() {
        val diagnosis = SpeedtestQualityClassifier.classifyQuality(
            downloadMbps = 30.0,
            uploadMbps = 10.0,
            latencyMs = 40.0,
            jitterMs = 10.0,
            packetLossPercent = 0.1,
            bufferbloatDeltaMs = 0.0,
            bufferbloat = BufferbloatSeverity.NONE,
        )

        assertEquals(SpeedtestUsageVerdict.GOOD, diagnosis.streamingVerdict)
        assertEquals(SpeedtestUsageVerdict.GOOD, diagnosis.gamingVerdict)
        assertEquals(SpeedtestUsageVerdict.GOOD, diagnosis.videoCallVerdict)
        assertEquals(SpeedtestBottleneck.NONE, diagnosis.primaryBottleneck)
    }

    @Test
    fun `packet loss above 2 percent is flagged as the primary bottleneck even with high throughput`() {
        val diagnosis = SpeedtestQualityClassifier.classifyQuality(
            downloadMbps = 100.0,
            uploadMbps = 50.0,
            latencyMs = 20.0,
            jitterMs = 5.0,
            packetLossPercent = 3.0,
            bufferbloatDeltaMs = 0.0,
            bufferbloat = BufferbloatSeverity.NONE,
        )
        assertEquals(SpeedtestBottleneck.PACKET_LOSS, diagnosis.primaryBottleneck)
    }

    @Test
    fun `severe bufferbloat is flagged as the primary bottleneck over latency alone`() {
        val diagnosis = SpeedtestQualityClassifier.classifyQuality(
            downloadMbps = 100.0,
            uploadMbps = 50.0,
            latencyMs = 20.0,
            jitterMs = 5.0,
            packetLossPercent = 0.0,
            bufferbloatDeltaMs = 150.0,
            bufferbloat = BufferbloatSeverity.SEVERE,
        )
        assertEquals(SpeedtestBottleneck.BUFFERBLOAT, diagnosis.primaryBottleneck)
    }

    @Test
    fun `low upload alone is flagged as bottleneck when nothing else fails`() {
        val diagnosis = SpeedtestQualityClassifier.classifyQuality(
            downloadMbps = 100.0,
            uploadMbps = 2.0,
            latencyMs = 20.0,
            jitterMs = 5.0,
            packetLossPercent = 0.0,
            bufferbloatDeltaMs = 0.0,
            bufferbloat = BufferbloatSeverity.NONE,
        )
        assertEquals(SpeedtestBottleneck.UPLOAD, diagnosis.primaryBottleneck)
    }

    @Test
    fun `video call verdict is good at 10Mbps down, 3Mbps up, not only at higher tiers`() {
        val good = SpeedtestQualityClassifier.classifyQuality(
            downloadMbps = 12.0,
            uploadMbps = 4.0,
            latencyMs = 60.0,
            jitterMs = 20.0,
            packetLossPercent = 0.5,
            bufferbloatDeltaMs = 0.0,
            bufferbloat = BufferbloatSeverity.NONE,
        )
        assertEquals(SpeedtestUsageVerdict.GOOD, good.videoCallVerdict)

        val acceptable = SpeedtestQualityClassifier.classifyQuality(
            downloadMbps = 26.0,
            uploadMbps = 2.0,
            latencyMs = 60.0,
            jitterMs = 20.0,
            packetLossPercent = 0.5,
            bufferbloatDeltaMs = 0.0,
            bufferbloat = BufferbloatSeverity.NONE,
        )
        assertEquals(SpeedtestUsageVerdict.ACCEPTABLE, acceptable.videoCallVerdict)
    }

    @Test
    fun `gaming verdict demands low latency and jitter even with high throughput`() {
        val poorDueToLatency = SpeedtestQualityClassifier.classifyQuality(
            downloadMbps = 200.0,
            uploadMbps = 50.0,
            latencyMs = 150.0,
            jitterMs = 40.0,
            packetLossPercent = 0.0,
            bufferbloatDeltaMs = 0.0,
            bufferbloat = BufferbloatSeverity.NONE,
        )
        assertEquals(SpeedtestUsageVerdict.POOR, poorDueToLatency.gamingVerdict)
    }

    @Test
    fun `streaming verdict tolerates higher latency than gaming`() {
        val diagnosis = SpeedtestQualityClassifier.classifyQuality(
            downloadMbps = 26.0,
            uploadMbps = 1.0,
            latencyMs = 180.0,
            jitterMs = 40.0,
            packetLossPercent = 1.0,
            bufferbloatDeltaMs = 0.0,
            bufferbloat = BufferbloatSeverity.NONE,
        )
        assertEquals(SpeedtestUsageVerdict.GOOD, diagnosis.streamingVerdict)
        assertEquals(SpeedtestUsageVerdict.POOR, diagnosis.gamingVerdict)
    }
}
