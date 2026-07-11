package com.nethal.feature.toolsspeedtest.engine

import com.nethal.core.model.BufferbloatSeverity
import com.nethal.core.model.SpeedtestLivePoint
import com.nethal.core.model.SpeedtestMode
import com.nethal.core.model.SpeedtestPhase
import com.nethal.core.model.SpeedtestResult
import com.nethal.core.model.SpeedtestRoundResult
import com.nethal.core.model.SpeedtestRunState
import com.nethal.core.model.SpeedtestSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Motor real de teste de velocidade contra `speed.cloudflare.com` (issue #98) — portado de
 * `io.signallq.app.feature.speedtest.ExecutorSpeedtestCloudflare` (SignallQ, reaproveitamento
 * confirmado, motor JVM puro).
 *
 * ## Simplificações deliberadas em relação à fonte do SignallQ (registradas no PR de #90/#98)
 *
 * O original tem ~1350 linhas cobrindo cenários que não se aplicam (ainda) ao NetHAL Lab — trazer
 * tudo seria complexidade sem uso real hoje:
 * - **Sem detecção de troca de rede em andamento** (`connectionTypeProvider`/"contaminado"): o
 *   SignallQ contamina o resultado se o Android trocar Wi-Fi↔dado móvel no meio do teste; o NetHAL
 *   Lab não tem hoje um provider de tipo de conexão passado para esta tela. Pode ser adicionado
 *   depois sem mudar o shape público (`SpeedtestResult` já não tem os campos `connectionType*`).
 * - **Sem sonda de DNS separada** (`executarDnsProbe`): não pedido nos critérios de aceite de #98
 *   (download/upload/latência/jitter).
 * - **Sem variante de upload adaptativo específica para rede móvel**
 *   (`executarFaseUploadAdaptativa`): usa a mesma [measureTransferPhase] multi-stream para as duas
 *   direções — a diferença do original era otimização de bateria/dados em 4G/5G, fora de escopo
 *   desta rodada.
 * - **Retry de rate-limit (429/403) simplificado**: o original tenta 2 configs diferentes com
 *   backoff progressivo em cascata; aqui é uma única tentativa extra com payload/streams reduzidos
 *   ([runTransferWithFallback]) — cobre o caso real (Cloudflare bloqueia payload grande demais sem
 *   contexto de browser) sem a cascata de 3 backoffs do original.
 *
 * O que **não** foi simplificado — é o que dá valor real à medição: múltiplos streams paralelos
 * com escalonamento adaptativo por ganho de janela, filtro de warmup + corte dos primeiros 30% das
 * amostras (evita que o slow-start do TCP puxe a média pra baixo), latência sob carga
 * (download/upload) para calcular bufferbloat, mediana+jitter com descarte da primeira amostra (RTT
 * de handshake TLS não é rede).
 */
class CloudflareSpeedtestEngine : SpeedtestEngine {

    private companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

        /** Intervalo entre rodadas do modo [SpeedtestMode.TRIPLE] — 5s (vs. 10s do motor original: cada rodada aqui já é curta, ver KDoc da classe). */
        private const val ROUND_GAP_MILLIS = 5_000L

        private const val MAX_LIVE_POINTS = 40
    }

    // Client HTTP/2 para upload e ping — múltiplos streams numa única conexão TCP.
    private val client: OkHttpClient = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", USER_AGENT)
                    .header("Cache-Control", "no-store")
                    .build(),
            )
        }
        .build()

    // Client HTTP/1.1 para download, com headers de contexto de browser — o endpoint `/__down` da
    // Cloudflare bloqueia (429/403) clientes HTTP/2 sem esse contexto de origem (evidência de campo
    // já registrada no motor original do SignallQ).
    private val downloadClient: OkHttpClient = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "*/*")
                    .header("Cache-Control", "no-store")
                    .header("Origin", "https://speed.cloudflare.com")
                    .header("Referer", "https://speed.cloudflare.com/")
                    .build(),
            )
        }
        .build()

    private val pingClient: OkHttpClient = client.newBuilder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(4, TimeUnit.SECONDS)
        .build()

    private val uploadPayloadCache = ConcurrentHashMap<Int, ByteArray>()
    private val isRunning = AtomicBoolean(false)
    private val cancelFlag = AtomicBoolean(false)

    private val mutableSnapshotFlow = MutableStateFlow(SpeedtestSnapshot(runState = SpeedtestRunState.IDLE))
    override val snapshotFlow: StateFlow<SpeedtestSnapshot> = mutableSnapshotFlow.asStateFlow()

    override fun cancel() {
        cancelFlag.set(true)
    }

    override suspend fun run(mode: SpeedtestMode) {
        if (!isRunning.compareAndSet(false, true)) return
        withContext(Dispatchers.IO) {
            try {
                cancelFlag.set(false)
                if (mode == SpeedtestMode.TRIPLE) runTripleMode() else runSingleMode(mode)
            } catch (t: Throwable) {
                update {
                    SpeedtestSnapshot(
                        runState = SpeedtestRunState.ERROR,
                        errorMessage = t.message ?: "Falha desconhecida ao testar a velocidade da conexão.",
                    )
                }
            } finally {
                isRunning.set(false)
            }
        }
    }

    private suspend fun runSingleMode(mode: SpeedtestMode) {
        val config = TransferConfig.forMode(mode)
        update { SpeedtestSnapshot(runState = SpeedtestRunState.RUNNING, phase = SpeedtestPhase.LATENCY, progressPercent = 2) }

        val latency = measureLatencyPhase(config) { done, total ->
            update { it.copy(progressPercent = 2 + (done.toDouble() / total * 18).toInt()) }
        }
        if (cancelledToIdle()) return

        update { it.copy(phase = SpeedtestPhase.DOWNLOAD, progressPercent = 20, liveMbps = 0.0, livePoints = emptyList()) }
        val pingsDownload = mutableListOf<Double>()
        val download = runTransferWithFallback(isDownload = true, config = config, pingsUnderLoad = pingsDownload) { progress, mbps ->
            update { it.withLiveSample(progressBase = 20, progressSpan = 40, progress = progress, mbps = mbps, isDownload = true) }
        }
        if (cancelledToIdle()) return

        update { it.copy(phase = SpeedtestPhase.UPLOAD, progressPercent = 60, liveMbps = 0.0, livePoints = emptyList()) }
        val pingsUpload = mutableListOf<Double>()
        val upload = runTransferWithFallback(isDownload = false, config = config, pingsUnderLoad = pingsUpload) { progress, mbps ->
            update { it.withLiveSample(progressBase = 60, progressSpan = 40, progress = progress, mbps = mbps, isDownload = false) }
        }
        if (cancelledToIdle()) return

        val result = buildResult(mode, latency, download, upload, pingsDownload, pingsUpload, rounds = emptyList())
        update { SpeedtestSnapshot(runState = SpeedtestRunState.DONE, phase = SpeedtestPhase.DONE, progressPercent = 100, result = result) }
    }

    private suspend fun runTripleMode() {
        val config = TransferConfig.forMode(SpeedtestMode.TRIPLE)
        val rounds = mutableListOf<SpeedtestRoundResult>()
        val totalRounds = 3

        for (round in 1..totalRounds) {
            if (cancelledToIdle()) return
            val offset = (round - 1) * 30
            update { SpeedtestSnapshot(runState = SpeedtestRunState.RUNNING, phase = SpeedtestPhase.LATENCY, progressPercent = offset + 2, currentRound = round) }

            val latency = measureLatencyPhase(config) { done, total ->
                update { it.copy(progressPercent = offset + (done.toDouble() / total * 6).toInt()) }
            }
            if (cancelledToIdle()) return

            update { it.copy(phase = SpeedtestPhase.DOWNLOAD, progressPercent = offset + 6, liveMbps = 0.0, livePoints = emptyList()) }
            val pingsDownload = mutableListOf<Double>()
            val download = runTransferWithFallback(isDownload = true, config = config, pingsUnderLoad = pingsDownload) { progress, mbps ->
                update { it.withLiveSample(progressBase = offset + 6, progressSpan = 15, progress = progress, mbps = mbps, isDownload = true) }
            }
            if (cancelledToIdle()) return

            update { it.copy(phase = SpeedtestPhase.UPLOAD, progressPercent = offset + 21, liveMbps = 0.0, livePoints = emptyList()) }
            val pingsUpload = mutableListOf<Double>()
            val upload = runTransferWithFallback(isDownload = false, config = config, pingsUnderLoad = pingsUpload) { progress, mbps ->
                update { it.withLiveSample(progressBase = offset + 21, progressSpan = 9, progress = progress, mbps = mbps, isDownload = false) }
            }

            rounds.add(SpeedtestRoundResult(downloadMbps = download.throughputMbps, uploadMbps = upload.throughputMbps, latencyMs = latency.latencyMs))

            if (round < totalRounds && !cancelFlag.get()) {
                update { it.copy(phase = SpeedtestPhase.IDLE, progressPercent = offset + 30, liveMbps = 0.0) }
                delay(ROUND_GAP_MILLIS)
            }
        }
        if (cancelledToIdle()) return

        val downloadMedian = median(rounds.map { it.downloadMbps })
        val uploadMedian = median(rounds.map { it.uploadMbps })
        val latencyMedian = median(rounds.map { it.latencyMs })
        // Rodadas individuais não medem jitter/perda/bufferbloat (só latência ponto a ponto) —
        // mesma decisão do motor original: resultado triplo usa valores neutros nesses campos.
        val diagnosis = SpeedtestQualityClassifier.classifyQuality(
            downloadMbps = downloadMedian,
            uploadMbps = uploadMedian,
            latencyMs = latencyMedian,
            jitterMs = 0.0,
            packetLossPercent = 0.0,
            bufferbloatDeltaMs = 0.0,
            bufferbloat = BufferbloatSeverity.NONE,
        )
        val result = SpeedtestResult(
            timestampEpochMs = System.currentTimeMillis(),
            mode = SpeedtestMode.TRIPLE,
            downloadMbps = downloadMedian,
            uploadMbps = uploadMedian,
            latencyMs = latencyMedian,
            jitterMs = 0.0,
            packetLossPercent = 0.0,
            bufferbloatMs = 0.0,
            bufferbloatSeverity = BufferbloatSeverity.NONE,
            peakDownloadMbps = rounds.maxOf { it.downloadMbps },
            peakUploadMbps = rounds.maxOf { it.uploadMbps },
            qualityDiagnosis = diagnosis,
            rounds = rounds,
        )
        update { SpeedtestSnapshot(runState = SpeedtestRunState.DONE, phase = SpeedtestPhase.DONE, progressPercent = 100, result = result) }
    }

    /** `true` (e já publicou `IDLE`) se o teste foi cancelado — chamador deve retornar imediatamente. */
    private fun cancelledToIdle(): Boolean {
        if (!cancelFlag.get()) return false
        update { SpeedtestSnapshot(runState = SpeedtestRunState.IDLE) }
        return true
    }

    private fun update(reducer: (SpeedtestSnapshot) -> SpeedtestSnapshot) {
        mutableSnapshotFlow.update(reducer)
    }

    private fun SpeedtestSnapshot.withLiveSample(
        progressBase: Int,
        progressSpan: Int,
        progress: Double,
        mbps: Double,
        isDownload: Boolean,
    ): SpeedtestSnapshot {
        val points = if (mbps > 0.0) {
            val point = SpeedtestLivePoint(
                atMillis = System.currentTimeMillis(),
                downloadMbps = mbps.takeIf { isDownload },
                uploadMbps = mbps.takeIf { !isDownload },
            )
            (livePoints + point).takeLast(MAX_LIVE_POINTS)
        } else {
            livePoints
        }
        return copy(
            progressPercent = progressBase + (progress * progressSpan).toInt(),
            liveMbps = if (mbps > 0.0) mbps else liveMbps,
            livePoints = points,
        )
    }

    /** Retry único com payload/streams reduzidos quando a Cloudflare bloqueia por rate-limit (429/403) — ver KDoc da classe. */
    private suspend fun runTransferWithFallback(
        isDownload: Boolean,
        config: TransferConfig,
        pingsUnderLoad: MutableList<Double>,
        onSample: (progress: Double, mbps: Double) -> Unit,
    ): ThroughputPhaseResult {
        return try {
            measureTransferPhase(isDownload, config, pingsUnderLoad, onSample)
        } catch (t: Throwable) {
            val message = t.message.orEmpty()
            val rateLimited = message.contains("HttpStatus:429") || message.contains("HttpStatus:403")
            if (!isDownload || !rateLimited) throw t
            val fallback = config.copy(downloadPayloadBytes = 1_000_000, initialStreams = 1, maxStreams = 2)
            measureTransferPhase(isDownload, fallback, pingsUnderLoad, onSample)
        }
    }

    private suspend fun measureLatencyPhase(
        config: TransferConfig,
        onProgress: (done: Int, total: Int) -> Unit,
    ): LatencyPhaseResult {
        val raw = mutableListOf<Double?>()
        repeat(config.pingCount) { index ->
            raw.add(if (cancelFlag.get()) null else measurePing())
            onProgress(index + 1, config.pingCount)
        }
        // A primeira amostra inclui o handshake TLS da primeira conexão — não representa RTT de rede
        // real, mesmo descarte do motor original.
        val withoutFirst = raw.drop(1)
        val timeouts = withoutFirst.count { it == null }
        val valid = withoutFirst.filterNotNull()
        val roughMedian = median(valid)
        val filtered = if (roughMedian > 0.0) valid.filter { it <= roughMedian * 3.0 } else valid
        val used = filtered.ifEmpty { valid }
        return LatencyPhaseResult(
            latencyMs = median(used),
            jitterMs = jitter(used),
            packetLossPercent = if (withoutFirst.isNotEmpty()) (timeouts.toDouble() / withoutFirst.size) * 100.0 else 0.0,
        )
    }

    private suspend fun measureTransferPhase(
        isDownload: Boolean,
        config: TransferConfig,
        pingsUnderLoad: MutableList<Double>,
        onSample: (progress: Double, mbps: Double) -> Unit,
    ): ThroughputPhaseResult = supervisorScope {
        val durationMs = if (isDownload) config.downloadDurationMs else config.uploadDurationMs
        val payloadBytes = if (isDownload) config.downloadPayloadBytes else config.uploadPayloadBytes

        val startNs = System.nanoTime()
        val stopNs = startNs + durationMs * 1_000_000L
        val stopFlag = AtomicBoolean(false)
        val bytesTick = AtomicLong(0)
        val bytesTotal = AtomicLong(0)
        val targetStreams = AtomicInteger(config.initialStreams)
        val payloadCurrent = AtomicInteger(payloadBytes)
        val lastError = AtomicReference<String?>(null)
        val samples = Collections.synchronizedList(mutableListOf<Sample>())
        val workers = mutableListOf<Job>()

        fun elapsedMs() = (System.nanoTime() - startNs) / 1_000_000L
        fun stillRunning() = !stopFlag.get() && System.nanoTime() < stopNs && !cancelFlag.get()

        repeat(config.maxStreams) { index ->
            workers += launch {
                if (index > 0) delay(index * 150L)
                while (stillRunning()) {
                    if (index >= targetStreams.get()) {
                        delay(100)
                        continue
                    }
                    try {
                        if (isDownload) {
                            executeDownloadRequest(payloadCurrent.get()) { chunk ->
                                bytesTick.addAndGet(chunk.toLong())
                                bytesTotal.addAndGet(chunk.toLong())
                            }
                        } else {
                            val sent = executeUploadRequest(payloadCurrent.get())
                            bytesTick.addAndGet(sent.toLong())
                            bytesTotal.addAndGet(sent.toLong())
                        }
                    } catch (t: Throwable) {
                        val message = t.message.orEmpty()
                        lastError.compareAndSet(null, message.ifBlank { t::class.java.simpleName })
                        val rateLimited = message.contains("HttpStatus:429") || message.contains("HttpStatus:403")
                        if (isDownload && rateLimited && payloadCurrent.get() > 1_000_000) {
                            payloadCurrent.set(1_000_000)
                        }
                        delay(300)
                    }
                }
            }
        }

        val pingJob = launch {
            while (stillRunning()) {
                val t0 = System.nanoTime()
                measurePing()?.let { pingsUnderLoad.add(it) }
                val spentMs = (System.nanoTime() - t0) / 1_000_000L
                delay(max(0L, 1_000L - spentMs))
            }
        }

        val sampler = launch {
            var lastScaleMs = 0L
            while (stillRunning()) {
                delay(1_000L)
                val bytes = bytesTick.getAndSet(0)
                val tMs = elapsedMs()
                val mbps = if (bytes > 0L) (bytes * 8.0) / 1_000_000.0 else 0.0
                if (mbps > 0.0) samples.add(Sample(tMs.toInt(), mbps))
                onSample(min(1.0, tMs.toDouble() / durationMs.toDouble()), mbps)

                val readyToScale = tMs - lastScaleMs >= 3_000L && targetStreams.get() < config.maxStreams
                if (readyToScale) {
                    lastScaleMs = tMs
                    if (windowGain(samples.toList(), tMs.toInt()) >= 0.10) {
                        targetStreams.set(min(config.maxStreams, targetStreams.get() + 1))
                    }
                }
            }
        }

        while (System.nanoTime() < stopNs && !cancelFlag.get()) delay(80)
        stopFlag.set(true)
        pingJob.join()
        sampler.join()
        workers.forEach { it.join() }

        val durationMeasuredMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
        val validSamples = samples.filter { it.tMs >= config.warmupMs && it.mbps > 0.0 }.sortedBy { it.tMs }
        // Descarta os primeiros ~30% das amostras válidas (slow-start do TCP puxa a média pra baixo
        // se contado como throughput real) — mesmo raciocínio do motor original (lá 35%).
        val cut = min(validSamples.size, ceil(validSamples.size * 0.3).toInt())
        val stable = validSamples.drop(cut)
        val throughput = when {
            stable.isNotEmpty() -> stable.map { it.mbps }.average()
            validSamples.isNotEmpty() -> validSamples.map { it.mbps }.average()
            bytesTotal.get() > 0L -> (bytesTotal.get() * 8.0) / (durationMeasuredMs.toDouble() / 1000.0) / 1_000_000.0
            else -> 0.0
        }
        if (isDownload && bytesTotal.get() <= 0L) {
            throw IllegalStateException("download_failed:${lastError.get() ?: "sem_dados"}")
        }
        ThroughputPhaseResult(
            throughputMbps = throughput,
            peakMbps = validSamples.maxOfOrNull { it.mbps } ?: 0.0,
        )
    }

    private fun windowGain(samples: List<Sample>, nowMs: Int): Double {
        val recent = samples.filter { it.tMs > nowMs - 3_000 }
        val prior = samples.filter { it.tMs <= nowMs - 3_000 && it.tMs > nowMs - 6_000 }
        if (recent.size < 2 || prior.size < 2) return 0.0
        val recentAvg = recent.map { it.mbps }.average()
        val priorAvg = prior.map { it.mbps }.average()
        if (priorAvg <= 0.0) return 0.0
        return (recentAvg - priorAvg) / priorAvg
    }

    private fun buildResult(
        mode: SpeedtestMode,
        latency: LatencyPhaseResult,
        download: ThroughputPhaseResult,
        upload: ThroughputPhaseResult,
        pingsDownload: List<Double>,
        pingsUpload: List<Double>,
        rounds: List<SpeedtestRoundResult>,
    ): SpeedtestResult {
        val latencyUnderDownload = median(pingsDownload)
        val latencyUnderUpload = median(pingsUpload)
        val bufferbloatMs = if (pingsDownload.isEmpty() && pingsUpload.isEmpty()) {
            0.0
        } else {
            max(max(latencyUnderDownload, latencyUnderUpload) - latency.latencyMs, 0.0)
        }
        val severity = SpeedtestQualityClassifier.classifyBufferbloat(bufferbloatMs)
        val diagnosis = SpeedtestQualityClassifier.classifyQuality(
            downloadMbps = download.throughputMbps,
            uploadMbps = upload.throughputMbps,
            latencyMs = latency.latencyMs,
            jitterMs = latency.jitterMs,
            packetLossPercent = latency.packetLossPercent,
            bufferbloatDeltaMs = bufferbloatMs,
            bufferbloat = severity,
        )
        return SpeedtestResult(
            timestampEpochMs = System.currentTimeMillis(),
            mode = mode,
            downloadMbps = download.throughputMbps,
            uploadMbps = upload.throughputMbps,
            latencyMs = latency.latencyMs,
            jitterMs = latency.jitterMs,
            packetLossPercent = latency.packetLossPercent,
            bufferbloatMs = bufferbloatMs,
            bufferbloatSeverity = severity,
            peakDownloadMbps = download.peakMbps,
            peakUploadMbps = upload.peakMbps,
            qualityDiagnosis = diagnosis,
            rounds = rounds,
        )
    }

    // ── Camada HTTP (OkHttp) ─────────────────────────────────────────────────

    private fun executeDownloadRequest(payloadBytes: Int, onChunk: (Int) -> Unit): Int {
        val request = Request.Builder().url(CloudflareSpeedtestEndpoints.download(payloadBytes)).get().build()
        downloadClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HttpStatus:${response.code}")
            val body = response.body ?: throw IllegalStateException("empty_body")
            val buffer = ByteArray(16 * 1024)
            var total = 0
            body.byteStream().use { stream ->
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    total += read
                    onChunk(read)
                }
            }
            return total
        }
    }

    private fun executeUploadRequest(payloadBytes: Int): Int {
        val payload = uploadPayload(payloadBytes)
        val body = payload.toRequestBody("application/octet-stream".toMediaType())
        val request = Request.Builder().url(CloudflareSpeedtestEndpoints.upload()).post(body).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HttpStatus:${response.code}")
            return payloadBytes
        }
    }

    private fun measurePing(): Double? {
        val request = Request.Builder().url(CloudflareSpeedtestEndpoints.ping()).get().build()
        val start = System.nanoTime()
        return try {
            pingClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.bytes()
                (System.nanoTime() - start) / 1_000_000.0
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun uploadPayload(payloadBytes: Int): ByteArray =
        uploadPayloadCache.getOrPut(payloadBytes) { ByteArray(payloadBytes) { (it and 0xFF).toByte() } }

    // ── Helpers estatísticos ─────────────────────────────────────────────────

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2.0 else sorted[middle]
    }

    private fun jitter(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val deltas = values.zipWithNext { a, b -> abs(b - a) }
        return deltas.average()
    }

    private data class Sample(val tMs: Int, val mbps: Double)

    private data class LatencyPhaseResult(
        val latencyMs: Double,
        val jitterMs: Double,
        val packetLossPercent: Double,
    )

    private data class ThroughputPhaseResult(
        val throughputMbps: Double,
        val peakMbps: Double,
    )

    private data class TransferConfig(
        val pingCount: Int,
        val downloadDurationMs: Long,
        val uploadDurationMs: Long,
        val downloadPayloadBytes: Int,
        val uploadPayloadBytes: Int,
        val initialStreams: Int,
        val maxStreams: Int,
        val warmupMs: Int,
    ) {
        companion object {
            fun forMode(mode: SpeedtestMode): TransferConfig = when (mode) {
                SpeedtestMode.FAST, SpeedtestMode.TRIPLE -> TransferConfig(
                    pingCount = 12,
                    downloadDurationMs = 6_000L,
                    uploadDurationMs = 6_000L,
                    downloadPayloadBytes = 10_000_000,
                    uploadPayloadBytes = 5_000_000,
                    initialStreams = 2,
                    maxStreams = 4,
                    warmupMs = 1_000,
                )
                SpeedtestMode.COMPLETE -> TransferConfig(
                    pingCount = 20,
                    downloadDurationMs = 15_000L,
                    uploadDurationMs = 15_000L,
                    downloadPayloadBytes = 25_000_000,
                    uploadPayloadBytes = 10_000_000,
                    initialStreams = 2,
                    maxStreams = 6,
                    warmupMs = 1_500,
                )
            }
        }
    }
}
