// android module — Android application launcher for Orbital Frontier.
// Wires the libGDX Android backend + natives and depends on :core for all game logic.

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jlleitschuh.gradle.ktlint")
}

val gdxVersion = "1.13.1"
val sqlDelightVersion = "2.0.2"

android {
    namespace = "com.orbitalfrontier"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.orbitalfrontier"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    // libGDX ships its native .so files inside the natives jars; do not compress them.
    androidResources {
        noCompress += listOf("png", "jpg", "atlas", "fnt")
    }

    packaging {
        // Avoid duplicate-file collisions from transitive libGDX/SQLDelight jars.
        resources.excludes += setOf("META-INF/robovm/ios/robovm.xml")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ktlint {
    // Skip generated sources (BuildConfig, R, etc.).
    filter {
        exclude { element -> element.file.path.contains("generated") }
    }
}

dependencies {
    implementation(project(":core"))

    implementation("com.badlogicgames.gdx:gdx-backend-android:$gdxVersion")

    // libGDX core natives (per-ABI .so files packaged into the APK).
    implementation("com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-armeabi-v7a")
    implementation("com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-arm64-v8a")
    implementation("com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-x86")
    implementation("com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-x86_64")

    // Box2D natives (movement integration — AC#10).
    implementation("com.badlogicgames.gdx:gdx-box2d-platform:$gdxVersion:natives-armeabi-v7a")
    implementation("com.badlogicgames.gdx:gdx-box2d-platform:$gdxVersion:natives-arm64-v8a")
    implementation("com.badlogicgames.gdx:gdx-box2d-platform:$gdxVersion:natives-x86")
    implementation("com.badlogicgames.gdx:gdx-box2d-platform:$gdxVersion:natives-x86_64")

    // On-device SQLite driver (ADR 0003).
    implementation("app.cash.sqldelight:android-driver:$sqlDelightVersion")
}
