package com.nethal.core.driver.family.tplink.gdprcgi

internal object TpLinkGdprCgiResponseParser {

    fun parseRsaParams(body: String): TpLinkGdprCgiRsaParams? {
        val exponent = Regex("""var\s+ee="([0-9a-fA-F]+)";""").find(body)?.groupValues?.get(1)
        val modulus = Regex("""var\s+nn="([0-9a-fA-F]+)";""").find(body)?.groupValues?.get(1)
        val sequence = Regex("""var\s+seq="(\d+)";""").find(body)?.groupValues?.get(1)?.toLongOrNull()
        if (exponent.isNullOrBlank() || modulus.isNullOrBlank() || sequence == null) return null
        return TpLinkGdprCgiRsaParams(modulusHex = modulus, exponentHex = exponent, sequence = sequence)
    }

    fun parseTokenId(body: String): String? =
        Regex("""var\s+token="([^"]+)";""").find(body)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

    fun parseReturnCode(body: String): Int? =
        Regex("""\$\.ret=(\d+);""").find(body)?.groupValues?.get(1)?.toIntOrNull()

    /**
     * Extrai todo par `campo=valor` de um corpo decifrado no dialeto de dispatcher clássico `/cgi`
     * (`[a,b,c,d,e,f]indice` seguido de linhas `campo=valor`, terminado em `[error]codigo` —
     * gramática idêntica à documentada em `TpLinkLegacyCgiResponseParser`, reimplementada aqui em
     * vez de importada porque esta Driver Family não depende de `tplink-legacy-cgi` — cada Driver
     * Family entende seu próprio protocolo, mesmo quando a gramática de baixo nível é compartilhada).
     *
     * Defensivo em toda a extensão: linhas entre colchetes (`[...]`) são marcadores de bloco/erro,
     * nunca dado — ignoradas. Corpo vazio ou sem nenhum `campo=valor` reconhecível devolve mapa
     * vazio, nunca lança. Como esta rodada só envia uma seção por chamada (ver
     * `TpLinkGdprCgiDriverFamily.buildStackReadPlaintext`), não há necessidade de agrupar por
     * índice — todo par encontrado pertence à única seção pedida.
     */
    fun parseStackFields(body: String): Map<String, String> {
        val fields = mutableMapOf<String, String>()
        for (rawLine in body.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("[")) continue
            val eqIndex = line.indexOf('=')
            if (eqIndex > 0) {
                fields[line.substring(0, eqIndex).trim()] = line.substring(eqIndex + 1).trim()
            }
        }
        return fields
    }

    /** `[error]0` = sucesso, mesmo vocabulário de `TpLinkLegacyCgiResponseParser.isSuccess`. */
    fun isStackSuccess(body: String): Boolean =
        Regex("""\[error](-?\d+)""").find(body)?.groupValues?.get(1)?.toIntOrNull() == 0
}
