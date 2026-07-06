plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Diagnostico manual do driver Nokia contra hardware real (SIG-333) - nunca roda em CI/test,
// so quando o usuario dispara explicitamente. Ver ManualCheckRunner.kt para o porque de a
// senha ser sempre interativa, nunca argumento de linha de comando.
tasks.register<JavaExec>("nokiaManualCheck") {
    group = "verification"
    description = "Diagnostico manual contra um Nokia G-1425G-B real na LAN (SIG-333). Uso: gradlew :core:nokiaManualCheck --args=\"<ip> <usuario>\""
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.nethal.core.driver.nokia.ManualCheckRunnerKt")
    standardInput = System.`in`
}

// Diagnostico manual do driver TP-Link contra hardware real - nunca roda em CI/test, so quando o
// usuario dispara explicitamente. Ver ManualCheckRunner.kt (pacote tplink) para o porque de a
// senha ser sempre interativa, nunca argumento de linha de comando.
tasks.register<JavaExec>("tplinkManualCheck") {
    group = "verification"
    description = "Diagnostico manual contra um TP-Link Archer C6 real na LAN. Uso: gradlew :core:tplinkManualCheck --args=\"<ip> <usuario> [cbc|gcm]\""
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.nethal.core.driver.tplink.ManualCheckRunnerKt")
    standardInput = System.`in`
}

// Diagnostico manual do driver TP-Link Archer C20 contra hardware real - nunca roda em CI/test,
// so quando o usuario dispara explicitamente. Mecanismo de login e especulativo para este modelo
// (ver TplinkC20AuthenticationClient) - falha aqui e esperada e informativa.
tasks.register<JavaExec>("tplinkC20ManualCheck") {
    group = "verification"
    description = "Diagnostico manual contra um TP-Link Archer C20 real na LAN. Uso: gradlew :core:tplinkC20ManualCheck --args=\"<ip> <usuario>\""
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.nethal.core.driver.tplink.ManualCheckRunnerC20Kt")
    standardInput = System.`in`
}
