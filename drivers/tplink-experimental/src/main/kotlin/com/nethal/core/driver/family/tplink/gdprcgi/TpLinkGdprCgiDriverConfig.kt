package com.nethal.core.driver.family.tplink.gdprcgi

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Uma seção de leitura autenticada por capability, no mesmo dialeto de dispatcher clássico `/cgi`
 * (`[oid#stack]indice,qtdCampos` + linhas `campo=valor`) confirmado ao vivo para `tplink-legacy-cgi`
 * (ver `TpLinkLegacyCgiResponseParser`) — reaproveitado aqui porque o corpo de login
 * `C50_GDPR_BODY_LOGIN` (`TpLinkGdprCgiAuthenticationClient.buildC50LoginPlaintext`) usa
 * literalmente a mesma gramática `[oid#stack]indice,qtd\r\ncampo\r\n...`, só cifrada em vez de
 * enviada em texto puro — é o mesmo dispatcher CGI clássico da TP-Link, com um envelope RSA+AES por
 * cima. `oid`/`fields` desta seção são inferência disclosed, **não confirmada contra hardware real
 * desta família**: reaproveitam os mesmos nomes de seção/campo já validados ao vivo para
 * `tplink-legacy-cgi` (`LAN_WLAN`→`name`/`SSID`, `LAN_HOST_ENTRY`→`hostName`/`IPAddress`/
 * `MACAddress`), sob a premissa de que o catálogo de `oid`s do firmware TP-Link é compartilhado
 * entre variantes de autenticação (mesma base de firmware/SDK interno) — premissa plausível, nunca
 * verificada para GDPR-CGI especificamente. Por isso todo resultado de capability lido a partir
 * daqui nunca sobe além de `EXPERIMENTAL` (ver `TpLinkGdprCgiDriverFamily.capabilityResultFor`).
 */
@Serializable
internal data class TpLinkGdprCgiCapabilitySection(
    /** Nome literal de [com.nethal.core.model.CapabilityId] (ex.: `"READ_WIFI_STATUS"`). */
    val capabilityId: String,
    val oid: String,
    val fields: List<String>,
)

@Serializable
internal data class TpLinkGdprCgiDriverConfig(
    val rsaKeyPath: String,
    val loginPath: String,
    val loginStyle: TpLinkGdprCgiLoginStyle,
    val cryptoMode: TpLinkGdprCgiCryptoMode,
    val rsaPaddingMode: TpLinkGdprCgiRsaPaddingMode,
    val tokenPath: String = "/",
    val authenticatedReadPath: String,
    val authenticatedReadPlaintext: String,
    /**
     * Seções de leitura por capability, config-driven (nunca hardcoded no código da Driver Family —
     * mesma regra de `TpLinkLegacyCgiDriverConfig`). Vazio por padrão para não quebrar profiles/
     * fixtures de teste existentes que só usam [authenticatedReadPlaintext] (o `readRaw()` bruto já
     * existente antes desta rodada). Só faz sentido popular para profiles com [loginStyle]
     * `C50_GDPR_BODY_LOGIN` — ver KDoc de [TpLinkGdprCgiCapabilitySection] para o porquê do escopo
     * restrito a esse estilo (é o único com gramática de leitura documentada/inferível).
     */
    val capabilitySections: List<TpLinkGdprCgiCapabilitySection> = emptyList(),
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun fromJsonElement(element: JsonElement): TpLinkGdprCgiDriverConfig =
            json.decodeFromJsonElement(serializer(), element)
    }
}
