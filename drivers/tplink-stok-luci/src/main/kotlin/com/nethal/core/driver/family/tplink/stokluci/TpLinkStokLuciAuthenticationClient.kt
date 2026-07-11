package com.nethal.core.driver.family.tplink.stokluci

import com.nethal.core.auth.AuthenticationStrategy
import com.nethal.core.protocol.http.HttpTransport
import java.io.IOException

/**
 * Motivo de falha de login da plataforma `tplink-stok-luci`. Corrigido a partir de evidência ao
 * vivo real (ver KDoc de [TpLinkStokLuciAuthenticationClient]) — o vocabulário em si (nomes de
 * motivo) não mudou desde a implementação anterior, só o mecanismo que dispara cada um.
 */
internal enum class TpLinkStokLuciLoginFailureReason {
    AUTH_ENDPOINT_UNAVAILABLE,
    INVALID_CREDENTIALS,
    UNEXPECTED_RESPONSE,

    /**
     * Sinaliza que uma chamada autenticada ([TpLinkStokLuciAuthenticationClient.fetchAuthenticated])
     * pós-login falhou com HTTP 401/403 — heurística conservadora para "sessão/token `stok` expirou",
     * distinta de [INVALID_CREDENTIALS] (que hoje só é usado durante o próprio [login]). Sem
     * confirmação por evidência ao vivo de como o firmware sinaliza expiração real de sessão (ainda
     * não foi capturado um `stok` expirando contra o hardware do Luiz) — mesmo código HTTP usado por
     * `login()` para credencial inválida, reaproveitado aqui porque é o único sinal observável
     * disponível. Diego: confirmar contra hardware real quando possível (ver KDoc de
     * [TpLinkStokLuciAuthenticationClient.fetchAuthenticated]).
     */
    SESSION_EXPIRED,
}

internal class TpLinkStokLuciLoginException(
    val reason: TpLinkStokLuciLoginFailureReason,
    message: String,
) : IOException(message)

