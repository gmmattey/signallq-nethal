package com.nethal.core.driver.family.tplink.stokluci

import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Primitivas criptográficas do handshake de login da plataforma `tplink-stok-luci`, corrigidas a
 * partir de evidência ao vivo **definitiva** (terceira rodada, 2026-07-07): captura via Playwright,
 * com `page.on('response')` interceptando o corpo completo de request E response de cada chamada
 * `cgi-bin/luci` durante um login real bem-sucedido contra o hardware físico do Luiz (Archer C6
 * v2.0, firmware `1.1.10 Build 20230830 rel.69433(5553)`) — inclusive chamadas autenticadas
 * pós-login, com `stok` real funcionando.
 *
 * **Duas chaves RSA distintas, confirmadas por captura completa de request+response**:
 * - `form=keys` → `data.password = [modulus_hex, exponent_hex]`, módulo de 256 caracteres hex = 128
 *   bytes = **RSA 1024-bit**, usada só para cifrar a senha.
 * - `form=auth` → `data.key = [modulus_hex, exponent_hex]`, módulo de 128 caracteres hex = 64 bytes
 *   = **RSA 512-bit**, usada só para assinar o envelope `sign`. Expoente sempre `010001` (65537).
 *
 * Isso confirma exatamente o que a lib de referência `tplinkrouterc6u` sempre documentou. **A
 * rodada anterior (manifesto `catalog-2026.07.17.json`) concluiu, por engano, que só existia a
 * chamada `form=auth` e uma única chave RSA reaproveitada para senha e assinatura** — essa
 * conclusão veio de uma captura incompleta feita com a extensão Chrome, que pulou `form=keys` por
 * algum motivo de cache/estado do navegador naquela tentativa específica, não porque o protocolo
 * real só tem uma chamada. Este objeto foi corrigido para repor a chamada a `form=keys` e usar as
 * duas chaves corretamente.
 *
 * O tamanho de bloco do RSA em pedaços (chunking) do envelope `sign` é derivado diretamente do
 * tamanho real da chave de **assinatura** (`form=auth`, 512-bit = 64 bytes): PKCS1v1.5 tem 11 bytes
 * de overhead, logo `64 - 11 = 53 bytes` por bloco — o valor de [DEFAULT_RSA_CHUNK_SIZE_BYTES] já
 * usado nesta implementação bate exatamente com essa conta, confirmando que não é um valor
 * arbitrário.
 *
 * Confirmado por leitura estrutural (rodada anterior) do arquivo
 * `webpages/js/libs/tpEncrypt.1693386897767.js` real do equipamento: `CBC` presente, `GCM`/`ECB`
 * ausentes → **AES-CBC**, nunca GCM. `pkcs7` presente (case-insensitive) → padding **PKCS7**
 * (equivalente a PKCS5 para blocos de 16 bytes). `MD5` presente → hash de assinatura usa MD5.
 * `CryptoJS` presente → biblioteca de cifra client-side usada pelo firmware.
 *
 * **Confirmado byte a byte** (interceptação real da função `CryptoJS.AES.encrypt` na própria página
 * do equipamento, hook instalado via JavaScript, durante um login real bem-sucedido pelo
 * navegador): o texto plano que entra no AES para virar o campo `data` é exatamente
 * `operation=login&password=<256 caracteres hex>` — **sem** `&confirm=true` no final. Os 256
 * caracteres hex são a senha **já cifrada em RSA** com a chave de `form=keys` (1024-bit) — nunca a
 * chave de `form=auth` (512-bit), que é usada só para o envelope `sign`.
 *
 * **Geração de chave/IV AES — variante decimal confirmada por captura byte a byte externa
 * (quarta rodada, 2026-07-07)**: uma ferramenta externa de captura (não Claude Code) interceptou o
 * texto puro exato do campo `sign` antes de cifrar, durante um login real bem-sucedido contra a
 * mesma unidade física/firmware. Texto capturado (senha nunca aparece em claro, só via hash MD5):
 * `k=5945270769887026&i=3257785177414969&h=f6fdffe48c908deb0f4c3bd36c032e72&s=855135262`.
 *
 * Isso confirma que este firmware usa a variante `EncryptionWrapperMR` da lib de referência
 * `tplinkrouterc6u` — **distinta** da `EncryptionWrapper` genérica que orientou as rodadas
 * anteriores desta implementação: a chave/IV AES **não são bytes binários aleatórios
 * hex-encodados**. São strings de exatamente 16 caracteres decimais ASCII (`k=5945270769887026`,
 * `i=3257785177414969` — 16 caracteres cada, só dígitos `0-9`), usadas **diretamente como os 16
 * bytes UTF-8/ASCII** da chave AES-128 e do IV — nunca decodificadas de hex, nunca geradas como
 * `SecureRandom.nextBytes(ByteArray(16))`. Ou seja: o que aparece em `k=`/`i=` no envelope `sign`
 * já É a chave/IV byte a byte (cada caractere decimal = 1 byte ASCII), não uma representação hex de
 * outra sequência de bytes.
 *
 * Consequência prática: [generateAesKeyOrIvDigits] gera essas strings de 16 dígitos decimais, e
 * tanto o campo `k=`/`i=` do texto do `sign` quanto a `SecretKeySpec`/`IvParameterSpec` usados para
 * cifrar de fato o campo `data` devem vir da mesma string — nunca de bytes aleatórios binários
 * convertidos para hex depois.
 *
 * O JS real do firmware calcula `s=` como `seq + encryptedData.length`, onde `encryptedData` é a
 * string Base64 devolvida por `CryptoJS.AES.encrypt(...).toString()` antes de qualquer
 * URL-encoding. Ou seja: o que entra na conta é o **comprimento da string Base64** do campo
 * `data`, não o tamanho do ciphertext bruto em bytes.
 *
 * **Hash MD5 do campo `sign` — fórmula confirmada por correlação exata contra um login real**:
 * `h=f6fdffe48c908deb0f4c3bd36c032e72` do exemplo capturado bate exatamente com
 * `md5(username + password)` usando o valor de usuário fixo/interno deste firmware — a convenção
 * já documentada pela lib de referência `tplinkrouterc6u`. Isso fecha a última lacuna que ainda
 * restava no envelope `sign`: apesar de o firmware não exibir um campo de usuário no formulário
 * HTML, o hash do `sign` continua incluindo um `username` interno fixo (não exposto na UI, não
 * fornecido pelo usuário) — nunca vazio. A credencial real usada para essa correlação não é
 * reproduzida aqui (nunca persistir senha de equipamento em código versionado, ver `SECURITY.md`).
 */
