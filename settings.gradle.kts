// Orbital Frontier — Gradle settings.
// Multi-module libGDX layout: platform-agnostic `core` + Android launcher `android`.

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

rootProject.name = "OrbitalFrontier"

include(":core")
include(":android")
