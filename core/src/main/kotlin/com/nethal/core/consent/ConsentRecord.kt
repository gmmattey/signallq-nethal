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
 * ação daquele tipo for solicitada — ainda não existe Command Executor nesta entrega,
 * então este modelo só é consultado, nunca populado por uma ação real de escrita.
 */
class ConsentState(private val records: Map<ConsentScope, ConsentRecord>) {

    fun isGranted(scope: ConsentScope): Boolean = records[scope]?.granted == true

    fun recordFor(scope: ConsentScope): ConsentRecord? = records[scope]

    companion object {
        fun empty(): ConsentState = ConsentState(emptyMap())
    }
}