internal object TpLinkStokLuciCrypto {

    /**
     * Tamanho de bloco (bytes) usado para cifrar o envelope `sign` em pedaços com RSA PKCS1v1.5 —
     * derivado do tamanho real da chave de assinatura de `form=auth` (RSA 512-bit = 64 bytes),
     * confirmado por captura completa via Playwright: `64 - 11` bytes de overhead de padding
     * PKCS1v1.5 = 53 bytes por bloco. Não é um valor arbitrário herdado da lib de referência sem
     * relação com este firmware — é exatamente o chunk size implícito no tamanho de chave real.
     */
    const val DEFAULT_RSA_CHUNK_SIZE_BYTES = 53

    /**
     * Tamanho de chave/IV AES (bytes) gerados por sessão de login — 16 bytes = 128 bits, **AES-128**
     * confirmado pelo hook real em `CryptoJS.AES.encrypt` (`keyWords: 4`; CryptoJS usa palavras de
     * 32 bits, logo 4 palavras = 16 bytes = 128 bits), nunca AES-256.
     */
    const val AES_KEY_SIZE_BYTES = 16
    const val AES_IV_SIZE_BYTES = 16

    /**
     * Quantidade de caracteres decimais da string usada como chave/IV AES — ver
     * [generateAesKeyOrIvDigits]. Coincide numericamente com [AES_KEY_SIZE_BYTES]/
     * [AES_IV_SIZE_BYTES] porque cada caractere decimal vira exatamente 1 byte ASCII quando usado
     * como chave/IV — não é coincidência, é a variante `EncryptionWrapperMR` confirmada por captura
     * byte a byte (ver KDoc do objeto).
     */
    const val AES_KEY_OR_IV_DIGIT_COUNT = 16

