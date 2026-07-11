package com.nethal.core.driver.family.tplink.gdprcgi

/**
 * Par de chaves RSA fixo só para teste do envelope `sign` do gdpr-cgi. Constantes idênticas à
 * fixture homônima do módulo `:drivers:tplink-stok-luci` — duplicada aqui de propósito na
 * modularização da ADR 0002 para manter os módulos de driver independentes (test fixture, sem
 * acoplamento entre drivers). Nunca é chave real de equipamento.
 */
internal object TestSignKeyFixture {
    const val MODULUS_HEX =
        "9358029ac47a6c869cb1cd8df032c1534a386671f381b2a22d04dccab93d01d" +
            "132ea812945464ff2838a8bc9fa10e1a206bdb842fe6b78aa6404a3d972fa0c8d"
    const val EXPONENT_HEX = "10001"
    const val PRIVATE_EXPONENT_HEX =
        "f46981a889532ac3011a5007aaf2068ecb0753a8a26dfa8bda71be6ee967b1a" +
            "1648e25607219b394b577fe63f55f105a95f2f0009f73f622e3fecddda26d3c7"
}
