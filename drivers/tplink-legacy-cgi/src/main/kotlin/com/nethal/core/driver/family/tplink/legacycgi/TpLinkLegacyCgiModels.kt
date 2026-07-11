package com.nethal.core.driver.family.tplink.legacycgi

/**
 * Modelos de dados da Driver Family `tplink-legacy-cgi-driver` (protocolo real confirmado por
 * captura via DevTools contra unidade física do Luiz, 2026-07-06, ver SIG-337/SIG-338).
 *
 * Movido de `driver/tplink/TplinkC20Models.kt` no passo 4 do plano de refatoração HAL
 * (`docs/architecture/hal-layering-model.md` §10) — mesma lógica, sem mudança de comportamento.
 * Antes nomeado por modelo (`TplinkC20*`); agora nomeado pela plataforma/Driver Family
 * (`tplink-legacy-cgi`), já que o protocolo serve qualquer modelo TP-Link com o mesmo dispatcher
 * único `/cgi` + Basic Auth via cookie — não só o Archer C20.
 *
 * O protocolo real é um dispatcher único `/cgi` com blocos de texto por seção
 * (`[NOME_SECAO#...]indice,qtd`), não JSON por endpoint — ver `TpLinkLegacyCgiResponseParser` para
 * o formato completo. Seções/campos por modelo vêm de `profile.driverConfig`
 * (`TpLinkLegacyCgiDriverConfig`), nunca hardcoded aqui.
 */

/** Identificação do equipamento — seções configuráveis via `driverConfig` (tipicamente IGD_DEV_INFO + ETH_SWITCH + SYS_MODE). */
data class TpLinkLegacyCgiDeviceInfo(
    val modelName: String,
    val description: String,
    val isFactoryDefault: Boolean?,
    val numberOfVirtualPorts: Int?,
    val mode: String?,
)

/** Uma linha de rádio Wi-Fi — seção configurável via `driverConfig` (tipicamente LAN_WLAN). */
data class TpLinkLegacyCgiWifiStatus(
    val name: String,
    val ssid: String,
)

/**
 * Um cliente DHCP conectado — seção configurável via `driverConfig` (tipicamente LAN_HOST_ENTRY).
 * MAC bruto (ADR 0001, `docs/architecture/adr/0001-fronteira-sanitizacao-telemetria.md`):
 * mascaramento é responsabilidade exclusiva de um futuro Telemetry Collector, aplicada só na
 * fronteira de exportação — nunca no modelo de dados local.
 */
data class TpLinkLegacyCgiConnectedClient(
    val hostname: String,
    val ipAddress: String,
    val macAddress: String,
    val leaseTimeRemainingSeconds: Long?,
)

/** Snapshot agregado das capabilities confirmadas, retornado pelo orquestrador da Driver Family. */
data class TpLinkLegacyCgiSnapshot(
    val deviceInfo: TpLinkLegacyCgiDeviceInfo?,
    val wifi: List<TpLinkLegacyCgiWifiStatus>,
    val connectedClients: List<TpLinkLegacyCgiConnectedClient>,
)
