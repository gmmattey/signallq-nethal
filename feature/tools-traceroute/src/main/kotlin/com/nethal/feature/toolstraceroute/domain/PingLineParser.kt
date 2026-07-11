package com.nethal.feature.toolstraceroute.domain

/**
 * Parser puro (sem I/O, sem Android) da saída de UMA execução de `ping -c 1 -t <ttl>` — extraído à
 * parte de [com.nethal.feature.toolstraceroute.data.AndroidTracerouteEngine] só para poder ser
 * testado sem `ProcessBuilder`/dispositivo real (issue #102, critério "parsing de hop testado").
 *
 * Cobre os dois formatos de linha que um binário ping (toybox, no Android) produz para um IPv4:
 * - `From 192.168.1.1: icmp_seq=1 Time to live exceeded` — roteador intermediário rejeitando o
 *   pacote por TTL esgotado (isso é o mecanismo real de traceroute: nenhuma resposta chega do
 *   alvo, mas o roteador que descartou o pacote se identifica).
 * - `64 bytes from 8.8.8.8: icmp_seq=1 ttl=53 time=18.3 ms` — echo reply de verdade, quando o TTL
 *   enviado já é suficiente para alcançar o alvo.
 *
 * Não copiado do `TopologyTracer.kt` do SignallQ (reescrito, ver decisão de arquitetura no PR) —
 * mas a mesma heurística de regex (`From`/`from` seguido de IPv4) é reaproveitada porque é
 * exatamente o que os dois formatos acima têm em comum.
 */
object PingLineParser {

    private val ADDRESS_REGEX = Regex("""(?:From|from) ([0-9]{1,3}(?:\.[0-9]{1,3}){3})""")

    /** Devolve o IPv4 de quem respondeu, ou `null` se a saída não contém nenhuma das duas formas conhecidas — tratado como timeout por quem chama. */
    fun extractAddress(pingOutput: String): String? =
        ADDRESS_REGEX.find(pingOutput)?.groupValues?.get(1)
}
