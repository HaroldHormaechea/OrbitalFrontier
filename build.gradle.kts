// Root build file — declares plugin versions for the modules, applied per-module.
// Versions mirror PROJECT_BRIEF.md frontmatter (stack.versions).
//   - Kotlin 2.0.21
//   - Android Gradle Plugin 8.6.1 (compileSdk 35)
//   - Gradle 8.10 (see gradle/wrapper/gradle-wrapper.properties)
//   - libGDX 1.13.1
//   - SQLDelight 2.0.2 (persistence access layer — ADR 0003)
//   - ktlint Gradle plugin 12.1.1 (lint/format)

plugins {
    id("com.android.application") version "8.6.1" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.jvm") version "2.0.21" apply false
    id("app.cash.sqldelight") version "2.0.2" apply false
    id("org.jlleitschuh.gradle.ktlint") version "12.1.1" apply false
}

allprojects {
    group = "com.orbitalfrontier"
    version = "0.1.0-SNAPSHOT"
}
