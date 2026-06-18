# ADR 0022 — Ship-destruction / game-over consequence screen

- **Status:** Accepted
- **Date:** 2026-06-18

## Context

Combat (UC13, [ADR 0012](0012-real-time-combat.md)) already made destruction **forgiving**: when the
player's hull reaches 0, `Respawn` relocates the ship to its `lastDockedStation`, jettisons a
`respawnCargoLossFraction` slice of the hold, fully repairs the sections and clears the encounter — no
permadeath, no credit loss. But the moment was **invisible**: `PlayScreen.runCombat` applied the respawn
and *silently teleported* the player back, with no "ship destroyed" feedback, no statement of what was
lost, and no acknowledgement before control resumed. For an MVP vertical slice (`maturity_target: mvp`)
that is a readiness gap — destruction is the highest-stakes event in the loop and the player can miss it
entirely.

UC-33 adds a **destruction / game-over screen**: on destruction the simulation halts and a modal overlay
reports the consequences (cargo lost, any credit/insurance penalty, the respawn location) and requires a
deliberate **CONTINUE** before returning to control at the respawn station. This forces four decisions
worth recording: (1) the death model (permadeath vs. respawn-with-penalty), (2) how the consequence is
surfaced relative to the existing pause overlay (UC32/[ADR 0021](0021-pause-overlay.md)), (3) how the
respawn is persisted so a crash can't duplicate or skip the penalty, and (4) the fallback respawn point
when the player is destroyed before ever docking.

Relevant brief fields: `maturity_target: mvp`, the 60 FPS performance budget, the binding **SOLID**
coding principle (the consequence is a pure, JVM-testable value, mirroring `PauseState`), and the
combat design note's "forgiving stakes (no permadeath)" goal (`docs/design/combat.md`).

## Options considered

| Option | For | Against |
|---|---|---|
| **Respawn-with-penalty, surfaced on a modal CONTINUE screen (chosen)** | Keeps the established forgiving model (ADR 0012) but makes its cost legible; the deliberate CONTINUE turns a silent teleport into an understood consequence; the freeze reuses the UC32 gate pattern (a pure, testable `DestructionState`); the consequence is a pure value a JVM test asserts (AC#5). | Adds a second sim-freezing overlay concept alongside pause, so the two must compose cleanly (resolved: the destruction gate is read *nested under* the pause gate). |
| Full game-over / permadeath | Higher stakes; simpler "you died" framing | Contradicts ADR 0012's no-permadeath decision and the brief's approachable MVP framing; would discard progression on every loss — wrong for a pick-up-and-play mobile RPG. |
| Keep the silent teleport, add only a toast/log line | Smallest change | Non-modal feedback is missable mid-flight and gives no acknowledgement beat; the player still can't tell what was lost or where they are now. Leaves the readiness gap. |
| Bump the save schema to record a "destruction pending" flag | Explicit crash-safety bookkeeping | Unnecessary: the respawn is a complete, already-modelled world mutation — persisting it durably *at destruction* (no schema change) reloads the post-respawn state, which is correct by construction. A flag would add a migration and a second source of truth. |

## Decision

Add a **respawn-with-penalty destruction screen** that freezes the simulation until the player taps
CONTINUE.

- **Consequence stays PURE.** A new libGDX-free `combat/DestructionSummary` (cargo units lost, credit
  penalty, respawn-location name) is built by a pure factory `DestructionSummary.from(RespawnResult,
  name)`. `creditPenalty` is a constant `0` in the MVP — **insurance covered, no credit loss** — modelled
  explicitly (not omitted) so the screen can state the insurance outcome and a future balancing pass can
  introduce a real penalty without changing the shape. This is the value a JVM test drives a destruction
  through and asserts (AC#5); the device and the replay harness derive an identical summary.

- **The SCREEN is the Scene2D layer.** A new `render/DestructionState` (`isPending` / `pending(summary)`
  / `cleared()`) mirrors `PauseState`, and a new `screen/controls/DestructionOverlay` mirrors
  `PauseOverlay` (tap-swallowing dim backdrop + "SHIP DESTROYED" title + the three consequence lines +
  a single CONTINUE button). `PlayScreen` reads `destructionState.isPending` once per frame **nested
  inside the pause gate** (`if (!paused) { if (!destructionState.isPending) advanceSimulation(dt) }`) so
  the two freeze conditions compose and the UC32 source-anchored guard stays byte-identical. Rendering
  still runs in both states (`renderFrame`), so the frozen scene stays visible under the backdrop.

- **Apply-at-destruction, then confirm (ordering).** The respawn mutates world state (position, sector,
  cargo, repair, cleared combat) **before** the CONTINUE tap; CONTINUE only clears the gate. The ship
  already sits at the respawn station while the screen is up — CONTINUE is an acknowledgement, not the
  thing that moves the player.

- **Persistence — NO schema bump, durable flush.** The fire-and-forget `autosave.onEvent("respawn")` is
  replaced by a new `AutosaveController.onCriticalEvent(reason)` (enqueue **+ `saveExecutor.flush()`**,
  mirroring `onPauseOrExit`). The respawn is committed and durably written at the moment of destruction,
  so a crash/close on the consequence screen reloads the **post-respawn** state. Combat is transient
  (ADR 0012) → no encounter reloads → the penalty is applied exactly once (AC#4).

- **Fallback respawn point.** The no-prior-dock branch (destroyed before the first dock) now respawns at
  the **game-start point** — `(MvpSectorMap.START_SECTOR, Vec2.ZERO)`, which is exactly where a new game
  spawns (`Fleet.starter()` at the origin in `START_SECTOR`) — rather than respawning in place in deep
  space. The location label is read from the authored world (`Sector.displayName`, "Alpha Reach"), never
  hardcoded.

## Consequences

- **Easier:** the highest-stakes event in the loop is now legible and acknowledged; the consequence is a
  pure value, so its content is unit-tested without GL; crash-safety is "free" (a complete world mutation
  persisted durably) with no migration. The pattern (pure gate + mirrored modal overlay) now exists twice
  (pause, destruction), so a third sim-freezing modal would follow a well-worn path.
- **Harder / watch:** there are now **two** sim-freezing overlays whose gates must keep composing — the
  destruction gate is deliberately read *nested under* the pause gate, and the UC32 guard literals
  (`pauseButton.isVisible = …`, `pauseOverlay.actor.isVisible = paused`, the settings-visibility branch)
  are preserved byte-identically with destruction overrides added as **follow-up lines**, never edits.
  Adding a future overlay must respect the same z-order and gate-composition discipline.
- **Reversibility:** the credit penalty is a single constant in `DestructionSummary.from`; a real penalty
  (or a permadeath mode) would be a new ADR plus a `CombatParams` `[TUNE]` change, not a structural
  rework. The screen and gate are independent of the (unchanged) `Respawn` rule.