    fun buildRsaPublicKey(modulusHex: String, exponentHex: String): java.security.PublicKey {
        val modulus = BigInteger(modulusHex, 16)
        val exponent = BigInteger(exponentHex, 16)
        val keySpec = RSAPublicKeySpec(modulus, exponent)
        return KeyFactory.getInstance("RSA").generatePublic(keySpec)
    }

    /**
     * Cifra [plaintext] com RSA/ECB/PKCS1Padding em pedaços de [chunkSizeBytes], concatenando o
     * resultado de cada pedaço em hex minúsculo — necessário porque o envelope `sign` (chave/IV
     * AES + hash + seq, ver [buildSignPlaintext]) costuma exceder o limite de um único bloco
     * PKCS1v1.5 para chaves RSA 1024-bit. Tamanho de pedaço parametrizável porque o valor real
     * (53 bytes, herdado da lib de referência) não foi confirmado contra este firmware
     * especificamente.
     */
    fun rsaEncryptChunkedToHex(
        modulusHex: String,
        exponentHex: String,
        plaintext: String,
        chunkSizeBytes: Int = DEFAULT_RSA_CHUNK_SIZE_BYTES,
    ): String {
        val publicKey = buildRsaPublicKey(modulusHex, exponentHex)
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        val plaintextBytes = plaintext.toByteArray(Charsets.UTF_8)
        val hex = StringBuilder()
        var offset = 0
        while (offset < plaintextBytes.size) {
            val end = minOf(offset + chunkSizeBytes, plaintextBytes.size)
            cipher.init(Cipher.ENCRYPT_MODE, publicKey)
            val encryptedChunk = cipher.doFinal(plaintextBytes.copyOfRange(offset, end))
            encryptedChunk.forEach { hex.append("%02x".format(it)) }
            offset = end
        }
        return hex.toString()
    }

    /** Gera [size] bytes aleatórios seguros — uso genérico, não usado para chave/IV AES desta plataforma (ver [generateAesKeyOrIvDigits]). */
    fun generateRandomBytes(size: Int, random: SecureRandom = SecureRandom()): ByteArray =
        ByteArray(size).also { random.nextBytes(it) }

    /**
     * Gera uma string de [AES_KEY_OR_IV_DIGIT_COUNT] dígitos decimais ASCII (`0`-`9`) — usada
     * **diretamente** (bytes UTF-8/ASCII da própria string, sem hex-decodificar) como chave ou IV
     * AES-128 por sessão de login. Variante `EncryptionWrapperMR`, confirmada por captura byte a
     * byte externa contra o hardware real (ver KDoc do objeto): a chave/IV real observada não são
     * bytes binários aleatórios convertidos para hex — são strings decimais usadas literalmente
     * como bytes.
     *
     * Cada chamada gera dígitos independentes (chamar duas vezes para obter chave e IV distintos).
     */
    fun generateAesKeyOrIvDigits(random: SecureRandom = SecureRandom()): String =
        buildString(AES_KEY_OR_IV_DIGIT_COUNT) {
            repeat(AES_KEY_OR_IV_DIGIT_COUNT) { append(random.nextInt(10)) }
        }