/**
 * Sessão autenticada contra a WebUI de equipamentos da plataforma `tplink-stok-luci` (hoje só o
 * profile `tplink_archer_c6_stok_v1`).
 *
 * **Protocolo real confirmado por evidência ao vivo definitiva** (terceira rodada, 2026-07-07)
 * contra o hardware físico do Luiz (Archer C6 v2.0, firmware
 * `1.1.10 Build 20230830 rel.69433(5553)`): captura via Playwright, com `page.on('response')`
 * interceptando o corpo completo de request E response de cada chamada `cgi-bin/luci` durante um
 * login real bem-sucedido — inclusive chamadas autenticadas pós-login, com `stok` real
 * funcionando.
 *
 * **Correção sobre a rodada anterior** (manifesto `catalog-2026.07.17.json`): aquela rodada tinha
 * concluído, por engano, que existia **uma única** chamada de preparação (`form=auth`) com uma
 * única chave RSA reaproveitada para cifrar a senha e assinar o envelope `sign`. Essa conclusão
 * veio de uma captura **incompleta** feita com a extensão Chrome, que pulou a chamada `form=keys`
 * por algum motivo de cache/estado do navegador naquela tentativa específica — não porque o
 * protocolo real só tem uma chamada. A captura completa via Playwright desta rodada confirma que
 * **existem sim duas chamadas de preparação com duas chaves RSA distintas**, exatamente como a lib
 * de referência `tplinkrouterc6u` sempre documentou.
 *
 * **Correção adicional sobre a geração de chave/IV AES (quarta rodada, 2026-07-07)**: mesmo com as
 * duas chaves RSA corrigidas, o login real ainda falhava com `INVALID_CREDENTIALS`/HTTP 403. Uma
 * ferramenta externa de captura (não Claude Code) interceptou o texto puro exato do campo `sign`
 * antes de cifrar, durante um login real bem-sucedido contra a mesma unidade/firmware, revelando
 * que a chave/IV AES **não são bytes binários aleatórios hex-encodados** (como as três rodadas
 * anteriores assumiam) — são strings de 16 dígitos decimais ASCII usadas diretamente como os 16
 * bytes da chave/IV (variante `EncryptionWrapperMR` da lib de referência `tplinkrouterc6u`). Ver
 * KDoc de [TpLinkStokLuciCrypto] para o valor exato capturado e o detalhe completo.
 *
 * Passos do login real confirmado:
 *
 * 1. `POST {host}/cgi-bin/luci/;stok=/login?form=keys`, corpo `operation=read`. Resposta real:
 *    `{"success":true,"data":{"password":["<256 caracteres hex>","010001"],"mode":"router",
 *    "username":""}}` — `data.password` é a chave RSA (módulo 256 caracteres hex = 128 bytes = RSA
 *    1024-bit) usada **só para cifrar a senha**.
 * 2. `POST {host}/cgi-bin/luci/;stok=/login?form=auth`, corpo `operation=read`. Resposta real:
 *    `{"success":true,"data":{"key":["<128 caracteres hex>","010001"],"seq":<número>}}` —
 *    `data.key` é uma chave RSA **diferente** da do passo 1 (módulo 128 caracteres hex = 64 bytes =
 *    RSA 512-bit), usada **só para assinar o envelope `sign`**. `data.seq` é o número de sequência.
 * 3. Gera por sessão de login duas strings de 16 dígitos decimais ASCII
 *    ([TpLinkStokLuciCrypto.generateAesKeyOrIvDigits]) — uma para a chave AES-128, outra para o IV.
 *    **Correção confirmada por captura byte a byte externa (quarta rodada, 2026-07-07)**: essas
 *    strings decimais são usadas **diretamente como os 16 bytes UTF-8/ASCII** da chave/IV — nunca
 *    bytes binários aleatórios convertidos para hex depois (variante `EncryptionWrapperMR` da lib de
 *    referência `tplinkrouterc6u`, distinta da `EncryptionWrapper` genérica assumida nas rodadas
 *    anteriores). Ver KDoc de [TpLinkStokLuciCrypto] para o texto exato capturado que confirmou isso.
 * 4. Cifra a senha em RSA (chave do passo 1, PKCS1v1.5) e converte o resultado para hex.
 *    `data` = [TpLinkStokLuciCrypto.buildLoginPlaintext] (`operation=login&password=<senha cifrada
 *    em RSA, em hex>`, sem `&confirm=true` — confirmado byte a byte por captura real do texto plano
 *    via hook em `CryptoJS.AES.encrypt`; ver KDoc de [TpLinkStokLuciCrypto]), cifrado com
 *    AES-CBC/PKCS7 usando a chave/IV (bytes ASCII das strings decimais) gerados no passo 3,
 *    resultado em base64.
 * 5. `sign` = [TpLinkStokLuciCrypto.buildSignPlaintext] (`k=<string decimal da chave AES>&i=<string
 *    decimal do IV AES>&h=<hash>&s=<seq + comprimento_base64_do_data>`, hash =
 *    `md5(username+password)` — ambos confirmados pelo JS real do firmware e pela captura real do
 *    login do Luiz), cifrado em pedaços com a chave RSA do passo 2 (512-bit, PKCS1v1.5, chunk de
 *    53 bytes = 64 - 11 bytes de overhead), resultado em hex.
 * 6. `POST {host}/cgi-bin/luci/;stok=/login?form=login`, corpo `sign=<hex>&data=<base64
 *    URL-encoded>` — confirmado batendo com um HAR real de outra sessão de login também
 *    bem-sucedida (mesmo par de campos `sign`/`data`, nunca `operation=login&password=...`).
 * 7. Resposta real: `{"data": "<base64>"}` — sem campo `success` visível. O corpo é decifrado com a
 *    mesma chave/IV AES da sessão (passo 3); o texto plano resultante é um JSON contendo `stok`.
 * 8. Cookie `sysauth`: não capturamos headers de resposta reais nesta rodada (só corpo) — mantemos
 *    compatibilidade lendo o cookie se vier (`Set-Cookie`), mas a sessão NÃO depende só dele: o
 *    `stok` extraído do corpo decifrado é suficiente para autenticar chamadas subsequentes.
 *
 * (Existem outras chamadas reais no meio do fluxo capturado — `form=check_factory_default`,
 * `form=password` (`{"enable_rec":false}`), `locale?form=multilang`, `domain_login?form=dlogin` —
 * todas irrelevantes para o handshake de cripto, são checks de UI/cloud/idioma. Não precisam ser
 * replicadas por este client.)
 *
 * A credencial nunca é retida além da chamada de login: `password` só existe como parâmetro local
 * de [login], nunca vira campo desta classe nem é logada. Só o [TpLinkStokLuciSession] resultante
 * (token `stok` + cookie `sysauth` opcional, nenhum segredo por si só) fica em memória.
 *
 * Com o `h=` confirmado como `md5(username+password)`, o envelope de login desta plataforma fica
 * completamente alinhado com a captura real e com a variante `EncryptionWrapperMR` da lib de
 * referência.
 *
 * **Correção de `seq` como contador monotônico por sessão (issue #125, 2026-07-11)**: teste real
 * isolado a um único login (Luiz, `tplinkC6StokManualCheck` contra o Archer C6 físico) confirmou
 * login sempre bem-sucedido seguido de HTTP 403 ("sessão/token stok provavelmente expirado") em
 * TODA leitura autenticada seguinte — inclusive a primeira, inclusive `admin/status?form=all`, já
 * validado em 2026-07-07. Causa raiz encontrada por leitura de código, não por nova captura ao vivo:
 * o `seq` devolvido por `form=auth` era guardado como valor fixo (`val`) e reusado sem alteração
 * tanto no `sign` do próprio `form=login` quanto em TODA chamada de [fetchAuthenticatedRaw] da mesma
 * sessão. A lib de referência `tplinkrouterc6u` (`EncryptionWrapperMR`, já citada para `k=`/`i=`/`h=`
 * acima) trata `seq` como contador monotônico: a cada `sign` assinado, `s=` enviado
 * (`seq + tamanho_base64_do_data`) vira o novo piso da PRÓXIMA assinatura da sessão. Sem esse avanço,
 * o `sign` do login continua válido (é a chamada que estabelece o piso), mas toda chamada seguinte
 * assina com um `s=` que já ficou obsoleto no instante em que o login terminou — 403 em 100% das
 * leituras, exatamente o padrão relatado. Ver KDoc de `SessionEncryptorContext`/[fetchAuthenticatedRaw]
 * para o mecanismo exato da correção. **Ainda sem confirmação por evidência ao vivo** (o hardware do
 * Luiz não foi testado de novo com esta correção durante esta rodada) — é uma correção de leitura de
 * protocolo contra a própria lib de referência que orienta todo este arquivo, não uma suposição nova;
 * confirmação real fica para o próximo `tplinkC6StokManualCheck` do Luiz.
 */
