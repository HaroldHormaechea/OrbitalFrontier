# Orbital Frontier

A single-player 2D top-down space RPG for Android: fly a ship, take on missions, fight, trade, and upgrade your way across a sector.

## What it does

Orbital Frontier is an offline, single-player Android game (working title). It is in **pre-alpha** (maturity target `mvp`, not a production release), but the full core loop is playable end-to-end:

- **Roam** — fly a ship with touch-friendly 2D top-down controls through sectors connected by fixed jump gates; sectors can be procedurally generated, with points of interest including stations, asteroid fields, junkyards, derelicts, distress calls, and hazards.
- **Earn** — take mining, courier, and combat/bounty missions from station boards and the ship radio; fight hostiles in real-time combat; mine asteroids, salvage loot from wrecks, and trade goods between stations at dynamic prices.
- **Improve** — spend credits on ship outfitting and upgrades, buy used parts at junkyards, own and switch between multiple ships, hire and manage crew, build reputation with factions, and construct your own stations. Some acquisitions are reputation-gated.
- **Persist** — progress is autosaved to an on-device SQLite database (via SQLDelight) with multiple save slots and slot-management UI.

Supporting UI is in place: main menu (Start/Continue), settings, pause overlay, game-over/ship-destruction screen, an expanded flight HUD and combat HUD, an in-game notification feed, a first-run tutorial, and accessibility options. The bottom-right action cluster is a functional control surface.

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
├── core/         Platform-agnostic game logic + rendering (Kotlin/JVM, libGDX)
├── android/      Android launcher module (manifest, resources, Activity)
├── assets/       Sprites + texture atlas, audio, fonts
├── docs/adr/     Architecture decision records
├── docs/design/  Internal design notes per game system
├── use-cases/    Formalized use-case files (and their implementation plans)
├── settings.gradle.kts
├── build.gradle.kts
└── PROJECT_BRIEF.md   Machine-readable project contract
```

## Documentation

- `PROJECT_BRIEF.md` — the project's source-of-truth contract (stack, paths, standards).
- `docs/design/` — internal design notes for each game system (index: `docs/design/README.md`).
- `docs/adr/` — Architecture Decision Records, the dated log of binding technical decisions (index: `docs/adr/README.md`).

## Known limitations

- **Pre-alpha.** Not validated across a wide device matrix; balance and many tuning
  values are provisional placeholders.
- **Art.** A committed design-system texture atlas (`assets/orbital.png` / `orbital.atlas`)
  serves as the current MVP art. Some newer systems and POIs reuse existing atlas regions
  rather than bespoke sprites (see ADR 0019 and ADR 0042); a higher-fidelity, bespoke art
  pass remains future work. The scalable game font and the UI skin are implemented.
- **Audio.** An audio system is present (ADR 0020), but the SFX and music are placeholder
  synthesised clips, not final assets.
- **Deferred systems.** Recorded in the ADRs but not yet built: combat shields; a power
  capacitor and reactor-upgrade category; crew skills, desertion, and wage debt; station
  defense, passive income, crew staffing, teardown, and respawn; generational save-backup
  history; and encounter/bounty content populating procedurally-generated sectors.
- **Non-goals.** No multiplayer or online play; no monetization (ads/IAP); no iOS, desktop,
  or web ports.
- The working title "Orbital Frontier" may change before a store release; the name space is crowded.

## License

Copyright © 2026 Harold Hormaechea. **All Rights Reserved.** This repository is
public for visibility only and is not open-source — no use, copying, modification,
or distribution is permitted without express written permission. See [LICENSE](LICENSE).
