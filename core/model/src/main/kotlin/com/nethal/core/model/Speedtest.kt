package com.nethal.core.model

/**
 * Shape de resultado do teste de velocidade (issues #90/#98) — teste direto de internet do
 * aparelho contra `speed.cloudflare.com`, **não** uma leitura/ação de equipamento. Por isso este
 * modelo fica fora do vocabulário [CapabilityId]/[Capability]: não existe [DriverFamily] nem
 * `CapabilityEngine` envolvidos — o teste não precisa (nem tenta) autenticar no roteador, mede só
 * a conexão de internet do próprio celular. Decisão registrada no PR de #90/#98.
 *
 * Vive em `:core:model` (não dentro de `:feature:tools-speedtest`) pelo mesmo motivo de
 * [NativeDiagnosticPingRequest]/[NativeDiagnosticPingResult]: um shape de domínio que uma tela
 * consome deve ficar disponível no módulo compartilhado mais baixo da árvore, mesmo quando o fluxo
 * de execução em si (o motor OkHttp) vive só no módulo de feature.
 */
enum class SpeedtestMode {
    /** ~6s de download + ~6s de upload, poucos streams — teste rápido do dia a dia. */
    FAST,

    /** ~15s por direção, mais streams — estimativa mais estável em links rápidos. */
    COMPLETE,

    /** 3 rodadas no perfil de [FAST] com um intervalo entre elas; resultado final é a mediana das 3. */
    TRIPLE,
}

enum class SpeedtestPhase {
    IDLE,
    LATENCY,
    DOWNLOAD,
    UPLOAD,
    DONE,
}

enum class SpeedtestRunState {
    IDLE,
    RUNNING,
    DONE,
    ERROR,
}

enum class BufferbloatSeverity {
    NONE,
    MILD,
    MODERATE,
    SEVERE,
}

/** Veredito de adequação da conexão medida a um tipo de uso — thresholds em `SpeedtestQualityClassifier` (`:feature:tools-speedtest`). */
enum class SpeedtestUsageVerdict {
    GOOD,
    ACCEPTABLE,
    POOR,
}

/** Gargalo mais provável por trás de um veredito ruim — para a tela explicar "por quê", não só "quão rápido". */
enum class SpeedtestBottleneck {
    NONE,
    LATENCY,
    UPLOAD,
    BUFFERBLOAT,
    PACKET_LOSS,
}

data class SpeedtestQualityDiagnosis(
    val streamingVerdict: SpeedtestUsageVerdict,
    val gamingVerdict: SpeedtestUsageVerdict,
    val videoCallVerdict: SpeedtestUsageVerdict,
    val primaryBottleneck: SpeedtestBottleneck,
)

/** Uma rodada do modo [SpeedtestMode.TRIPLE] — usada só para calcular a mediana final, não exibida individualmente na tela. */
data class SpeedtestRoundResult(
    val downloadMbps: Double,
    val uploadMbps: Double,
    val latencyMs: Double,
)

data class SpeedtestResult(
    val timestampEpochMs: Long,
    val mode: SpeedtestMode,
    val downloadMbps: Double,
    val uploadMbps: Double,
    val latencyMs: Double,
    val jitterMs: Double,
    val packetLossPercent: Double,
    val bufferbloatMs: Double,
    val bufferbloatSeverity: BufferbloatSeverity,
    val peakDownloadMbps: Double,
    val peakUploadMbps: Double,
    val qualityDiagnosis: SpeedtestQualityDiagnosis,
    /** Só populado em [SpeedtestMode.TRIPLE] — as 3 rodadas que geraram a mediana acima. */
    val rounds: List<SpeedtestRoundResult> = emptyList(),
)

/** Amostra instantânea para o gráfico ao vivo da tela "testando" (protótipo `4a`). */
data class SpeedtestLivePoint(
    val atMillis: Long,
    val downloadMbps: Double? = null,
    val uploadMbps: Double? = null,
)

/** Snapshot publicado pelo motor de speedtest enquanto o teste roda — consumido pela ViewModel da tela. */
data class SpeedtestSnapshot(
    val runState: SpeedtestRunState,
    val phase: SpeedtestPhase = SpeedtestPhase.IDLE,
    val progressPercent: Int = 0,
    val liveMbps: Double = 0.0,
    val livePoints: List<SpeedtestLivePoint> = emptyList(),
    val result: SpeedtestResult? = null,
    val errorMessage: String? = null,
    /** 1..3 durante [SpeedtestMode.TRIPLE], 0 nos demais modos. */
    val currentRound: Int = 0,
)
