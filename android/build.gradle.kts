// android module — Android application launcher for Orbital Frontier.
// Wires the libGDX Android backend + natives and depends on :core for all game logic.

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jlleitschuh.gradle.ktlint")
}

val gdxVersion = "1.13.1"
val sqlDelightVersion = "2.0.2"

// Release version is overridable from CI (the android-release workflow derives it from the
// git tag); falls back to the committed defaults for local/debug builds.
val versionNameProp = (project.findProperty("orbitalfrontierVersionName") as String?) ?: "0.1.0"
val versionCodeProp = (project.findProperty("orbitalfrontierVersionCode") as String?)?.toIntOrNull() ?: 1

// Release signing is driven entirely by environment variables supplied by CI secrets
// (see .github/workflows/android-release.yml). When they are absent (local dev, debug,
// CI debug builds) the release type stays unsigned rather than failing the build.
val keystoreFile: String? = System.getenv("KEYSTORE_FILE")
val hasReleaseSigning = !keystoreFile.isNullOrBlank()

// libGDX ships its JNI .so files inside *plain* jars (classifier natives-<abi>). AGP only
// auto-extracts .so from AARs, not jars, so we pull them through a dedicated `natives`
// configuration and unpack them into a generated jniLibs dir (see copyAndroidNatives below).
val natives: Configuration by configurations.creating

android {
    namespace = "com.orbitalfrontier"
    compileSdk = 35

    // UC25: generate BuildConfig so the debug-only point-and-go navigation aid can gate on
    // BuildConfig.DEBUG (AGP 8+ defaults this off). DEBUG is true only for the debug variant, so
    // the release build compiles the feature out entirely.
    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.orbitalfrontier"
        minSdk = 24
        targetSdk = 35
        versionCode = versionCodeProp
        versionName = versionNameProp
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = file(keystoreFile!!)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Only attach the signing config when CI supplied a keystore; otherwise the
            // release variant builds unsigned (useful for local inspection).
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
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

    // UC27: the libGDX game assets (the design-system art atlas + page PNG) live in the repo-root
    // ./assets/ folder — the conventional libGDX asset root, shared with future desktop/iOS backends —
    // which is NOT one of AGP's default asset source dirs. Register it so orbital.atlas / orbital.png are
    // packaged into the APK and Gdx.files.internal("orbital.atlas") resolves on device (AC#1). The
    // androidResources.noCompress list above already keeps .atlas/.png uncompressed.
    sourceSets["main"].assets.srcDir(rootProject.file("assets"))
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

    // UC52: the framework SupportSQLiteOpenHelper factory, so AndroidSqlDriverFactory can build the
    // open-helper itself and disable WAL (journal_mode=DELETE) before opening. It is already on the
    // runtime classpath transitively via android-driver; declared here so it is visible at compile time.
    implementation("androidx.sqlite:sqlite-framework:2.4.0")
}
