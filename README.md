# Orbital Frontier

An approachable 2D top-down space RPG for Android: fly a single ship, take on missions, and upgrade your way across a sector.

## What it does

Orbital Frontier is a single-player mobile game (working title). The planned MVP core loop is:

- Control a single ship with touch-friendly 2D top-down movement.
- Accept and complete missions for rewards.
- Spend rewards on an upgrade/progression tree that changes how the ship plays.
- Roam a sector with points of interest and encounters.
- Save and restore progress across app restarts.

The first vertical slice is implemented (use-case 01): you can **fly a single ship around an empty sector** on Android. The left virtual joystick rotates the hull toward a target direction and thrusts (push opposite to reverse); momentum carries and decays to a stop on release; a parallax starfield and a follow-camera convey motion; a HUD shows speed and heading; an inert right-side action cluster is shown; and a handedness setting swaps the control sides and persists across restarts (SQLite via SQLDelight). Missions, upgrades, and the rest of the loop are not built yet — see Known limitations.

## Requirements

- Android Studio with the Android SDK (compileSdk 35, minSdk 24).
- JDK 17+ (the Gradle wrapper pins Gradle 8.10).
- Kotlin 2.0.21, Android Gradle Plugin 8.6.1.
- libGDX 1.13.1 (with Box2D) and SQLDelight 2.0.2 — wired in the build.

## Quick start

The build is wired and the Gradle wrapper is committed, so a fresh clone builds with the
standard commands (point the SDK location at your Android SDK via `ANDROID_HOME` or
`local.properties`):

- Build the debug APK: `./gradlew :android:assembleDebug`
  (output: `android/build/outputs/apk/debug/`)
- Unit tests: `./gradlew test`
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

- Only the first slice exists: a flyable ship in an empty sector. No missions, economy, upgrades, combat, fuel, other entities, or jump gates yet.
- The ship and on-screen controls use programmatic placeholder graphics (no art assets yet); the action cluster is a non-functional placeholder.
- Movement parameters are placeholder `[TUNE]` defaults, to be tuned on-device.
- Engine choice (libGDX) is confirmed — see `docs/adr/0001-engine-choice.md`. The Box2D/movement split is recorded in `docs/adr/0005-movement-integration.md`.
- Non-goals for the MVP: no multiplayer, no monetization, no iOS/desktop/web ports, and no space-station building (a deferred stretch feature).
- The working title "Orbital Frontier" may change before a store release; the name space is crowded.

## License

Copyright © 2026 Harold Hormaechea. **All Rights Reserved.** This repository is
public for visibility only and is not open-source — no use, copying, modification,
or distribution is permitted without express written permission. See [LICENSE](LICENSE).
