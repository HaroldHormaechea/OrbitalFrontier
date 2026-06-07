# ADR 0001 — Game engine / framework choice

- **Status:** Accepted (confirmed by the project owner 2026-06-07)
- **Date:** 2026-06-07

## Context

Orbital Frontier is a 2D top-down space RPG for Android: a single player-controlled
ship, a mission system, and an upgrade/progression system, with a possible future
space-station-building feature. The owner asked the scaffolder to *advise* on the
engine rather than assume one. Constraints: must run on Android phones; code-driven
mission/upgrade systems benefit from testable game logic; a future cross-platform
port is desirable but not required.

## Options considered

| Option | Language | For this game | Against |
|---|---|---|---|
| **libGDX** | Kotlin/Java | Mature code-first 2D; tilemaps, sprites, Box2D physics; cross-platform later; JVM-testable logic; clean Gradle/JUnit fit. | No visual editor; build your own ECS/tooling. |
| Godot 4 | GDScript/C# | Free OSS editor, scene system, strong 2D, Android export. | Separate runtime; engine-specific scripting; weaker Gradle/JUnit fit. |
| Native Kotlin + Compose/Canvas | Kotlin | Pure Android-native, smallest footprint, easiest CI. | Hand-roll loop/rendering/physics; least game-oriented. |
| Unity | C# | Industry standard, huge ecosystem, best tooling. | Heavy runtime, licensing, C#/.NET outside Gradle; overkill. |
| Flutter + Flame | Dart | Single codebase, good UI tooling. | Smaller game ecosystem; less proven for action top-down. |

## Decision

**libGDX with Kotlin**, using the standard `core` + `android` multi-module layout.
It is the best fit for a code-driven 2D top-down RPG, keeps game logic on a
JVM/Gradle/JUnit toolchain (so the dev-team's `./gradlew test` flow and unit-testable
systems work out of the box), and preserves a future cross-platform port without
committing to a heavy engine.

## Consequences

- No visual editor; level/content tooling is code- and asset-pipeline-driven.
- `core` must avoid Android-SDK dependencies to stay JVM-testable.
- Reversible: this ADR can be superseded via `/revise-brief` (re-running
  `define-technologies`), but switching engines is a significant re-scaffold —
  confirm before building features.
