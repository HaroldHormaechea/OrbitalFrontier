// Root build file — placeholder.
// Shared plugin/version configuration is finalized when the project is first
// opened in Android Studio. Versions referenced here mirror PROJECT_BRIEF.md
// frontmatter (stack.versions) and should be confirmed before building.
//
// Suggested toolchain (CONFIRM in IDE):
//   - Kotlin 2.0.x
//   - Android Gradle Plugin (AGP) compatible with compileSdk 35
//   - Gradle 8.10
//   - libGDX 1.13.x
//   - ktlint Gradle plugin for lint/format

plugins {
    // Apply per-module in core/build.gradle.kts and android/build.gradle.kts.
}

allprojects {
    group = "com.orbitalfrontier"
    version = "0.1.0-SNAPSHOT"
}