internal class TpLinkStokLuciAuthenticationClient(
    private val host: String,
    private val transport: HttpTransport,
    private val rsaChunkSizeBytes: Int = TpLinkStokLuciCrypto.DEFAULT_RSA_CHUNK_SIZE_BYTES,
) : AuthenticationStrategy<TpLinkStokLuciSession> {

    /**
     * `seq` é **mutável de propósito** — causa raiz da issue #125 (login sempre bem-sucedido, toda
     * leitura autenticada seguinte falha com 403 "sessão/token stok provavelmente expirado", mesmo a
     * primeira, mesmo numa sessão nova). O valor devolvido por `form=auth` (`parsedAuthKeys.seq`) só
     * é o piso correto para a PRIMEIRA assinatura `sign` que o firmware processa de fato — a do
     * próprio `POST .../login?form=login`. A partir daí, `s=` (`seq + tamanho_base64_do_data`) que o
     * client acabou de enviar e o firmware acabou de aceitar vira o novo piso esperado na PRÓXIMA
     * assinatura da mesma sessão: contador monotônico por sessão, mesmo comportamento documentado
     * pela lib de referência `tplinkrouterc6u` (`EncryptionWrapperMR`, já citada em todo este arquivo
     * como fonte da correção do resto do protocolo — nenhuma nova suposição, é a MESMA lib que já
     * orientou a correção de `k=`/`i=`/`h=`).
     *
     * A implementação anterior a esta correção guardava `seq` como `val`, fixo no valor bruto de
     * `form=auth`, e reusava esse mesmo valor tanto para computar o `s=` do login quanto para o `s=`
     * de TODA leitura autenticada posterior na mesma sessão — inclusive a primeira. Isso explica
     * exatamente o padrão relatado: o login em si estabelece o piso (é a chamada que o firmware usa
     * pra fixar o próximo valor esperado), então "funciona" mesmo com esse bug; toda chamada seguinte
     * assina com um `s=` que já ficou pra trás no exato instante em que o login terminou — 403 em
     * 100% das leituras, mesmo a primeira, mesmo em uma sessão recém-aberta.
     */
    private data class SessionEncryptorContext(
        val aesKey: ByteArray,
        val aesIv: ByteArray,
        val signHash: String,
        val signKey: TpLinkStokLuciRsaKey,
        var seq: Long,
    )

    private val baseUrl = "http://$host"
    private val loginBaseUrl = "$baseUrl/cgi-bin/luci/;stok=/login"

    private var session: TpLinkStokLuciSession? = null
    private var encryptorContext: SessionEncryptorContext? = null

    val isAuthenticated: Boolean get() = session != null

    /** Chave RSA de cifra de senha (1024-bit), obtida em `form=keys`. Guardada para uso futuro por chamadas autenticadas (etapa fora de escopo desta entrega). */
    var passwordKey: TpLinkStokLuciPasswordKey? = null
        private set

    /** Chave RSA de assinatura (512-bit) + sequência, obtidas em `form=auth`. Guardadas para uso futuro por chamadas autenticadas (etapa fora de escopo desta entrega). */
    var authKeys: TpLinkStokLuciAuthKeys? = null
        private set

    @Throws(IOException::class)
    override fun login(username: String, password: String): TpLinkStokLuciSession {
        val keysResponse = transport.post("$loginBaseUrl?form=keys", "operation=read")
        if (keysResponse.statusCode != 200) {
            throw TpLinkStokLuciLoginException(
                TpLinkStokLuciLoginFailureReason.AUTH_ENDPOINT_UNAVAILABLE,
                "endpoint form=keys indisponivel: status=${keysResponse.statusCode}",
            )
        }
        val parsedPasswordKey = TpLinkStokLuciResponseParser.parsePasswordKey(keysResponse.body)
            ?: throw TpLinkStokLuciLoginException(
                TpLinkStokLuciLoginFailureReason.UNEXPECTED_RESPONSE,
                "resposta de form=keys sem chave RSA de senha reconhecivel",
            )
        passwordKey = parsedPasswordKey

        val authResponse = transport.post("$loginBaseUrl?form=auth", "operation=read")
        if (authResponse.statusCode != 200) {
            throw TpLinkStokLuciLoginException(
                TpLinkStokLuciLoginFailureReason.AUTH_ENDPOINT_UNAVAILABLE,
                "endpoint form=auth indisponivel: status=${authResponse.statusCode}",
            )
        }
        val parsedAuthKeys = TpLinkStokLuciResponseParser.parseAuthKeys(authResponse.body)
            ?: throw TpLinkStokLuciLoginException(
                TpLinkStokLuciLoginFailureReason.UNEXPECTED_RESPONSE,
                "resposta de form=auth sem chave RSA/seq reconheciveis",
            )
        authKeys = parsedAuthKeys

        // Chave/IV AES-128 desta sessão de login: strings de 16 dígitos decimais ASCII, usadas
        // diretamente (bytes UTF-8) como SecretKeySpec/IvParameterSpec E como os campos k=/i= do
        // texto do sign — nunca bytes binários aleatórios hex-encodados (variante `EncryptionWrapperMR`,
        // confirmada por captura byte a byte externa contra o hardware real; ver KDoc de [TpLinkStokLuciCrypto]).
        val aesKeyDigits = TpLinkStokLuciCrypto.generateAesKeyOrIvDigits()
        val aesIvDigits = TpLinkStokLuciCrypto.generateAesKeyOrIvDigits()
        val aesKey = aesKeyDigits.toByteArray(Charsets.US_ASCII)
        val aesIv = aesIvDigits.toByteArray(Charsets.US_ASCII)

        val rsaEncryptedPasswordHex = TpLinkStokLuciCrypto.rsaEncryptChunkedToHex(
            modulusHex = parsedPasswordKey.key.modulusHex,
            exponentHex = parsedPasswordKey.key.exponentHex,
            plaintext = password,
            chunkSizeBytes = rsaChunkSizeBytes,
        )
        val loginPlaintext = TpLinkStokLuciCrypto.buildLoginPlaintext(rsaEncryptedPasswordHex)
        val encryptedData = TpLinkStokLuciCrypto.aesCbcEncrypt(aesKey, aesIv, loginPlaintext.toByteArray(Charsets.UTF_8))
        val dataBase64 = TpLinkStokLuciCrypto.base64Encode(encryptedData)

        val signPlaintext = TpLinkStokLuciCrypto.buildSignPlaintext(
            aesKeyDigits = aesKeyDigits,
            aesIvDigits = aesIvDigits,
            username = username,
            password = password,
            seq = parsedAuthKeys.seq,
            encryptedDataBase64Length = dataBase64.length,
        )
        val signHex = TpLinkStokLuciCrypto.rsaEncryptChunkedToHex(
            modulusHex = parsedAuthKeys.key.modulusHex,
            exponentHex = parsedAuthKeys.key.exponentHex,
            plaintext = signPlaintext,
            chunkSizeBytes = rsaChunkSizeBytes,
        )

        val encodedData = java.net.URLEncoder.encode(dataBase64, "UTF-8")
        val loginBody = "sign=$signHex&data=$encodedData"
        val loginResponse = transport.post("$loginBaseUrl?form=login", loginBody)

        if (loginResponse.statusCode == 401 || loginResponse.statusCode == 403) {
            throw TpLinkStokLuciLoginException(
                TpLinkStokLuciLoginFailureReason.INVALID_CREDENTIALS,
                "login falhou: status=${loginResponse.statusCode}",
            )
        }
        if (loginResponse.statusCode != 200) {
            throw TpLinkStokLuciLoginException(
                TpLinkStokLuciLoginFailureReason.UNEXPECTED_RESPONSE,
                "login falhou: status=${loginResponse.statusCode}",
            )
        }

        val ciphertextBase64 = TpLinkStokLuciResponseParser.parseLoginCiphertextBase64(loginResponse.body)
            ?: throw TpLinkStokLuciLoginException(
                TpLinkStokLuciLoginFailureReason.INVALID_CREDENTIALS,
                "login falhou: resposta sem campo data (credencial provavelmente invalida)",
            )

        val decryptedLoginJson = runCatching {
            val decryptedBytes = TpLinkStokLuciCrypto.aesCbcDecrypt(aesKey, aesIv, TpLinkStokLuciCrypto.base64Decode(ciphertextBase64))
            String(decryptedBytes, Charsets.UTF_8)
        }.getOrNull()
        val stok = decryptedLoginJson?.let(TpLinkStokLuciResponseParser::parseDecryptedStok)

        val sysauthCookie = TpLinkStokLuciCrypto.extractSysauthCookie(loginResponse.headers["set-cookie"])
            ?: loginResponse.cookies["sysauth"]

        if (stok.isNullOrBlank()) {
            val decryptedEnvelope = decryptedLoginJson?.let(TpLinkStokLuciResponseParser::parseDecryptedLoginEnvelope)
            if (decryptedEnvelope?.success == false && decryptedEnvelope.errorCode == "login failed") {
                val failureCount = decryptedEnvelope.data?.failureCount
                val attemptsAllowed = decryptedEnvelope.data?.attemptsAllowed
                val suffix = buildString {
                    if (failureCount != null || attemptsAllowed != null) {
                        append(" (failureCount=")
                        append(failureCount?.toString() ?: "?")
                        append(", attemptsAllowed=")
                        append(attemptsAllowed?.toString() ?: "?")
                        append(')')
                    }
                }
                throw TpLinkStokLuciLoginException(
                    TpLinkStokLuciLoginFailureReason.INVALID_CREDENTIALS,
                    "login falhou: firmware devolveu errorcode=login failed$suffix",
                )
            }
            throw TpLinkStokLuciLoginException(
                TpLinkStokLuciLoginFailureReason.INVALID_CREDENTIALS,
                "login falhou: nao foi possivel decifrar stok da resposta (credencial provavelmente invalida)",
            )
        }

        val newSession = TpLinkStokLuciSession(stok = stok, sysauthCookie = sysauthCookie)
        session = newSession
        encryptorContext = SessionEncryptorContext(
            aesKey = aesKey.copyOf(),
            aesIv = aesIv.copyOf(),
            signHash = TpLinkStokLuciCrypto.md5Hex(username + password),
            signKey = parsedAuthKeys.key,
            // Bug da issue #125: o piso inicial é o `s=` que ESTE login acabou de enviar e o
            // firmware acabou de aceitar (`parsedAuthKeys.seq + dataBase64.length`), não o `seq` cru
            // de `form=auth` — ver KDoc de [SessionEncryptorContext].
            seq = parsedAuthKeys.seq + dataBase64.length,
        )
        return newSession
    }

    override fun authenticatedHeaders(session: TpLinkStokLuciSession): Map<String, String> =
        session.sysauthCookie?.let { mapOf("sysauth" to it) } ?: emptyMap()

    /**
     * Faz uma chamada de dados autenticada contra `{host}/cgi-bin/luci/;stok=<token>/<path>`,
     * reenviando o cookie `sysauth` se presente e usando o envelope autenticado real desta
     * plataforma (`sign` + `data`, com reuso da chave/IV AES e da chave RSA de assinatura da
     * sessão). Hoje cobre só leitura simples do endpoint de status validado ao vivo; o parsing
     * estruturado dos campos continua fora de escopo desta entrega.
     *
     * HTTP 401/403 aqui é tratado como [TpLinkStokLuciLoginFailureReason.SESSION_EXPIRED] — ver KDoc
     * do enum para o motivo dessa heurística ainda não ter confirmação por evidência ao vivo de
     * expiração real. Chamado repetidamente pelo mesmo [TpLinkStokLuciDriverFamily]/mesma sessão a
     * partir da issue #16 (Capability Engine com gerenciamento de sessão real): antes, cada leitura
     * fazia login novo, então uma sessão nunca vivia tempo suficiente para expirar entre chamadas.
     *
     * **Issue #125**: antes desta correção, o `s=` do `sign` era assinado sempre com o `seq` cru de
     * `form=auth`, nunca avançado — todo 403 aqui era na verdade dessincronia de contador, não sessão
     * expirada de verdade (o driver não tem hoje como distinguir os dois casos pelo HTTP status
     * sozinho, daí a heurística conservadora continuar mapeando para `SESSION_EXPIRED`). Corrigido
     * avançando [SessionEncryptorContext.seq] logo após montar cada `sign` (aqui e em [login]) — ver
     * KDoc de `SessionEncryptorContext`.
     */
    @Throws(IOException::class)
    fun fetchAuthenticated(path: String, query: String): String =
        fetchAuthenticatedRaw(path, extractRequestQuery(query), extractRequestPlaintext(query))

    /**
     * Variante de [fetchAuthenticated] que aceita o corpo de requisição em texto plano diretamente
     * ([bodyPlaintext]), em vez de derivá-lo de uma `query` combinada que só suporta `operation=`.
     * Necessária para o diagnóstico nativo de ping (issue #26, `admin/diag?form=diag`): o passo de
     * disparo (`operation=write`) carrega parâmetros adicionais (`type`, `ipaddr`, `count`,
     * `pktsize`, `timeout`, `ttl`) que [fetchAuthenticated] descarta de propósito (só extrai
     * `operation=` do corpo, mantendo o resto só na URL). Mesma criptografia/assinatura de sessão de
     * [fetchAuthenticated] — extraído para reuso, não duplicado.
     */
    @Throws(IOException::class)
    fun fetchAuthenticatedRaw(path: String, urlQuery: String, bodyPlaintext: String): String {
        val currentSession = session
        check(currentSession != null) { "fetchAuthenticatedRaw chamado antes de login() bem-sucedido" }
        val currentEncryptor = encryptorContext
        check(currentEncryptor != null) { "fetchAuthenticatedRaw chamado sem contexto criptografico de sessao" }

        val encryptedData = TpLinkStokLuciCrypto.aesCbcEncrypt(
            currentEncryptor.aesKey,
            currentEncryptor.aesIv,
            bodyPlaintext.toByteArray(Charsets.UTF_8),
        )
        val dataBase64 = TpLinkStokLuciCrypto.base64Encode(encryptedData)
        val signPlaintext = TpLinkStokLuciCrypto.buildAuthenticatedSignPlaintext(
            hash = currentEncryptor.signHash,
            seq = currentEncryptor.seq,
            encryptedDataBase64Length = dataBase64.length,
        )
        // Bug da issue #125: avança o piso do contador ANTES de enviar, para a PRÓXIMA chamada
        // assinada desta sessão (login ou fetchAuthenticatedRaw) já nascer sincronizada com o que o
        // firmware espera — nunca reenviar o `s=` que acabou de ser gasto. Ver KDoc de
        // [SessionEncryptorContext].
        currentEncryptor.seq += dataBase64.length
        val signHex = TpLinkStokLuciCrypto.rsaEncryptChunkedToHex(
            modulusHex = currentEncryptor.signKey.modulusHex,
            exponentHex = currentEncryptor.signKey.exponentHex,
            plaintext = signPlaintext,
            chunkSizeBytes = rsaChunkSizeBytes,
        )
        val encodedData = java.net.URLEncoder.encode(dataBase64, "UTF-8")
        val requestBody = "sign=$signHex&data=$encodedData"
        val url = "$baseUrl/cgi-bin/luci/;stok=${currentSession.stok}/$path?$urlQuery"
        val response = transport.post(url, requestBody, authenticatedHeaders(currentSession))
        if (response.statusCode == 401 || response.statusCode == 403) {
            throw TpLinkStokLuciLoginException(
                TpLinkStokLuciLoginFailureReason.SESSION_EXPIRED,
                "leitura autenticada falhou: status=${response.statusCode} (sessão/token stok provavelmente expirado)",
            )
        }
        if (response.statusCode != 200) {
            throw TpLinkStokLuciLoginException(
                TpLinkStokLuciLoginFailureReason.UNEXPECTED_RESPONSE,
                "leitura autenticada falhou: status=${response.statusCode}",
            )
        }
        val ciphertextBase64 = TpLinkStokLuciResponseParser.parseLoginCiphertextBase64(response.body)
            ?: throw TpLinkStokLuciLoginException(
                TpLinkStokLuciLoginFailureReason.UNEXPECTED_RESPONSE,
                "leitura autenticada falhou: resposta sem campo data cifrado",
            )
        val decryptedBytes = TpLinkStokLuciCrypto.aesCbcDecrypt(
            currentEncryptor.aesKey,
            currentEncryptor.aesIv,
            TpLinkStokLuciCrypto.base64Decode(ciphertextBase64),
        )
        return String(decryptedBytes, Charsets.UTF_8)
    }

    private fun extractRequestPlaintext(query: String): String {
        val operationValue = Regex("""(?:^|&)operation=([^&]+)""").find(query)?.groupValues?.get(1)
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
        return if (operationValue.isNullOrBlank()) "operation=read" else "operation=$operationValue"
    }

    private fun extractRequestQuery(query: String): String {
        val filtered = query
            .split('&')
            .filter { it.isNotBlank() && !it.startsWith("operation=") }
            .joinToString("&")
        return if (filtered.isBlank()) "form=all" else filtered
    }
}
