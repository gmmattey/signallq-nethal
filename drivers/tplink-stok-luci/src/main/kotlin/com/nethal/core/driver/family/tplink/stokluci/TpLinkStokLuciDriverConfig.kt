package com.nethal.core.driver.family.tplink.stokluci

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Schema concreto (opaco para o resto do catálogo, ver `hal-layering-model.md` §5.6/§11.1) do
 * `driverConfig` consumido por [TpLinkStokLuciDriverFamily] — só esta Driver Family sabe
 * interpretar este formato.
 *
 * Diferente do bundle de seções/campos posicional do `tplink-legacy-cgi` (dispatcher `/cgi` em
 * texto plano), o protocolo `stok`/luci é baseado em caminhos (`form=<nome>`) sob o dispatcher
 * único `/cgi-bin/luci/;stok=<token>/<path>` — por isso este `driverConfig` é deliberadamente mais
 * simples: hoje só declara o caminho relativo (após o `stok`) usado para a primeira leitura real
 * pós-login, seguindo o mesmo espírito de "nunca hardcodar dado de modelo na Driver Family" já
 * estabelecido pelo `tplink-legacy-cgi-driver`.
 *
 * Cresce (novos campos) conforme mais leituras autenticadas forem implementadas — nenhum campo
 * aqui é específico do Archer C6; um segundo profile no mesmo protocolo (ex.: outro modelo TP-Link
 * com o mesmo firmware `stok`/luci) só precisaria de um `driverConfig` próprio com o path/seções do
 * seu modelo.
 */
@Serializable
data class TpLinkStokLuciDriverConfig(
    /**
     * Caminho relativo (após `;stok=<token>/`) usado para a leitura de status geral pós-login, ex.:
     * `"admin/status"`. Vem de pesquisa de terceiros (`tplinkrouterc6u`), ainda sem confirmação por
     * captura real contra o hardware do Luiz — ver evidência do profile `tplink_archer_c6_stok_v1`.
     */
    val statusReadPath: String,
    /** Query string fixa (sem `?`) anexada a [statusReadPath], ex.: `"form=all&operation=read"`. */
    val statusReadQuery: String,
    /**
     * Caminho relativo da topologia mesh (issue #32) — `admin/onemesh_network?form=mesh_topology`
     * documentado em `TPLINK_ARCHER_ROUTER_FIELD_MAP.md`, sem captura byte a byte própria ainda
     * (mesmo nível de confiança das demais leituras deste profile antes de validação ao vivo).
     */
    val meshTopologyPath: String,
    /** Query string fixa anexada a [meshTopologyPath], ex.: `"form=mesh_topology&operation=read"`. */
    val meshTopologyQuery: String,
    /**
     * Caminho relativo dos thresholds de proteção DoS (issue #34) —
     * `admin/security_settings?form=dos_setting`.
     */
    val dosSettingPath: String,
    /** Query string fixa anexada a [dosSettingPath], ex.: `"form=dos_setting&operation=read"`. */
    val dosSettingQuery: String,
    /**
     * Caminho relativo do diagnóstico nativo (ping, issue #26) — `admin/diag?form=diag`. Capability
     * de AÇÃO (`RUN_NATIVE_DIAGNOSTIC_PING`), não de leitura — ver
     * [TpLinkStokLuciDriverFamily.runNativeDiagnosticPing].
     */
    val diagPath: String,
    /** Query string fixa anexada a [diagPath], ex.: `"form=diag"`. */
    val diagQuery: String,
    /**
     * Caminho relativo do reinício do equipamento (`REBOOT_DEVICE`, issues #95/#103) —
     * `admin/system?form=reboot`. Assumido por analogia com a convenção `admin/<seção>?form=<ação>`
     * já confirmada ao vivo para as demais leituras deste profile (`admin/status`,
     * `admin/onemesh_network`, `admin/security_settings`, `admin/diag`) — **sem confirmação por
     * evidência ao vivo própria** (nenhuma tentativa de reboot real foi feita contra o hardware
     * físico; a regressão de sessão HTTP 403 documentada em `fingerprintEvidence[]`/issue #125
     * bloqueia qualquer validação ao vivo deste driver no momento). Ver
     * [TpLinkStokLuciDriverFamily.executeAction].
     */
    val rebootPath: String,
    /** Query string fixa anexada a [rebootPath], ex.: `"form=reboot"`. */
    val rebootQuery: String,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Desserializa `profile.driverConfig` (`JsonElement` opaco no catálogo) no schema
         * concreto desta Driver Family. Lança se o profile resolvido para
         * `tplink-stok-luci-driver` não tiver um `driverConfig` no formato esperado — mesma
         * filosofia de falha alta e cedo de `TpLinkLegacyCgiDriverConfig`.
         */
        fun fromJsonElement(element: JsonElement): TpLinkStokLuciDriverConfig =
            json.decodeFromJsonElement(serializer(), element)
    }
}
