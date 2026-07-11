package com.nethal.core.driver

import kotlinx.coroutines.delay
import java.net.ConnectException
import java.net.SocketTimeoutException

/**
 * Motivo de falha de **rede/comunicação** genérico, comum a qualquer Driver Family — parte
 * compartilhada do vocabulário que hoje se repetia (com nomes de enum trocados) em
 * `TplinkDriverFailureReason`/`TplinkC20DriverFailureReason`/`NokiaDriverFailureReason`.
 *
 * Não inclui motivo específico de protocolo (ex.: `INVALID_CREDENTIALS`, `SESSION_IN_USE`): esse
 * vocabulário continua vivendo no enum de falha de cada driver, porque cada protocolo decide de
 * forma diferente o que é fast-fail vs. candidato a retry.
 */
enum class NetworkFailureReason {
    DEVICE_UNREACHABLE,
    TIMEOUT,
    UNEXPECTED_RESPONSE,
    COMMUNICATION_ERROR,
}

/**
 * Classificação genérica de um [Throwable] de rede — substitui a `classifyFailure` quase idêntica
 * dos três drivers para os casos comuns (conexão recusada, timeout). Cada driver ainda pode
 * empilhar checagens específicas de protocolo por cima (ex.: TP-Link C6 e Nokia reconhecem
 * substrings de resposta inesperada do próprio handshake) antes de cair no genérico abaixo.
 */
fun classifyNetworkFailure(error: Throwable): NetworkFailureReason = when {
    error is ConnectException -> NetworkFailureReason.DEVICE_UNREACHABLE
    error is SocketTimeoutException -> NetworkFailureReason.TIMEOUT
    error.message?.contains("timed out", ignoreCase = true) == true -> NetworkFailureReason.TIMEOUT
    error.message?.contains("refused", ignoreCase = true) == true -> NetworkFailureReason.DEVICE_UNREACHABLE
    else -> NetworkFailureReason.COMMUNICATION_ERROR
}

/**
 * Resultado de uma execução com retry.
 *
 * [Failure] carrega tanto o [reason] já classificado no vocabulário específico do driver chamador
 * quanto o [error] original (para extrair mensagem) — assim o driver não precisa reclassificar a
 * exceção depois de receber o outcome, evitando duplicar o mesmo `when` duas vezes.
 */
sealed interface RetryOutcome<out T, out R> {
    data class Success<T>(val value: T) : RetryOutcome<T, Nothing>
    data class Failure<R>(val reason: R, val error: Throwable) : RetryOutcome<Nothing, R>
}

/**
 * Executa [attempt] até [maxAttempts] vezes, com espera [backoffMillis] antes de cada tentativa
 * além da primeira — mesmo laço `repeat(maxAttempts) { ... delay(backoffMillis(attemptIndex)) ... }`
 * hoje triplicado em `TplinkOntDriver`/`TplinkC20OntDriver`/`NokiaOntDriver`.
 *
 * [onLoginFailure] deixa o chamador decidir, para sua própria exceção de login (vocabulário de
 * motivo específico de cada protocolo), se a falha é fast-fail — devolvendo o motivo (`R`) já
 * classificado, o que interrompe o laço imediatamente sem gastar tentativas — ou candidata a
 * retry — devolvendo `null`, que faz esta função tratar a exceção como qualquer outro [Throwable]
 * (guarda o erro e segue para a próxima tentativa). Qualquer exceção que não seja a de login do
 * chamador é sempre candidata a retry, igual ao comportamento original dos três drivers.
 *
 * [classifyFinalFailure] classifica o erro final (após esgotar as tentativas, ou uma exceção de
 * login que [onLoginFailure] não tratou como fast-fail) no mesmo vocabulário `R` do driver
 * chamador — tipicamente delegando a [classifyNetworkFailure] mais checagens específicas de
 * protocolo.
 */
suspend fun <T, E : Throwable, R> executeWithRetry(
    maxAttempts: Int,
    backoffMillis: (attempt: Int) -> Long,
    loginExceptionType: Class<E>,
    onLoginFailure: (E) -> R?,
    classifyFinalFailure: (Throwable) -> R,
    attempt: suspend () -> T,
): RetryOutcome<T, R> {
    var lastError: Throwable? = null

    repeat(maxAttempts) { attemptIndex ->
        if (attemptIndex > 0) delay(backoffMillis(attemptIndex))
        try {
            return RetryOutcome.Success(attempt())
        } catch (t: Throwable) {
            if (loginExceptionType.isInstance(t)) {
                @Suppress("UNCHECKED_CAST")
                val fastFailReason = onLoginFailure(t as E)
                if (fastFailReason != null) return RetryOutcome.Failure(fastFailReason, t)
            }
            lastError = t
        }
    }

    val finalError = lastError ?: IllegalStateException("falha desconhecida apos $maxAttempts tentativas")
    return RetryOutcome.Failure(classifyFinalFailure(finalError), finalError)
}
