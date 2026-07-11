package com.nethal.feature.status

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nethal.core.capability.CapabilityEngine
import com.nethal.core.catalog.CapabilityReadResult
import com.nethal.core.model.CapabilityId
import com.nethal.core.model.CapabilityPayload
import com.nethal.core.model.DeviceType
import com.nethal.core.model.WifiBand
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Orquestra a tela Status (issue #83) com o mecanismo de atualização contínua da issue #107.
 *
 * ## Mecanismo de atualização escolhido (registrado no PR, critério de aceite de #107)
 *
 * Combinação de **poll em intervalo enquanto a tela está visível** ([onScreenStarted]/[POLL_INTERVAL_MILLIS])
 * + **pull-to-refresh manual** ([refreshNow], design system seção "Navegação por gestos" 1p — a
 * única forma de atualização que a spec visual documenta explicitamente). WorkManager foi descartado
 * de propósito: ele existe para trabalho garantido mesmo em background/processo morto, exatamente o
 * oposto do que a skill `/seguranca-nethal` pede aqui — a sessão administrativa não pode ficar viva
 * fora do primeiro plano.
 *
 * ## Ciclo de vida da sessão (skill `/seguranca-nethal`)
 *
 * [capabilityEngine] chega já autenticado (mesmo handoff usado hoje entre Tela 5 → Tela 4, ver
 * `AuthenticationViewModel.captureAuthenticatedSession`) — este módulo nunca autentica sozinho e
 * nunca vê credencial crua. [onScreenStarted] (chamado pela tela ao entrar em composição) liga o
 * poll; [onScreenStopped] (chamado ao sair de composição — inclusive ao trocar de aba na bottom nav,
 * que remove este composable da árvore mesmo com o `ViewModel` sobrevivendo via
 * `saveState`/`restoreState`) cancela o poll E encerra a sessão local
 * ([CapabilityEngine.closeSession]) — nunca fica lendo o equipamento com a tela fora de composição.
 * [onCleared] repete o encerramento como rede de segurança (processo matando o `ViewModel` sem passar
 * pelo `DisposableEffect` da tela).
 *
 * Encerrar a sessão descarta a credencial em memória de [CapabilityEngine] (ver KDoc de
 * `CapabilityEngine.closeSession`) — reentrar nesta tela depois de [onScreenStopped] sem uma nova
 * sessão autenticada resulta em [StatusUiState.SessionUnavailable] honesto, não numa tentativa de
 * reautenticar sozinha (este módulo não tem para onde voltar sem depender de outro `:feature:*`,
 * proibido pela ADR 0002). Provisionar uma sessão nova ao reabrir a aba é decisão do composition
 * root (`:app`), fora do escopo deste módulo.
 */
class StatusViewModel(
    private val capabilityEngine: CapabilityEngine?,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val _uiState = MutableStateFlow<StatusUiState>(StatusUiState.Loading)
    val uiState: StateFlow<StatusUiState> = _uiState.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private var pollingJob: Job? = null

    /** Chamado pela tela ao entrar em composição — inicia (ou reinicia, se já tinha parado) o poll ao vivo. */
    fun onScreenStarted() {
        if (pollingJob?.isActive == true) return
        pollingJob = viewModelScope.launch {
            while (isActive) {
                loadStatus()
                delay(POLL_INTERVAL_MILLIS)
            }
        }
    }

    /** Chamado pela tela ao sair de composição — para o poll e encerra a sessão administrativa (nunca fica aberta em background). */
    fun onScreenStopped() {
        pollingJob?.cancel()
        pollingJob = null
        capabilityEngine?.closeSession()
    }

    /** Pull-to-refresh (design system 1p) — leitura imediata, sem esperar o próximo tick do poll. */
    fun refreshNow() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            _isRefreshing.value = true
            loadStatus()
            _isRefreshing.value = false
            while (isActive) {
                delay(POLL_INTERVAL_MILLIS)
                loadStatus()
            }
        }
    }

    override fun onCleared() {
        pollingJob?.cancel()
        capabilityEngine?.closeSession()
    }

    /** Lê sequencialmente (mesmo raciocínio de `CapabilitiesViewModel`: renovação de sessão não foi desenhada para leituras concorrentes). */
    private suspend fun loadStatus() {
        val engine = capabilityEngine
        if (engine == null || !engine.isSessionActive) {
            _uiState.value = StatusUiState.SessionUnavailable(
                reason = "Nenhuma sessão administrativa ativa chegou até a tela de Status — " +
                    "pareie o equipamento novamente para ver dado ao vivo.",
            )
            return
        }

        val deviceInfoResult = engine.readCapability(CapabilityId.READ_DEVICE_INFO)
        val wifiResult = engine.readCapability(CapabilityId.READ_WIFI_STATUS)
        val wanResult = engine.readCapability(CapabilityId.READ_WAN_STATUS)
        val variant = resolveVariant(engine, deviceInfoResult)

        _uiState.value = buildLoadedState(deviceInfoResult, wifiResult, wanResult, variant)
    }

    /**
     * Decide qual [StatusVariant] renderizar (issues #87, #88+#106) — nunca por comparação de
     * fabricante (`if (vendor == ...)`), sempre por dado estrutural real já lido do equipamento.
     *
     * 1. `DeviceInfo.deviceType` de [deviceInfoResult] quando o driver o declara — hoje só o driver
     *    Nokia (`NokiaGponDriverFamily`) declara `DeviceType.ONT` em toda leitura bem-sucedida de
     *    `READ_DEVICE_INFO`. Escolher a variante por esse fato estrutural (em vez de só pela
     *    capability de sinal responder) mantém a variante ONT estável mesmo num tick em que
     *    `READ_SIGNAL` falhe transitoriamente — o card mostra o motivo honesto
     *    ([OpticalSignalDisplay.unavailableReason]) em vez de silenciosamente virar a variante
     *    genérica.
     * 2. Quando o driver não declara `deviceType` nenhum — hoje o caso de
     *    `TpLinkStokLuciDriverFamily`, que ainda não implementa `READ_DEVICE_INFO` — a variante Mesh
     *    é detectada pela própria capability `READ_MESH_TOPOLOGY` responder com sucesso (confirmado
     *    como backend suficiente no comentário da issue #106); só se essa não responder é que
     *    `READ_SIGNAL` é tentado como sinal secundário de variante ONT.
     * 3. Nenhuma capability extra de variante é lida quando o passo 1 já resolve — equipamento ONT
     *    nunca dispara `READ_MESH_TOPOLOGY`, e vice-versa.
     */
    private suspend fun resolveVariant(
        engine: CapabilityEngine,
        deviceInfoResult: CapabilityReadResult,
    ): StatusVariant? {
        val deviceType = (deviceInfoResult as? CapabilityReadResult.Success)
            ?.payload.let { it as? CapabilityPayload.DeviceInfo }?.info?.deviceType

        return when (deviceType) {
            DeviceType.ONT, DeviceType.ONU -> loadOntVariant(engine, signalResult = engine.readCapability(CapabilityId.READ_SIGNAL))
            DeviceType.MESH -> loadMeshVariant(engine, meshResult = engine.readCapability(CapabilityId.READ_MESH_TOPOLOGY))
            else -> {
                val meshResult = engine.readCapability(CapabilityId.READ_MESH_TOPOLOGY)
                if (meshResult is CapabilityReadResult.Success) {
                    loadMeshVariant(engine, meshResult)
                } else {
                    val signalResult = engine.readCapability(CapabilityId.READ_SIGNAL)
                    if (signalResult is CapabilityReadResult.Success) loadOntVariant(engine, signalResult) else null
                }
            }
        }
    }

    private suspend fun loadOntVariant(engine: CapabilityEngine, signalResult: CapabilityReadResult): StatusVariant.Ont {
        val gponErrorsResult = engine.readCapability(CapabilityId.READ_GPON_ERROR_COUNTERS)
        val lanPortsResult = engine.readCapability(CapabilityId.READ_LAN_PORT_STATUS)
        return StatusVariant.Ont(
            signal = signalDisplayFrom(signalResult),
            gponErrors = gponErrorCountersDisplayFrom(gponErrorsResult),
            lanPorts = lanPortsDisplayFrom(lanPortsResult),
        )
    }

    private suspend fun loadMeshVariant(engine: CapabilityEngine, meshResult: CapabilityReadResult): StatusVariant.Mesh =
        StatusVariant.Mesh(topology = meshTopologyDisplayFrom(meshResult))

    private fun signalDisplayFrom(result: CapabilityReadResult): OpticalSignalDisplay {
        val signal = (result as? CapabilityReadResult.Success)?.payload.let { it as? CapabilityPayload.Signal }?.status
        if (result !is CapabilityReadResult.Success || signal == null) {
            return OpticalSignalDisplay(
                rxPowerDbm = null,
                txPowerDbm = null,
                rxPowerMarginToLowerThresholdDb = null,
                dot = StatusDotLevel.ERROR,
                unavailableReason = readResultReason(result),
            )
        }
        return OpticalSignalDisplay(
            rxPowerDbm = signal.rxPowerDbm,
            txPowerDbm = signal.txPowerDbm,
            rxPowerMarginToLowerThresholdDb = signal.rxPowerMarginToLowerThresholdDb,
            // Só classifica ruim quando a margem (já calculada pelo driver, issue #28) é negativa —
            // sem margem disponível, mostra o dado bruto sem inventar uma classificação de saúde.
            dot = if ((signal.rxPowerMarginToLowerThresholdDb ?: 0.0) < 0) StatusDotLevel.WARNING else StatusDotLevel.OK,
        )
    }

    private fun gponErrorCountersDisplayFrom(result: CapabilityReadResult): GponErrorCountersDisplay {
        val counters = (result as? CapabilityReadResult.Success)?.payload.let { it as? CapabilityPayload.GponErrorCounters }?.counters
        if (result !is CapabilityReadResult.Success || counters == null) {
            return GponErrorCountersDisplay(
                fecErrorCount = null,
                hecErrorCount = null,
                dropPacketsCount = null,
                unavailableReason = readResultReason(result),
            )
        }
        return GponErrorCountersDisplay(
            fecErrorCount = counters.fecErrorCount,
            hecErrorCount = counters.hecErrorCount,
            dropPacketsCount = counters.dropPacketsCount,
        )
    }

    private fun lanPortsDisplayFrom(result: CapabilityReadResult): LanPortsDisplay {
        val status = (result as? CapabilityReadResult.Success)?.payload.let { it as? CapabilityPayload.LanPorts }?.status
        if (result !is CapabilityReadResult.Success || status == null) {
            return LanPortsDisplay(ports = emptyList(), unavailableReason = readResultReason(result))
        }
        return LanPortsDisplay(
            ports = status.ports.map { port ->
                LanPortRow(
                    portNumber = port.portNumber,
                    isUp = port.isUp,
                    linkSpeedMbps = port.linkSpeedMbps,
                    errorsSent = port.errorsSent,
                    errorsReceived = port.errorsReceived,
                )
            },
        )
    }

    private fun meshTopologyDisplayFrom(result: CapabilityReadResult): MeshTopologyDisplay {
        val topology = (result as? CapabilityReadResult.Success)?.payload.let { it as? CapabilityPayload.MeshTopology }?.topology
        if (result !is CapabilityReadResult.Success || topology == null) {
            return MeshTopologyDisplay(
                routerLabel = null,
                satelliteNodeCount = 0,
                clients = emptyList(),
                unavailableReason = readResultReason(result),
            )
        }
        return MeshTopologyDisplay(
            routerLabel = listOfNotNull(topology.routerModel, topology.routerName).joinToString(" · ").ifBlank { null },
            satelliteNodeCount = topology.satelliteNodeCount,
            clients = topology.clients.map { node ->
                MeshClientRow(
                    hostname = node.hostname,
                    macAddress = node.macAddress,
                    ipAddress = node.ipAddress,
                    wireType = node.wireType,
                )
            },
        )
    }

    private fun buildLoadedState(
        deviceInfoResult: CapabilityReadResult,
        wifiResult: CapabilityReadResult,
        wanResult: CapabilityReadResult,
        variant: StatusVariant?,
    ): StatusUiState.Loaded {
        val deviceInfo = (deviceInfoResult as? CapabilityReadResult.Success)
            ?.payload.let { it as? CapabilityPayload.DeviceInfo }?.info
        val wifi = (wifiResult as? CapabilityReadResult.Success)
            ?.payload.let { it as? CapabilityPayload.Wifi }?.status
        val wan = (wanResult as? CapabilityReadResult.Success)
            ?.payload.let { it as? CapabilityPayload.Wan }?.status

        val equipmentLabel = listOfNotNull(deviceInfo?.vendor, deviceInfo?.model)
            .joinToString(" ")
            .ifBlank { "Equipamento" }

        val equipmentDetail = buildString {
            append(if (deviceInfoResult is CapabilityReadResult.Success) "Online" else "Status desconhecido")
            deviceInfo?.uptimeSeconds?.let { append(" · ${formatUptime(it)}") }
            deviceInfo?.firmware?.let { append(" · firmware $it") }
        }

        val equipmentDot = when (deviceInfoResult) {
            is CapabilityReadResult.Success -> StatusDotLevel.OK
            is CapabilityReadResult.SessionExpired -> StatusDotLevel.ERROR
            else -> StatusDotLevel.WARNING
        }

        val primaryRadio = wifi?.radios?.firstOrNull { it.enabled != false } ?: wifi?.radios?.firstOrNull()
        val wifiDisplay = when {
            wifiResult is CapabilityReadResult.Success && primaryRadio != null ->
                WifiStatusDisplay(
                    label = listOfNotNull(primaryRadio.ssid, bandLabel(primaryRadio.band))
                        .joinToString(" · ")
                        .ifBlank { bandLabel(primaryRadio.band) },
                    detail = listOfNotNull(
                        primaryRadio.channel?.let { "canal $it" },
                        primaryRadio.security,
                    ).joinToString(" · ").ifBlank { "sem detalhes" },
                    dot = if (primaryRadio.enabled == false) StatusDotLevel.WARNING else StatusDotLevel.OK,
                )
            wifiResult is CapabilityReadResult.Success -> null
            else -> WifiStatusDisplay(
                label = "Wi-Fi",
                detail = readResultReason(wifiResult),
                dot = StatusDotLevel.ERROR,
            )
        }

        return StatusUiState.Loaded(
            equipmentLabel = equipmentLabel,
            equipmentDetail = equipmentDetail,
            equipmentDot = equipmentDot,
            wifi = wifiDisplay,
            publicIp = wan?.ipv4Address,
            // Sem CapabilityId de teste de velocidade hoje — ver KDoc de SpeedSample. Nunca mockado.
            speed = null,
            lastUpdatedAtMillis = nowMillis(),
            variant = variant,
        )
    }

    private fun readResultReason(result: CapabilityReadResult): String = when (result) {
        is CapabilityReadResult.Unavailable -> result.reason
        is CapabilityReadResult.Failure -> result.reason
        is CapabilityReadResult.SessionExpired -> result.reason
        is CapabilityReadResult.Success -> "" // inatingível nos call sites acima (já filtrado por `is Success`)
    }

    private fun bandLabel(band: WifiBand): String = when (band) {
        WifiBand.GHZ_2_4 -> "2,4 GHz"
        WifiBand.GHZ_5 -> "5 GHz"
        WifiBand.GHZ_6 -> "6 GHz"
        WifiBand.UNKNOWN -> "Wi-Fi"
    }

    private fun formatUptime(totalSeconds: Long): String {
        val days = totalSeconds / 86_400
        val hours = (totalSeconds % 86_400) / 3_600
        return when {
            days > 0 -> "${days}d ${hours}h"
            hours > 0 -> "${hours}h"
            else -> "<1h"
        }
    }

    private companion object {
        const val POLL_INTERVAL_MILLIS = 15_000L
    }
}
