plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.nethal.feature.toolsspeedtest"
    compileSdk = 35

    defaultConfig {
        minSdk = 26

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    // Regra de dependência única da ADR 0002: só :core:*, exceto :feature:tools-common
    // (componente reutilizável "Recurso indisponível", nunca lógica de produto) — mesma exceção já
    // documentada para os demais módulos :feature:tools-*.
    implementation(project(":core:model"))
    implementation(project(":core:navigation"))
    implementation(project(":core:designsystem"))
    implementation(project(":feature:tools-common"))

    // Motor de speedtest (issue #98) — decisão registrada no PR: OkHttp em vez de transporte de
    // streaming próprio sobre HttpURLConnection. Escopado só a este módulo, não vira dependência de
    // :core:protocol (que é deliberadamente restrito a HttpURLConnection + guard de IP privado,
    // pensado para falar só com equipamento de LAN — ver HttpTransportIpGuard — e não serve para
    // medir throughput por streaming contra um endpoint de internet).
    implementation(libs.okhttp)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.test.manifest)
}
