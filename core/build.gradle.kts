// core module — platform-agnostic game logic + rendering (Kotlin/JVM).
// Placeholder: dependency coordinates are finalized in the IDE. The core module
// must NOT depend on the Android SDK so its logic stays JVM-unit-testable.
//
// Expected dependencies (CONFIRM):
//   implementation("com.badlogicgames.gdx:gdx:1.13.1")          // libGDX core
//   implementation("com.badlogicgames.gdx:gdx-box2d:1.13.1")    // optional physics
//   testImplementation("junit:junit:4.13.2")                    // JVM unit tests

plugins {
    // id("org.jetbrains.kotlin.jvm")
}

// dependencies { ... }  // added when the toolchain is wired up in the IDE
