# Orbital Frontier

An approachable 2D top-down space RPG for Android: fly a single ship, take on missions, and upgrade your way across a sector.

## What it does

Orbital Frontier is a single-player mobile game (working title). The planned MVP core loop is:

- Control a single ship with touch-friendly 2D top-down movement.
- Accept and complete missions for rewards.
- Spend rewards on an upgrade/progression tree that changes how the ship plays.
- Roam a sector with points of interest and encounters.
- Save and restore progress across app restarts.

This repository is currently a structural scaffold. Game logic, rendering, and missions are stubs — see Known limitations.

## Requirements

- Android Studio with the Android SDK (compileSdk 35, minSdk 24).
- JDK 17+ and Gradle (the project targets Gradle 8.10; use the wrapper once generated).
- Kotlin 2.0.x.
- Game framework: libGDX 1.13.x (added when the build files are completed in the IDE).

Exact plugin and dependency versions are placeholders in the `*.gradle.kts` files and should be confirmed when the project is first opened in Android Studio.

## Quick start

This is an early scaffold: the Gradle build files are placeholders and the wrapper is not yet generated, so there is no runnable `./gradlew` command against a fresh clone yet. To get to a buildable state:

1. Clone the repository.
2. Open the folder in Android Studio and let it sync; complete the placeholder `build.gradle.kts` files (plugins, libGDX dependencies) following the inline comments and `PROJECT_BRIEF.md`.
3. Generate the Gradle wrapper, then the intended commands are:
   - Tests: `./gradlew test`
   - Lint: `./gradlew lint`
   - Format: `./gradlew ktlintFormat`

## Project layout

```
.
├── core/        Platform-agnostic game logic + rendering (Kotlin/JVM, libGDX)
├── android/     Android launcher module (manifest, resources, Activity)
├── assets/      Sprites, tilemaps, audio, fonts
├── docs/adr/    Architecture decision records
├── use-cases/   Formalized use-case files
├── settings.gradle.kts
├── build.gradle.kts
└── PROJECT_BRIEF.md   Machine-readable project contract
```

## Known limitations

- Scaffold only: no playable game yet. `OrbitalFrontierGame` and `AndroidLauncher` are stubs with no engine wired up.
- Build files are placeholders; no Gradle wrapper, so the project does not build or run as-cloned.
- No tests yet.
- Engine choice (libGDX) is recommended but pending owner confirmation — see `docs/adr/0001-engine-choice.md`.
- Non-goals for the MVP: no multiplayer, no monetization, no iOS/desktop/web ports, and no space-station building (a deferred stretch feature).
- The working title "Orbital Frontier" may change before a store release; the name space is crowded.

## License

Copyright © 2026 Harold Hormaechea. **All Rights Reserved.** This repository is
public for visibility only and is not open-source — no use, copying, modification,
or distribution is permitted without express written permission. See [LICENSE](LICENSE).
