package com.nethal.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TelemetryReasonCodeTest {

    @Test
    fun `motivo nulo ou em branco vira UNKNOWN`() {
        assertEquals(TelemetryReasonCode.UNKNOWN, TelemetryReasonCode.classify(null))
        assertEquals(TelemetryReasonCode.UNKNOWN, TelemetryReasonCode.classify(""))
        assertEquals(TelemetryReasonCode.UNKNOWN, TelemetryReasonCode.classify("   "))
    }

    @Test
    fun `classifica motivos conhecidos em codigos fechados`() {
        assertEquals(TelemetryReasonCode.SESSION_EXPIRED, TelemetryReasonCode.classify("sessão expirada, faça login novamente"))
        assertEquals(TelemetryReasonCode.AUTH_INVALID, TelemetryReasonCode.classify("credencial inválida"))
        assertEquals(TelemetryReasonCode.AUTH_REQUIRED, TelemetryReasonCode.classify("requer autenticação"))
        assertEquals(TelemetryReasonCode.TIMEOUT, TelemetryReasonCode.classify("read timed out após 5000ms"))
        assertEquals(TelemetryReasonCode.CONNECTION_REFUSED, TelemetryReasonCode.classify("Connection refused"))
        assertEquals(TelemetryReasonCode.UNSUPPORTED_CAPABILITY, TelemetryReasonCode.classify("capability não suportada por este driver"))
        assertEquals(TelemetryReasonCode.PARSE_ERROR, TelemetryReasonCode.classify("falha ao fazer parse do JSON de resposta"))
    }

    @Test
    fun `motivo desconhecido cai em UNKNOWN em vez de vazar texto bruto`() {
        val rawWithIp = "IOException: falha ao conectar em 192.168.15.1: rede inalcançável"

        val result = TelemetryReasonCode.classify(rawWithIp)

        assertEquals(TelemetryReasonCode.UNKNOWN, result)
    }

    @Test
    fun `classify nunca devolve o texto de entrada - contrato de tipo fechado`() {
        val inputs = listOf(
            "IOException: falha ao conectar em 192.168.15.1",
            "hostname roteador-sala.local não respondeu",
            "SSID CasaLuiz_5G não encontrado",
        )

        inputs.forEach { raw ->
            val code = TelemetryReasonCode.classify(raw)
            // o retorno é sempre um membro do enum fechado (garantido pelo tipo de retorno);
            // a asserção relevante é que o valor nunca é literalmente o texto de entrada.
            assertNotEquals(raw, code.name)
        }
    }
}
