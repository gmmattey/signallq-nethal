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
tasks.register<JavaExec>("tplinkC6StokManualCheck") {
    group = "verification"
    description = "Diagnostico manual contra um TP-Link Archer C6 real na LAN, plataforma stok/luci. Uso: gradlew :drivers:tplink-stok-luci:tplinkC6StokManualCheck --args=\"<ip> <usuario>\""
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.nethal.core.driver.family.tplink.stokluci.tooling.TpLinkStokLuciManualCheckKt")
    standardInput = System.`in`
}
