package com.nethal.feature.toolsspeedtest.engine

import com.nethal.core.model.BufferbloatSeverity
import com.nethal.core.model.SpeedtestBottleneck
import com.nethal.core.model.SpeedtestQualityDiagnosis
import com.nethal.core.model.SpeedtestUsageVerdict

/**
 * Thresholds de classificação de qualidade — portado de
 * `io.signallq.app.feature.speedtest.SpeedtestQualityClassifier` (SignallQ, reaproveitamento
 * confirmado na issue #98), mesmos limiares, sem alteração de comportamento. Não mexer nos números
 * sem teste de regressão — são o critério visível pro usuário em "streaming/gamer/videochamada".
 */
object SpeedtestQualityClassifier {

    fun classifyBufferbloat(deltaMs: Double): BufferbloatSeverity = when {
        deltaMs < 5.0 -> BufferbloatSeverity.NONE
        deltaMs <= 30.0 -> BufferbloatSeverity.MILD
        deltaMs <= 100.0 -> BufferbloatSeverity.MODERATE
        else -> BufferbloatSeverity.SEVERE
    }

    fun classifyQuality(
        downloadMbps: Double,
        uploadMbps: Double,
        latencyMs: Double,
        jitterMs: Double,
        packetLossPercent: Double,
        bufferbloatDeltaMs: Double,
        bufferbloat: BufferbloatSeverity,
    ): SpeedtestQualityDiagnosis {
        val streaming = when {
            downloadMbps >= 25.0 && latencyMs <= 200.0 && jitterMs <= 50.0 && packetLossPercent <= 2.0 ->
                SpeedtestUsageVerdict.GOOD
            downloadMbps >= 15.0 && latencyMs <= 500.0 && jitterMs <= 100.0 && packetLossPercent <= 5.0 ->
                SpeedtestUsageVerdict.ACCEPTABLE
            else -> SpeedtestUsageVerdict.POOR
        }
        val gaming = when {
            downloadMbps >= 10.0 && uploadMbps >= 3.0 && latencyMs <= 50.0 && jitterMs <= 15.0 && packetLossPercent <= 0.5 ->
                SpeedtestUsageVerdict.GOOD
            downloadMbps >= 5.0 && uploadMbps >= 1.0 && latencyMs <= 100.0 && jitterMs <= 30.0 && packetLossPercent <= 1.0 ->
                SpeedtestUsageVerdict.ACCEPTABLE
            else -> SpeedtestUsageVerdict.POOR
        }
        val videoCall = when {
            downloadMbps >= 10.0 && uploadMbps >= 3.0 && latencyMs <= 80.0 && jitterMs <= 30.0 && packetLossPercent <= 1.0 ->
                SpeedtestUsageVerdict.GOOD
            downloadMbps >= 5.0 && uploadMbps >= 1.0 && latencyMs <= 150.0 && jitterMs <= 50.0 && packetLossPercent <= 3.0 ->
                SpeedtestUsageVerdict.ACCEPTABLE
            else -> SpeedtestUsageVerdict.POOR
        }
        val bottleneck = when {
            packetLossPercent > 2.0 -> SpeedtestBottleneck.PACKET_LOSS
            bufferbloatDeltaMs >= 100.0 || bufferbloat == BufferbloatSeverity.SEVERE -> SpeedtestBottleneck.BUFFERBLOAT
            latencyMs > 100.0 -> SpeedtestBottleneck.LATENCY
            uploadMbps < 5.0 -> SpeedtestBottleneck.UPLOAD
            else -> SpeedtestBottleneck.NONE
        }
        return SpeedtestQualityDiagnosis(
            streamingVerdict = streaming,
            gamingVerdict = gaming,
            videoCallVerdict = videoCall,
            primaryBottleneck = bottleneck,
        )
    }
}
