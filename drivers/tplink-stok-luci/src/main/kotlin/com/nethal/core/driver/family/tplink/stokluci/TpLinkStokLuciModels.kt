package com.nethal.core.driver.family.tplink.stokluci

/**
 * Modelos de dados da Driver Family `tplink-stok-luci-driver` (plataforma `tplink-stok-luci`, ver
 * `docs/architecture/hal-layering-model.md` §9.1/§5.4 e profile `tplink_archer_c6_stok_v1` no
 * catálogo).
 *
 * **Terceira rodada de correção (2026-07-07), evidência definitiva via Playwright**: captura de
 * navegador real com `page.on('response')` interceptando corpo completo de request E response de
 * cada chamada `cgi-bin/luci` durante um login real bem-sucedido (inclusive chamadas autenticadas
 * pós-login, com `stok` real funcionando). Essa captura completa **confirma que existem sim duas
 * chamadas de preparação com duas chaves RSA distintas** (`form=keys` para a chave de senha,
 * `form=auth` para a chave de assinatura + `seq`), exatamente como a lib de referência
 * `tplinkrouterc6u` sempre documentou.
 *
 * A rodada anterior (manifesto `catalog-2026.07.17.json`) tinha concluído, por engano, que existia
 * **uma única chamada** (`form=auth`) e uma única chave RSA reaproveitada para senha e assinatura.
 * Essa conclusão foi baseada em captura incompleta feita com a extensão Chrome, que pulou a chamada
 * `form=keys` por algum motivo de cache/estado do navegador naquela tentativa específica — não
 * porque o protocolo real só tem uma chamada. A captura completa via Playwright desta rodada
 * corrige esse engano.
 */

/** Sessão pós-login: token `stok` (usado no path de toda chamada autenticada) e cookie `sysauth`, se presente. */
internal data class TpLinkStokLuciSession(
    val stok: String,
    val sysauthCookie: String?,
)

/**
 * Par de chave RSA em hex (módulo, expoente). Este mesmo formato é usado para as duas chaves
 * distintas do handshake: a de `form=keys` (1024-bit, cifra a senha) e a de `form=auth` (512-bit,
 * assina o envelope `sign`) — ver [TpLinkStokLuciPasswordKey] e [TpLinkStokLuciAuthKeys].
 */
internal data class TpLinkStokLuciRsaKey(
    val modulusHex: String,
    val exponentHex: String,
)

/**
 * Resposta real de `form=keys` (`operation=read`): `{"success":true,"data":{"password":[nn, ee],
 * "mode":"router","username":""}}`. `data.password` é a chave RSA usada **só para cifrar a
 * senha** — módulo de 256 caracteres hex = 128 bytes = RSA 1024-bit. Chave distinta da devolvida
 * por `form=auth` ([TpLinkStokLuciAuthKeys]).
 */
internal data class TpLinkStokLuciPasswordKey(
    val key: TpLinkStokLuciRsaKey,
)

/**
 * Resposta real de `form=auth` (`operation=read`): `{"success":true,"data":{"key":[nn, ee],
 * "seq":N}}`. `data.key` é a chave RSA usada **só para assinar o envelope `sign`** — módulo de 128
 * caracteres hex = 64 bytes = RSA 512-bit. Chave distinta da devolvida por `form=keys`
 * ([TpLinkStokLuciPasswordKey]).
 */
internal data class TpLinkStokLuciAuthKeys(
    val seq: Long,
    val key: TpLinkStokLuciRsaKey,
)

/**
 * Modelos estruturados do payload de status desta plataforma (`admin/status?form=all`), mapeados
 * pelo vocabulário de capabilities do NetHAL a partir do JSON bruto — ver [TpLinkStokLuciStatusParser]
 * para as regras exatas de extração.
 *
 * Por decisão de `docs/architecture/adr/0001-fronteira-sanitizacao-telemetria.md`, este modelo
 * carrega dado bruto (SSID em texto puro, MAC completo) — sanitização de telemetria (hash de SSID,
 * mascaramento de MAC) é responsabilidade exclusiva de um futuro Telemetry Collector, aplicada só
 * na fronteira de exportação, não do modelo interno do driver. `readSnapshot()` hoje alimenta
 * apenas uso local (`ManualCheckRunner` e futura tela do NetHAL Lab), sem upload para nuvem em
 * lugar nenhum do código.
 *
 * A única regra que permanece é de coleta, não de sanitização: a senha do Wi-Fi (`*_psk_key`)
 * nunca é lida para nenhum campo deste modelo — não existe campo para isso em
 * [TpLinkStokLuciWifiRadio] de propósito.
 */
internal enum class TpLinkStokLuciWifiBand { GHZ_2_4, GHZ_5, UNKNOWN }

/** Um rádio Wi-Fi (rede principal ou de convidados) — cobre `READ_WIFI_STATUS`. */
internal data class TpLinkStokLuciWifiRadio(
    val id: String,
    val band: TpLinkStokLuciWifiBand,
    val guestNetwork: Boolean,
    val ssid: String?,
    val channel: Int?,
)

/** Status de LAN (`lan_macaddr`/`lan_ipv4_ipaddr`) — cobre `READ_LAN_STATUS`. */
internal data class TpLinkStokLuciLanStatus(
    val macAddress: String?,
    val ipv4Address: String?,
)

/** Status de WAN (`wan_ipv4_ipaddr`) — cobre `READ_WAN_STATUS`. */
internal data class TpLinkStokLuciWanStatus(
    val ipv4Address: String?,
)

/** Um dispositivo cabeado de `access_devices_wired` — cobre `READ_CONNECTED_CLIENTS`. */
internal data class TpLinkStokLuciConnectedClient(
    val hostname: String?,
    val ipAddress: String?,
    val macAddress: String?,
)

/** Snapshot estruturado agregando todas as capabilities cobertas pelo parser desta plataforma. */
internal data class TpLinkStokLuciSnapshot(
    val wifi: List<TpLinkStokLuciWifiRadio>,
    val lan: TpLinkStokLuciLanStatus?,
    val wan: TpLinkStokLuciWanStatus?,
    val connectedClients: List<TpLinkStokLuciConnectedClient>,
)
