plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":core:model"))
    api(project(":core:protocol"))
    api(project(":core:catalog"))
    implementation(project(":core:auth"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":core:capability"))
}

// Diagnostico manual contra hardware real na LAN (ADR 0002: cada driver carrega sua ferramenta).
// Cada task injeta o profileId correto via argumentProviders (anexado depois dos --args do usuario);
// nomes de task e forma de uso preservados da consolidacao anterior.
tasks.register<JavaExec>("tplinkManualCheck") {
    group = "verification"
    description = "Diagnostico manual contra um TP-Link Archer C6 real na LAN. Uso: gradlew :drivers:tplink-legacy-cgi:tplinkManualCheck --args=\"<ip> <usuario> [cbc|gcm]\""
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.nethal.core.driver.tplink.tooling.TpLinkLegacyManualCheckKt")
    argumentProviders.add(org.gradle.process.CommandLineArgumentProvider { listOf("tplink_archer_c6_v1") })
    standardInput = System.`in`
}

tasks.register<JavaExec>("tplinkC20ManualCheck") {
    group = "verification"
    description = "Diagnostico manual contra um TP-Link Archer C20 real na LAN. Uso: gradlew :drivers:tplink-legacy-cgi:tplinkC20ManualCheck --args=\"<ip> <usuario>\""
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.nethal.core.driver.tplink.tooling.TpLinkLegacyManualCheckKt")
    argumentProviders.add(org.gradle.process.CommandLineArgumentProvider { listOf("tplink_archer_c20_v1") })
    standardInput = System.`in`
}
