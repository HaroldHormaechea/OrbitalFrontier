---
plan_for: use-cases/44-combat-hud-feedback.md
work_branch: feat/uc-44-combat-hud-feedback
team: orbital-frontier-uc-44
approved: 2026-06-19
---

# UC-44 Combat HUD feedback — FINAL APPROVED PROPOSAL

Challenger approved (1 round, 4 minor pins folded in). This is the fixed source of truth the developer and QA build/verify against.

## Analysis
Combat (UC13) is fully modelled and pure. Anchors:
- **Auto-target = `TargetingPriority.selectTarget(playerPos, hostiles)`** (`core/src/main/kotlin/com/orbitalfrontier/combat/TargetingPriority.kt`) — the SAME call `Combat.step` uses for turret fire, with `playerPos = player.kinematics.position`. Turret fire is gated on `operableTurrets(crew).isNotEmpty() && !turretDisabled(TURRET) [&& cooldown && hostiles non-empty]`. The lock indicator reuses this selection + the operable/disabled gate (dropping the per-shot cooldown so the reticle doesn't flicker).
- **Hostile hull** = `Hostile.hullFraction(archetype)`, archetype via `HostileArchetypes.byId(h.archetypeId)`; position = `hostile.kinematics.position`.
- **Hit events already exist** (`CombatEvent.HostileHit`/`HostileDestroyed` = dealt; `PlayerHit`/`PlayerDestroyed` = taken); `PlayScreen.stepCombatOnce()` already iterates `result.events`. Reuse — no model change.
- **Reduced-motion** = `MotionPreference.reduced` (`core/.../render/MotionPreference.kt`), rendering-only global with `reset()`; its doc pre-declares screen-shake as in scope.
- **Player section status (AC#2)** already drawn by `ShipSchematicRenderer` while `combat.active` (PlayScreen ~1173) — reused, not duplicated.
- **Drawing**: `MinimapRenderer` uses `ShapeRenderer` (Filled+Line); world-space combat draws use the world camera (`HostileRenderer`). Bars+reticle → new ShapeRenderer renderer in WORLD space. No new atlas regions.
- **Determinism**: CombatState transient (NONE on load, ADR 0012); the test-set `Simulation` mirror runs `Combat.step` but renders no HUD. HUD derivation only READS the model. → **No Simulation change, no schema bump (stays v19), replay byte-identical.**

## Proposed Solution
Two PURE engine-free classes + one device renderer + PlayScreen wiring + ADR.

1. **`core/src/main/kotlin/com/orbitalfrontier/render/CombatHudState.kt`** (NEW, pure; AC#1/#2, the AC#5-tested seam):
   - `data class HostileHealthBar(id: HostileId, position: Vec2, hullFraction: Float, fade: Float)` — `fade` 0..1 alpha from distance (full ≤ NEAR_RANGE², → 0 by FAR_RANGE²; squared distances, no sqrt).
   - `data class CombatHudState(lockedTargetId: HostileId?, bars: List<HostileHealthBar>)`.
   - `build(combat, playerPos, hasOperableTurrets, turretDisabled)`: `lockedTargetId = TargetingPriority.selectTarget(playerPos, combat.hostiles)?.id` **only when `hasOperableTurrets && !turretDisabled`** (else null). `bars` = live hostiles' hull fraction + distance fade, **sorted by distance² then HostileId** (the TargetingPriority total order), **capped to MAX_BARS nearest** (clutter pitfall). Empty no-op when combat inactive.
   - Companion constants `MAX_BARS`, `NEAR_RANGE`, `FAR_RANGE` (internal).

2. **`core/src/main/kotlin/com/orbitalfrontier/render/CombatFeedback.kt`** (NEW, pure, deterministic-stateful; AC#3):
   - Raw intensities `dealt`/`taken`/`shake` (0..1). `update(events: List<CombatEvent>, dt: Float, reducedMotion: Boolean)`: decay by `dt/DECAY_SECONDS`, then bump `dealt` on HostileHit/HostileDestroyed, `taken`+`shake` on PlayerHit/PlayerDestroyed (clamp 1).
   - Read accessors return values **already gated by reducedMotion**: `shakeIntensity`→0; `takenFlash`/`dealtFlash` attenuated to 0 under reduced motion. **reducedMotion is a PARAMETER — never touches the global → unit tests need no `reset()` teardown.**

3. **`core/src/main/kotlin/com/orbitalfrontier/render/CombatHudRenderer.kt`** (NEW, device-only): WORLD-space draw via the world camera, mirroring `HostileRenderer` (owns its own `ShapeRenderer`, borrows nothing). Given `CombatHudState` + a `CombatFeedback` snapshot + camera: lock reticle (ring/brackets) around `lockedTargetId`'s hostile (pulse from `dealtFlash`); health bar above each `bar.position` (SUCCESS/WARNING/DANGER by fraction, alpha = `bar.fade`). No-op when state empty.

4. **`core/src/main/kotlin/com/orbitalfrontier/screen/PlayScreen.kt`** (MODIFY, wiring only):
   - Field-init `CombatHudRenderer(gameAssets)` + `CombatFeedback()` (near `hostileRenderer`/`shipSchematicRenderer` ~258).
   - Accumulate combat events: `stepCombatOnce()` appends `result.events` to a per-frame `combatEventsThisFrame` list; `renderFrame()` calls `combatFeedback.update(combatEventsThisFrame, frameDt, MotionPreference.reduced)` once, then clears it.
   - **PIN #1 (firing-point parity):** in `renderFrame()`, derive `playerPos` from `physics.readKinematics().position` — the SAME source `stepCombatOnce` feeds into `PlayerCombatInput.kinematics`. Do NOT use a render-interpolated camera/ship transform. Build `CombatHudState.build(combat, thatPos, hasOperableTurrets, turretDisabled)` (gate read from `fleet.active` loadout+crew exactly like `PlayerCombatInput`), then `combatHudRenderer.render(worldCamera, state, feedbackSnapshot)` after `hostileRenderer.render(...)` (~1088).
   - **PIN #2 (non-accumulating shake):** apply jitter to the FRESHLY-derived camera-follow position each frame (set position from ship, then add `shakeIntensity`-scaled offset; never `+=` onto last frame's camera position) before `worldCamera.update()` (~1068–9). Tiny capped magnitude. Decay must drive shake/flash to 0 when combat ends — no stuck overlay on `EncounterCleared`.
   - **Damage-taken flash:** full-screen translucent DANGER quad (ShapeRenderer Filled) at alpha = `takenFlash`, drawn over the world. Full-screen overlay → zero layout footprint. (PIN #3: bars + flash are ShapeRenderer draws in `renderFrame`, NOT scene2d Stage actors, so they can't drift into the FIRE-arc/Stage layer.)

5. **`docs/adr/0032-combat-hud-feedback.md`** (NEW): (a) pure combat-HUD-state derivation + lock-reuses-`TargetingPriority.selectTarget` (same firing point the sim uses), suppressed when no operable turret; (b) reduced-motion gating via param injection (pure tests, no global); (c) world-space bars/reticle + reused schematic → no new screen-space HUD element near the FIRE arc; (d) **PIN #4: particles omitted** — flash+shake satisfy AC#3's menu.
6. **`docs/design/combat.md`** (MODIFY): bump status line + player-facing section for UC44.

## Files Affected
**Production code (developer):**
- `core/src/main/kotlin/com/orbitalfrontier/render/CombatHudState.kt` (new)
- `core/src/main/kotlin/com/orbitalfrontier/render/CombatFeedback.kt` (new)
- `core/src/main/kotlin/com/orbitalfrontier/render/CombatHudRenderer.kt` (new)
- `core/src/main/kotlin/com/orbitalfrontier/screen/PlayScreen.kt` (modify)
- `docs/adr/0032-combat-hud-feedback.md` (new)
- `docs/design/combat.md` (modify)

**Test code (qa):**
- `core/src/test/kotlin/com/orbitalfrontier/render/CombatHudStateTest.kt` (new, pure): lock == `TargetingPriority.selectTarget(physicsPos, hostiles)` for identical inputs (**PIN #1 case**); null when no operable turret / turret disabled / combat inactive; bar hullFraction == `Hostile.hullFraction`; clutter cap to MAX_BARS nearest; fade decreases with distance.
- `core/src/test/kotlin/com/orbitalfrontier/render/CombatFeedbackTest.kt` (new, pure): bump on dealt vs taken; decay over dt to 0 (no stuck state); reducedMotion zeroes shake + attenuates flash. No global touched.
- `core/src/test/kotlin/com/orbitalfrontier/screen/Uc44CombatHudSourceTest.kt` (new, source-anchored, comment-stripped, walk-up finder — mirrors `Uc40EconomyFeedbackSourceTest`): PlayScreen builds `CombatHudState.build(`, draws via `combatHudRenderer.render(` with the WORLD camera, passes `MotionPreference.reduced` into `combatFeedback.update(`, derives `playerPos` from `physics.readKinematics(` (PIN #1), applies a non-accumulating shake to the camera-follow position (PIN #2), and draws flash/bars via ShapeRenderer (NOT scene2d actors) (PIN #3). Rationale: screen has GL in field inits → not headlessly constructible.

## Risks & Considerations
- **AC#1**: reticle suppressed when no operable turret / TURRET disabled (honesty-to-turret-state) — challenger endorsed; keep.
- **Shake**: render-only, non-accumulating, reduced-motion-gated, tiny capped magnitude; FIRE arc is scene2d/screen-space so unaffected by the world-camera offset. Decays to 0 on combat end.
- **Particles** not implemented (heavier); flash+shake satisfy AC#3.
- **AC#4 overlap**: bars/reticle world-space, flash full-screen, player status reuses schematic (already non-overlapping per `Uc34ExpandedHudGuardTest`) → no new screen-space widget near FIRE arc.
- Feedback decay uses render frame-dt (not COMBAT_DT) for smoothness.

**Status: APPROVED by challenger.**
