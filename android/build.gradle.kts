// android module — Android application launcher for Orbital Frontier.
// Wires the libGDX Android backend + natives and depends on :core for all game logic.

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jlleitschuh.gradle.ktlint")
}

val gdxVersion = "1.13.1"
val sqlDelightVersion = "2.0.2"

// libGDX ships its JNI .so files inside *plain* jars (classifier natives-<abi>). AGP only
// auto-extracts .so from AARs, not jars, so we pull them through a dedicated `natives`
// configuration and unpack them into a generated jniLibs dir (see copyAndroidNatives below).
val natives: Configuration by configurations.creating

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

    // .so files are unpacked here by copyAndroidNatives (kept under build/, not committed).
    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("generated/jniLibs"))
}

// Extract each natives-<abi> jar's .so files into build/generated/jniLibs/<abi>/ so AGP packages
// them into the APK. Without this the libGDX/Box2D native libs are missing and the app crashes
// at launch with UnsatisfiedLinkError.
val copyAndroidNatives by tasks.registering {
    val outputRoot = layout.buildDirectory.dir("generated/jniLibs")
    outputs.dir(outputRoot)
    doLast {
        natives.resolvedConfiguration.resolvedArtifacts.forEach { artifact ->
            val abi = artifact.classifier?.removePrefix("natives-") ?: return@forEach
            copy {
                from(zipTree(artifact.file)) { include("*.so") }
                into(outputRoot.get().dir(abi))
            }
        }
    }
}

tasks.matching { it.name == "preBuild" || it.name.startsWith("merge") && it.name.contains("JniLibFolders") }
    .configureEach { dependsOn(copyAndroidNatives) }

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

    // libGDX core API (the launcher references com.badlogic.gdx.Game / Application directly).
    implementation("com.badlogicgames.gdx:gdx:$gdxVersion")
    implementation("com.badlogicgames.gdx:gdx-backend-android:$gdxVersion")

    // libGDX core natives (per-ABI .so, extracted into jniLibs by copyAndroidNatives).
    natives("com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-armeabi-v7a")
    natives("com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-arm64-v8a")
    natives("com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-x86")
    natives("com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-x86_64")

    // Box2D natives (movement integration — AC#10).
    natives("com.badlogicgames.gdx:gdx-box2d-platform:$gdxVersion:natives-armeabi-v7a")
    natives("com.badlogicgames.gdx:gdx-box2d-platform:$gdxVersion:natives-arm64-v8a")
    natives("com.badlogicgames.gdx:gdx-box2d-platform:$gdxVersion:natives-x86")
    natives("com.badlogicgames.gdx:gdx-box2d-platform:$gdxVersion:natives-x86_64")

    // On-device SQLite driver (ADR 0003).
    implementation("app.cash.sqldelight:android-driver:$sqlDelightVersion")
}
