// android module — Android application launcher for Orbital Frontier.
// Placeholder: AGP/Kotlin plugins and the libGDX Android backend are wired up
// in the IDE. Depends on :core for all game logic.
//
// Expected setup (CONFIRM):
//   plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
//   android {
//     namespace = "com.orbitalfrontier.android"
//     compileSdk = 35
//     defaultConfig { minSdk = 24; targetSdk = 35; applicationId = "com.orbitalfrontier" }
//   }
//   dependencies {
//     implementation(project(":core"))
//     implementation("com.badlogicgames.gdx:gdx-backend-android:1.13.1")
//     // plus gdx-platform natives (armeabi-v7a, arm64-v8a, x86, x86_64)
//   }

plugins {
    // id("com.android.application")
    // id("org.jetbrains.kotlin.android")
}

// android { ... }  // configured when the toolchain is wired up in the IDE
