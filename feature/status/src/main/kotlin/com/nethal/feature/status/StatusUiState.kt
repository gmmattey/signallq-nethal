package com.nethal.feature.status

/**
 * Estado da tela "Status" (issue #83, uso diário — destino [com.nethal.core.navigation.BottomNavDestination.STATUS]).
 *
 * Diferente da extinta `CapabilitiesScreen` (decisão `docs/product/decisions/0001-telas-orfas-redesenho.md`),
 * esta tela nunca lista o vocabulário bruto de `CapabilityId` — só os recortes de dado ao vivo já
 * lidos ([Loaded]), com o motivo (`reason`) honesto quando algo não estiver disponível, nunca um
 * valor inventado.
 */
sealed interface StatusUiState {

    /** Primeira leitura ainda em andamento — nenhum dado chegou até agora nesta instância de tela. */
    data object Loading : StatusUiState

    /**
     * Sem sessão administrativa ativa disponível para esta tela — estado perdido (processo
     * recriado, sessão fechada por [StatusViewModel.onScreenStopped] numa visita anterior e nunca
     * reaberta) ou a sessão nunca chegou a esta tela. Nunca finge ter dado: mostra o motivo e deixa
     * quem compõe o grafo (`:app`, fora deste módulo) decidir como reencaminhar o usuário para
     * reautenticar — este módulo não conhece o fluxo de pareamento (regra de dependência única da
     * ADR 0002).
     */
    data class SessionUnavailable(val reason: String) : StatusUiState

    data class Loaded(
        val equipmentLabel: String,
        val equipmentDetail: String?,
        val equipmentDot: StatusDotLevel,
        val wifi: WifiStatusDisplay?,
        val publicIp: String?,
        val speed: SpeedSample?,
        val lastUpdatedAtMillis: Long,
        /**
         * Variante especializada da tela (issues #87/#88+#106) — `null` para o equipamento genérico
         * (Roteador, comportamento já existente antes destas issues, sem regressão). Nunca é uma
         * escolha do usuário: [StatusViewModel.resolveVariant] decide a partir de dado estrutural
         * real já lido do equipamento (`DeviceInfo.deviceType` quando o driver o declara, ou a
         * própria capability de variante respondendo com sucesso quando o driver não declara
         * `deviceType`) — nunca por comparação de fabricante.
         */
        val variant: StatusVariant? = null,
    ) : StatusUiState
}

/**
 * Bloco de dado adicional específico de um tipo de equipamento, além do card genérico
 * equipamento+Wi-Fi já existente. Um card por sub-bloco de dado ([OpticalSignalDisplay],
 * [GponErrorCountersDisplay], [LanPortsDisplay], [MeshTopologyDisplay]) — cada um honesto sobre
 * disponibilidade (`unavailableReason` não nulo quando a leitura ao vivo falhou ou a capability não
 * está disponível), nunca dado inventado, mesmo padrão já usado por [WifiStatusDisplay]/[SpeedSample].
 */
sealed interface StatusVariant {
    /** Variante ONT/GPON (issue #87, driver Nokia G-1425G-B) — capabilities #27-30. */
    data class Ont(
        val signal: OpticalSignalDisplay,
        val gponErrors: GponErrorCountersDisplay,
        val lanPorts: LanPortsDisplay,
    ) : StatusVariant

    /** Variante Mesh (issue #88+#106, driver TP-Link Archer C6/OneMesh) — capability `READ_MESH_TOPOLOGY` (#32). */
    data class Mesh(
        val topology: MeshTopologyDisplay,
    ) : StatusVariant
}

/** Recorte de `READ_SIGNAL` (potência óptica) para a variante ONT. */
data class OpticalSignalDisplay(
    val rxPowerDbm: Double?,
    val txPowerDbm: Double?,
    /** Margem já calculada pelo driver (`SignalStatus.rxPowerMarginToLowerThresholdDb`, issue #28) — nunca recalculada aqui. */
    val rxPowerMarginToLowerThresholdDb: Double?,
    val dot: StatusDotLevel,
    val unavailableReason: String? = null,
)

/** Recorte de `READ_GPON_ERROR_COUNTERS` para a variante ONT. */
data class GponErrorCountersDisplay(
    val fecErrorCount: Long?,
    val hecErrorCount: Long?,
    val dropPacketsCount: Long?,
    val unavailableReason: String? = null,
)

/** Uma porta LAN, recorte de `READ_LAN_PORT_STATUS` para a variante ONT. */
data class LanPortRow(
    val portNumber: Int,
    val isUp: Boolean,
    val linkSpeedMbps: String?,
    val errorsSent: Long?,
    val errorsReceived: Long?,
)

/**
 * Recorte de `READ_LAN_PORT_STATUS` para a variante ONT. Lista vazia com [unavailableReason] `null`
 * é dado real (equipamento reportou zero portas), distinto de leitura indisponível — mesmo raciocínio
 * já documentado em `TpLinkStokLuciDriverFamily.meshTopologyResultFor` para `READ_CONNECTED_CLIENTS`.
 */
data class LanPortsDisplay(
    val ports: List<LanPortRow>,
    val unavailableReason: String? = null,
)

/** Um cliente conectado à malha mesh, recorte de `MeshTopologyNode` para a variante Mesh. */
data class MeshClientRow(
    val hostname: String?,
    val macAddress: String?,
    val ipAddress: String?,
    val wireType: String?,
)

/** Recorte de `READ_MESH_TOPOLOGY` para a variante Mesh. Lista de clientes/contagem de satélites vazia é dado real, mesmo raciocínio de [LanPortsDisplay]. */
data class MeshTopologyDisplay(
    val routerLabel: String?,
    val satelliteNodeCount: Int,
    val clients: List<MeshClientRow>,
    val unavailableReason: String? = null,
)

/** Cor do indicador de status (design system, tokens de sucesso/aviso/erro — nunca cor decorativa). */
enum class StatusDotLevel {
    OK,
    WARNING,
    ERROR,
}

data class WifiStatusDisplay(
    val label: String,
    val detail: String,
    val dot: StatusDotLevel,
)

/**
 * Amostra de velocidade para o card com sparkline (design system, seção "Componentes" 1h/1i).
 *
 * Nenhum [com.nethal.core.model.CapabilityId] hoje cobre teste de velocidade/throughput — este tipo
 * existe para o componente já nascer pronto para consumir uma capability futura, mas
 * [StatusViewModel] nunca produz uma instância mockada: enquanto não houver capability real, o card
 * mostra o estado "indisponível" (design system, seção 1v), nunca um número inventado.
 */
data class SpeedSample(
    val downloadMbps: Double,
    val history: List<Float>,
)
