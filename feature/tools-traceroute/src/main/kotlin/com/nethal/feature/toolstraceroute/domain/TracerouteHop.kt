package com.nethal.feature.toolstraceroute.domain

/**
 * Um hop do traceroute (issue #102), TTL crescente a partir de 1. Cada valor de [TracerouteHop]
 * corresponde a UMA tentativa de ping com aquele TTL específico — nunca representa o resultado
 * agregado do traceroute inteiro (isso é [TracerouteRun]).
 */
sealed interface TracerouteHop {

    val ttl: Int

    /**
     * O ping com este TTL recebeu resposta — de um roteador intermediário (`ICMP Time Exceeded`,
     * mensagem "From <ip>") ou do próprio alvo final (`echo reply`, [isTarget] = true).
     *
     * @param address IP de quem respondeu — nunca nulo neste estado (é exatamente o que diferencia
     * [Responded] de [TimedOut]: sem IP extraído da saída do ping, o hop é tratado como timeout,
     * nunca como "resposta parcial").
     * @param hostname resolução reversa (PTR) best-effort, só tentada para o hop que bate com o IP
     * do alvo (ver [AndroidTracerouteEngine] — evita estourar o tempo do traceroute inteiro
     * tentando PTR em cada roteador intermediário, que raramente tem PTR configurado).
     * @param rttMillis tempo decorrido entre o disparo do ping e o retorno do processo, medido pelo
     * próprio app (`System.nanoTime()`) — não depende de parsear o texto "time=X ms" da saída do
     * `ping`, que varia entre variantes de binário (toybox/busybox/iputils) conforme o fabricante
     * do Android. Menos preciso que o RTT reportado pelo próprio ICMP, mas portátil entre ROMs.
     * @param isTarget true quando [address] bate com o IP resolvido do alvo informado pelo usuário
     * — sinaliza para o [TracerouteEngine] parar de incrementar o TTL.
     */
    data class Responded(
        override val ttl: Int,
        val address: String,
        val hostname: String? = null,
        val rttMillis: Long,
        val isTarget: Boolean = false,
    ) : TracerouteHop

    /**
     * Nenhuma resposta dentro do timeout deste TTL — hop "esgotado" (`* * *` no protótipo `4d`).
     * Não interrompe o traceroute: hops intermediários sem resposta (firewall descartando ICMP,
     * por exemplo) são comuns e esperados: o loop de TTL continua normalmente para o próximo hop.
     */
    data class TimedOut(override val ttl: Int) : TracerouteHop
}

/** Resultado agregado de uma execução completa (ou interrompida) do traceroute. */
data class TracerouteRun(
    val target: String,
    val hops: List<TracerouteHop>,
    val reachedTarget: Boolean,
)
