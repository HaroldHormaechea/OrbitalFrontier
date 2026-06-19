# ADR 0032 — Combat HUD feedback (targeting, health bars & hit feedback)

- **Status:** Accepted
- **Date:** 2026-06-19

## Context

UC44 adds the combat HUD the auto-aim fight (UC13, [ADR 0012](0012-real-time-combat.md)) has been
missing: a target-lock reticle on the hostile the turrets are engaging, enemy hull bars, and hit
feedback (flash/shake) for damage dealt and taken. The fight itself is already fully modelled and
**pure** — `Combat.step` is the single shared tick the device loop and the replay harness both call, and
`CombatState` is transient (NONE on load, [ADR 0012](0012-real-time-combat.md)). The binding constraints
are the usual ones: the combat model stays pure and **byte-identical on replay**
([ADR 0006](0006-determinism-and-playthrough-harness.md)), `core` logic stays JVM-testable
([ADR 0001](0001-engine-choice.md)), and the new overlay must compose with the always-on FIRE arc (UC26)
and the expanded HUD (UC34) **without overlap** (UC44 AC#4).

Three forces shaped the design:

1. **The reticle must be honest to the firing point.** A lock indicator that points somewhere other than
   where the turrets actually shoot is worse than none. Turret auto-fire selects its target via
   `TargetingPriority.selectTarget(playerPos, hostiles)` with `playerPos` read from the player's
   kinematics, gated on an operable, non-disabled TURRET.
2. **Reduced motion (UC39, [ADR 0027](0027-accessibility-options.md)) must gate the shake and flash** —
   and `MotionPreference` is a rendering-only global with a `reset()`, which is awkward to unit-test
   around.
3. **Nothing may touch the model or the save.** HUD feedback is a pure *read* of the combat state; it
   must not add persisted state or perturb the committed playthrough fixtures.

## Options considered

| Option | For | Against |
|---|---|---|
| **Pure derived HUD state + pure feedback holder + a world-space ShapeRenderer overlay; reduced-motion injected as a parameter** | Reticle reuses the exact `TargetingPriority` firing-point selection (parity by construction); the AC#5-testable state lives in engine-free classes (`CombatHudState`, `CombatFeedback`) unit-tested against the model; reduced-motion as a parameter keeps the pure tests free of global `reset()` teardown; world-space bars/reticle + a full-screen flash never enter the scene2d layer, so they cannot overlap the FIRE arc/HUD; read-only → no schema bump, replay byte-identical | Two new pure classes + one renderer + a source-anchored guard (PlayScreen has GL in its field inits → not headlessly constructible, so the wiring is pinned at the source level rather than driven from a unit test) |
| Compute lock/health/feedback inline in `PlayScreen` | Fewer files | The derivation would not be unit-testable (PlayScreen needs a GL context); risks the reticle drifting from the real firing point; mixes pure logic into a GL class |
| Particle systems for hit feedback | Richer effect | Heavier (texture/particle plumbing) for marginal MVP value; flash + shake already satisfy AC#3's "flash/particle/shake" menu |
| Draw bars/reticle as scene2d `Stage` actors | Reuses the existing Stage | Puts the overlay on the SAME screen-space layer as the FIRE arc/HUD, where it can drift into and overlap them (the exact AC#4 failure) |

## Decision

Add **two pure, engine-free classes** plus **one device renderer**, wired into `PlayScreen` with
thin glue, and pin the wiring with a source-anchored guard.

- **`render/CombatHudState.kt`** (pure): `CombatHudState.build(combat, playerPos, hasOperableTurrets,
  turretDisabled)` derives the frame's overlay. The **lock** is
  `TargetingPriority.selectTarget(playerPos, combat.hostiles)?.id` — the *same firing point the sim
  feeds the turrets* — suppressed (null) when no operable turret would fire. The **bars** are the
  `MAX_BARS` nearest hostiles (the `TargetingPriority` total order: distance² then `HostileId`), each
  carrying its `Hostile.hullFraction` and a distance **fade** (1 near → 0 far) so simultaneous hostiles
  do not clutter the screen.
- **`render/CombatFeedback.kt`** (pure, deterministic-stateful): per-frame intensities for damage
  **dealt** (HostileHit/HostileDestroyed), **taken** (PlayerHit/PlayerDestroyed) and **shake**, each
  decaying linearly to 0. **No RNG, no wall clock.** Reduced motion is a **parameter** to `update(...)`
  and gates the read accessors (`shakeIntensity`/`takenFlash`/`dealtFlash` → 0 under reduced motion), so
  `CombatFeedback` never reads the `MotionPreference` global and its tests need no `reset()` teardown.
- **`render/CombatHudRenderer.kt`** (device-only): a `ShapeRenderer` drawing the bars + lock reticle in
  **world space** and a full-screen damage-taken flash in screen space — **not** scene2d actors (PIN #3),
  so they cannot drift into the FIRE-arc/HUD layer (AC#4).
- **`PlayScreen` wiring**: accumulate each combat tick's `CombatEvent`s, fold them into `CombatFeedback`
  once per frame, build `CombatHudState` from `physics.readKinematics().position` (the firing-point
  parity, PIN #1), draw the overlay after the hostiles, and apply a **non-accumulating** camera shake —
  the camera-follow position is rebuilt from the ship each frame, then a tiny capped offset is added (PIN
  #2), so the shake decays cleanly to 0 when the encounter ends.

Pinned decisions:

- **Lock honesty.** The reticle is suppressed when no operable turret / TURRET disabled — it never lies
  about an engagement the guns are not making.
- **Reduced-motion by parameter injection**, not by reading the global — keeping the pure tests global-free.
- **World-space bars/reticle + full-screen flash + reused `ShipSchematicRenderer` for player status** —
  no new screen-space widget near the FIRE arc (AC#4).
- **PIN #4: particles omitted.** Flash + shake satisfy AC#3; particles are deferred as heavier and
  low-value for the MVP.

## Consequences

- The combat model, its schema (stays **v19**), and the committed playthrough fixtures are untouched: the
  HUD only *reads* the state, the test-set `Simulation` mirror renders no HUD, and `CombatFeedback` adds
  no RNG draws — so replay stays byte-identical ([ADR 0006](0006-determinism-and-playthrough-harness.md)).
- The targeting/health-bar/feedback derivation is unit-tested against the combat model in `core`
  (`CombatHudStateTest`, `CombatFeedbackTest`), satisfying UC44 AC#5; the PlayScreen wiring — which needs
  a GL context — is pinned by a comment-stripped, source-anchored guard (`Uc44CombatHudSourceTest`),
  mirroring the existing `Uc40EconomyFeedbackSourceTest`.
- The reticle is correct by construction: it shares `TargetingPriority.selectTarget` with the turret fire,
  so a future change to targeting moves the reticle and the guns together.
- Future richer feedback (particles, per-section damage numbers, a manual-targeting reticle) can extend
  `CombatFeedback`/`CombatHudState` without touching the model. Manual/player-designated targeting remains
  a separate later feature (UC44 keeps auto-aim as the MVP control).