    /**
     * Converte bytes para uma string hex minúscula de exatamente `bytes.size * 2` caracteres —
     * utilitário genérico usado, por exemplo, pelo fake de teste ([FakeTpLinkStokLuciHttpTransport])
     * para reconstruir a chave/IV a partir dos bytes decimais decifrados do `sign`. **Não** é mais o
     * formato usado nos campos `k=`/`i=` do envelope `sign` em si — esses campos usam a string
     * decimal de [generateAesKeyOrIvDigits] diretamente (ver KDoc do objeto).
     */
    fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun hexToBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { i ->
        hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }

    /** AES-CBC/PKCS7 (equivalente a PKCS5 no JCE para blocos de 16 bytes) — confirmado por leitura estrutural de `tpEncrypt.js` real do equipamento (contém `CBC` e `pkcs7`, não `GCM`/`ECB`). */
    fun aesCbcEncrypt(key: ByteArray, iv: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(plaintext)
    }

    fun aesCbcDecrypt(key: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(ciphertext)
    }

    fun base64Encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    fun base64Decode(value: String): ByteArray = Base64.getDecoder().decode(value)

    fun md5Hex(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Corpo em texto plano cifrado com AES-CBC/PKCS7 para virar o campo `data` do login —
     * `operation=login&password=<rsaEncryptedPasswordHex>`, **sem** `&confirm=true`. Confirmado
     * byte a byte por interceptação real de `CryptoJS.AES.encrypt` na própria página do equipamento
     * durante um login real bem-sucedido (ver KDoc do objeto). [rsaEncryptedPasswordHex] é a senha
     * já cifrada em RSA (hex) com a chave devolvida por `form=keys` (1024-bit) — nunca a chave de
     * `form=auth` (512-bit, usada só para o envelope `sign`), e nunca a senha em texto puro.
     */
    fun buildLoginPlaintext(rsaEncryptedPasswordHex: String): String =
        "operation=login&password=$rsaEncryptedPasswordHex"

    /**
     * Texto plano do envelope `sign`:
     * `k=<chave AES decimal>&i=<IV AES decimal>&h=<hash>&s=<seq + comprimento_base64_do_data>`.
     * O hash real confirmado contra o login bem-sucedido do Luiz é `md5(username + password)` —
     * ex.: `admin/admin` gera exatamente `f6fdffe48c908deb0f4c3bd36c032e72`, o mesmo `h=` da
     * captura byte a byte do equipamento.
     */
    fun buildSignPlaintext(
        aesKeyDigits: String,
        aesIvDigits: String,
        username: String,
        password: String,
        seq: Long,
        encryptedDataBase64Length: Int,
    ): String {
        val hash = md5Hex(username + password)
        return "k=$aesKeyDigits&i=$aesIvDigits&h=$hash&s=${seq + encryptedDataBase64Length}"
    }

    /**
     * Texto plano do envelope `sign` de uma chamada autenticada pós-login: `h=<hash>&s=<seq +
     * comprimento_base64_do_data>`. Diferente do login inicial, as chamadas autenticadas reais não
     * repetem `k=`/`i=` no `sign`; elas reutilizam a chave/IV AES da sessão já estabelecida.
     */
    fun buildAuthenticatedSignPlaintext(
        hash: String,
        seq: Long,
        encryptedDataBase64Length: Int,
    ): String = "h=$hash&s=${seq + encryptedDataBase64Length}"

    /**
     * Extrai o valor de `sysauth` do header `Set-Cookie` bruto, se presente. A evidência ao vivo
     * desta rodada não capturou headers de resposta (só corpo) — mantido por compatibilidade com o
     * mecanismo antigo, mas o login não deve depender só deste cookie (ver
     * [TpLinkStokLuciAuthenticationClient]).
     */
    fun extractSysauthCookie(setCookieHeader: String?): String? {
        if (setCookieHeader.isNullOrBlank()) return null
        return Regex("""sysauth=([^;]+)""").find(setCookieHeader)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
    }
}
