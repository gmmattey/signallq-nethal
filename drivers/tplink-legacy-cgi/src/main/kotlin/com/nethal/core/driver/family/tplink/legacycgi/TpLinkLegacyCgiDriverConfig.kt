package com.nethal.core.driver.family.tplink.legacycgi

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Schema concreto (opaco para o resto do catálogo, ver `hal-layering-model.md` §5.6/§11.1) do
 * `driverConfig` consumido por [TpLinkLegacyCgiDriverFamily] — só esta Driver Family sabe
 * interpretar este formato.
 *
 * Substitui os literais de seção/campo antes hardcoded em `TplinkC20OntDriver.readSnapshot()`
 * (`listOf("LAN_WLAN" to listOf("name", "SSID"))`, etc.) e em
 * `TplinkC20AuthenticationClient.LOGIN_VALIDATION_SECTIONS` — ambos vinham do dado de modelo
 * (seções/campos reais do dispatcher `/cgi`), não de lógica de protocolo, e por isso pertencem ao
 * Profile (`hal-layering-model.md` §3 item 6), não ao código da Driver Family.
 *
 * Um segundo profile no mesmo protocolo (ex.: Archer C50 V2) só precisa de um `driverConfig`
 * próprio com os nomes de seção/campo daquele modelo — zero Kotlin novo, conforme a regra de
 * evolução de `hal-layering-model.md` §9.
 */
@Serializable
data class TpLinkLegacyCgiSectionConfig(
    /** Nome do bloco no protocolo, ex.: `"IGD_DEV_INFO"`, `"LAN_WLAN"`, `"/cgi/info"`. */
    val section: String,
    /** Campos pedidos dentro do bloco, na ordem exata usada para montar o request. Vazio para blocos sem campo (ex.: `/cgi/info`). */
    val fields: List<String> = emptyList(),
)

/**
 * Um bundle é uma sequência ordenada de seções enviadas numa única requisição `POST /cgi` — a
 * ordem determina o índice posicional (`indice` do protocolo) usado depois para reencontrar cada
 * bloco na resposta (ver `TplinkC20ResponseParser`/`TpLinkLegacyCgiResponseParser`).
 */
@Serializable
data class TpLinkLegacyCgiBundleConfig(
    val sections: List<TpLinkLegacyCgiSectionConfig>,
)

/**
 * Configuração completa de um profile compatível com [TpLinkLegacyCgiDriverFamily].
 *
 * - `loginValidationBundle`: bundle único usado tanto para validar a credencial (primeira leitura
 *   real, sem endpoint de login dedicado neste protocolo) quanto para a leitura de device info —
 *   nunca devem divergir, é o único bundle com prova real de sucesso contra o hardware
 *   (equivalente a `TplinkC20AuthenticationClient.LOGIN_VALIDATION_SECTIONS` hoje).
 * - `deviceInfoIndex`/`ethSwitchIndex`/`sysModeIndex`: índice posicional de cada seção dentro de
 *   `loginValidationBundle.sections`, usado pelo parser para reencontrar o bloco certo na resposta.
 * - `wifiStatusBundle`/`connectedClientsBundle`: bundles independentes para as outras duas
 *   capabilities lidas nesta rodada (`READ_WIFI_STATUS`, `READ_CONNECTED_CLIENTS`).
 */
@Serializable
data class TpLinkLegacyCgiDriverConfig(
    val loginValidationBundle: TpLinkLegacyCgiBundleConfig,
    val deviceInfoIndex: Int,
    val ethSwitchIndex: Int,
    val sysModeIndex: Int,
    val wifiStatusBundle: TpLinkLegacyCgiBundleConfig,
    val wifiStatusIndex: Int,
    val connectedClientsBundle: TpLinkLegacyCgiBundleConfig,
    val connectedClientsIndex: Int,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Desserializa `profile.driverConfig` (`JsonElement` opaco no catálogo) no schema
         * concreto desta Driver Family. Lança se o profile resolvido para
         * `tplink-legacy-cgi-driver` não tiver um `driverConfig` no formato esperado — falha alta
         * e cedo, mesmo espírito de `UnknownDriverFamilyException`: um profile que declara este
         * `driverFamilyId` sem o `driverConfig` correspondente é catálogo publicado incorretamente,
         * não uma ausência esperada.
         */
        fun fromJsonElement(element: JsonElement): TpLinkLegacyCgiDriverConfig =
            json.decodeFromJsonElement(serializer(), element)
    }

    /** Seções + campos para validar a credencial e ler device info, na forma consumida por `buildRequestBody`. */
    fun loginValidationSections(): List<Pair<String, List<String>>> = loginValidationBundle.toPairs()

    fun wifiStatusSections(): List<Pair<String, List<String>>> = wifiStatusBundle.toPairs()

    fun connectedClientsSections(): List<Pair<String, List<String>>> = connectedClientsBundle.toPairs()
}

private fun TpLinkLegacyCgiBundleConfig.toPairs(): List<Pair<String, List<String>>> =
    sections.map { it.section to it.fields }
