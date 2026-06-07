// core module — platform-agnostic game logic + rendering (Kotlin/JVM).
// MUST NOT depend on the Android SDK so its logic stays JVM-unit-testable (ADR 0001).
// Persistence schema/queries are authored as SQLDelight `.sq` files (ADR 0003); the
// SqlDriver is injected per platform (Android on device, JDBC in JVM tests).

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("app.cash.sqldelight")
    id("org.jlleitschuh.gradle.ktlint")
}

val gdxVersion = "1.13.1"
val sqlDelightVersion = "2.0.2"

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

sqldelight {
    databases {
        create("OrbitalFrontier") {
            packageName.set("com.orbitalfrontier.save")
            // ADR 0002: sequential, version-by-version migrations with a stored schema
            // version. verifyMigrations checks the .sqm chain against the schema baseline.
            verifyMigrations.set(true)
        }
    }
}

dependencies {
    implementation("com.badlogicgames.gdx:gdx:$gdxVersion")
    implementation("com.badlogicgames.gdx:gdx-box2d:$gdxVersion")
    implementation("app.cash.sqldelight:runtime:$sqlDelightVersion")

    testImplementation("junit:junit:4.13.2")
    // JVM-side SQLDelight driver for persistence round-trip tests (ADR 0003).
    testImplementation("app.cash.sqldelight:sqlite-driver:$sqlDelightVersion")
    testImplementation("org.xerial:sqlite-jdbc:3.46.1.3")
}

tasks.withType<Test>().configureEach {
    useJUnit()
}
