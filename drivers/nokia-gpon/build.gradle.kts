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
// Nome da task e forma de uso preservados da consolidacao anterior.
tasks.register<JavaExec>("nokiaManualCheck") {
    group = "verification"
    description = "Diagnostico manual contra um Nokia G-1425G-B real na LAN (SIG-333). Uso: gradlew :drivers:nokia-gpon:nokiaManualCheck --args=\"<ip> <usuario>\""
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.nethal.core.driver.nokia.tooling.NokiaManualCheckKt")
    standardInput = System.`in`
}
