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
include(":feature:tools-common")
include(":feature:tools-dns")
include(":feature:tools-traceroute")
include(":feature:wifi-network")
include(":feature:onboarding")
include(":feature:pairing-discovery")
include(":feature:pairing-auth")
include(":feature:status")
include(":drivers:nokia-gpon")
include(":drivers:tplink-legacy-cgi")
include(":drivers:tplink-stok-luci")
include(":drivers:tplink-experimental")
include(":feature:devices")
include(":feature:settings")
include(":app")
