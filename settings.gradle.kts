pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "NetHAL"

include(":core:model")
include(":core:protocol")
include(":core:catalog")
include(":core:discovery")
include(":core:fingerprint")
include(":core:capability")
include(":core:auth")
include(":core:consent")
include(":core:telemetry")
include(":core:navigation")
include(":core:designsystem")
include(":drivers:nokia-gpon")
include(":drivers:tplink-legacy-cgi")
include(":drivers:tplink-stok-luci")
include(":drivers:tplink-experimental")
include(":app")
