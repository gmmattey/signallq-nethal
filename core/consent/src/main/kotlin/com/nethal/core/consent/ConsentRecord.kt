package com.nethal.core.consent

/**
 * Registro de consentimento por escopo. Persistido apenas localmente (ver DataStore no módulo app) —
 * nunca enviado a servidor, conforme princípio "sem senha armazenada / falha segura".
 */
data class ConsentRecord(
    val scope: ConsentScope,
    val granted: Boolean,
    val grantedAtEpochMillis: Long?,
)

/**
 * Snapshot de todos os consentimentos conhecidos no momento da consulta.
 * WRITE_CONFIGURATION e REBOOT_DEVICE só são perguntados na primeira vez que uma
 * ação daquele tipo for solicitada. Nota (issues #95/#103): `CapabilityEngine.executeAction`
 * (`:core:capability`) já executa `REBOOT_DEVICE` de verdade contra o driver TP-Link Archer C6, mas
 * nenhum chamador (`:feature:tools-reboot-wan`) consulta/popula este `ConsentState` ainda — a
 * confirmação exigida por `/seguranca-nethal` para essa ação hoje é o diálogo por-ação da própria
 * tela (`RebootConfirmationDialog`), não um escopo de consentimento amplo concedido uma única vez.
 * Unificar os dois mecanismos (consentimento de escopo vs. confirmação por ação) é decisão de
 * arquitetura em aberto, não resolvida por esta entrega.
 */
class ConsentState(private val records: Map<ConsentScope, ConsentRecord>) {

    fun isGranted(scope: ConsentScope): Boolean = records[scope]?.granted == true

    fun recordFor(scope: ConsentScope): ConsentRecord? = records[scope]

    companion object {
        fun empty(): ConsentState = ConsentState(emptyMap())
    }
}
