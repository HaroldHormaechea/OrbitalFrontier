// core module — platform-agnostic game logic + rendering (Kotlin/JVM).
// MUST NOT depend on the Android SDK so its logic stays JVM-unit-testable (ADR 0001).
// Persistence schema/queries are authored as SQLDelight `.sq` files (ADR 0003); the
// SqlDriver is injected per platform (Android on device, JDBC in JVM tests).

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("app.cash.sqldelight")
    id("org.jlleitschuh.gradle.ktlint")
}

val gdxVersion = "1.13.1"
val sqlDelightVersion = "2.0.2"

// kotlinx.serialization powers the Playthrough JSON codec (ADR 0006). The playthrough
// record/replay harness is test infrastructure and lives in the test source set, so the
// dependency is test-only. The serialization plugin (applied above) processes the test sources.
val kotlinxSerializationVersion = "1.7.3"

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
            // version. verifyMigrations checks the .sqm chain against the schema baseline
            // (the committed databases/<version>.db file produced by the generate-schema task).
            schemaOutputDirectory.set(file("src/main/sqldelight/databases"))
            verifyMigrations.set(true)
        }
    }
}

ktlint {
    // SQLDelight adds its generated sources to the Kotlin source set; don't lint generated code.
    filter {
        exclude { element -> element.file.path.contains("generated") }
    }
}

dependencies {
    implementation("com.badlogicgames.gdx:gdx:$gdxVersion")
    implementation("com.badlogicgames.gdx:gdx-box2d:$gdxVersion")
    implementation("app.cash.sqldelight:runtime:$sqlDelightVersion")

    testImplementation("junit:junit:4.13.2")
    // Test-only: powers the Playthrough JSON codec used by the record/replay harness (ADR 0006).
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinxSerializationVersion")
    // JVM-side SQLDelight driver for persistence round-trip tests (ADR 0003).
    testImplementation("app.cash.sqldelight:sqlite-driver:$sqlDelightVersion")
    testImplementation("org.xerial:sqlite-jdbc:3.46.1.3")
}

tasks.withType<Test>().configureEach {
    useJUnit()
    // Gradle does NOT forward command-line `-D` system properties to the forked test JVM, so the
    // playtest harness (which selects a playthrough via `-Dplaythrough.name=…`) would otherwise see
    // null and silently skip via JUnit Assume. Forward the harness's known props explicitly.
    listOf("playthrough.name", "fixture.regen").forEach { key ->
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
}
