---
plan_for: use-cases/45-enemy-ai-spawn-director.md
work_branch: feat/uc-45-enemy-ai-spawn-director
team: orbital-frontier-uc-45
approved: 2026-06-19
---

# UC-45 — Richer enemy AI & encounter variety — FINAL APPROVED PROPOSAL

**Status:** Approved by challenger (round 1) with conditions #1–#2 hard, #3–#5 folded in. This is the implementation plan the developer and QA build/verify against.

## Analysis

**Load-bearing finding (verified by both analyst & challenger):** Combat state is **transient (ADR 0012)** and **not serialized**. `StateSnapshotDto` omits hostiles/projectiles/combat-RNG; `SimulationState.combat` decodes as `CombatState.NONE`. So adding fields to `CombatState`/`Hostile`/`Projectile`/`HostileArchetype`/`EncounterZone`/`Projectile` changes **no committed fixture JSON**. The real stability contract is the **replay assertion tests** (`Uc13/41/42/43…ReplayTest`), which re-run the sim and assert combat *effects* that the snapshot DOES record (player section damage, credits, salvage, reputation, position). Therefore "keep fixtures byte-identical" == "do not change the behavior or RNG-consumption of the existing RAIDER / SCAVENGER / INDEPENDENT_MARAUDER in their recorded encounters." Every change below is engineered around **opt-in defaults** so existing archetypes and zones are provably non-perturbing.

Other confirmed facts:
- Combat is the deterministic core: pure `Combat.step` is called by BOTH `PlayScreen.stepCombatOnce` (production) AND `core/src/test/.../sim/Simulation.step` (the lockstep test mirror). All new logic must be pure and mirrored in lockstep.
- `EnemyAi.decide(hostile, archetype, playerPos) → Decision(desiredHeading, thrust, wantsToFire)`. `AiBehavior` is equality-checked (`== FLEE_WHEN_DAMAGED`), **no exhaustive `when`** — new constants are safe provided the existing AGGRESSIVE/FLEE branches stay byte-identical (extend the `if/else`, don't restructure).
- Hostile fire is undirected; the struck player section is chosen at collision time by `DamageModel.applyHit` (RNG-weighted over `params.sectionHitWeights`). "Target weakest section" must be opt-in or it perturbs every existing combat replay's RNG stream.
- Multi-hostile **already works**: `EncounterSpawner.spawn` loops `repeat(hostileCount)` (one `nextFloat` bearing-draw per hostile); `EncounterSpawnerTest` already exercises `hostileCount=2`.
- Zones: `MvpSectorMap.ENCOUNTER_ZONES` = `alpha-raider-picket` (Alpha, RAIDER×1) + `gamma-independent-marauder` (Gamma, INDEPENDENT_MARAUDER×1). **Beta has zero natural zones.** Two `.single()` reliances: `Uc42LootSalvageReplayTest:37` (`encounterZones(alpha).single()`) and `PlaythroughFixtures.kt:1028` (`encounterZones(gamma).single()`).
- **uc03 roams Beta** (Alpha→Beta jump, arrives ≈(-1300,0), thrusts east along y≈0 for 30 ticks). **uc43 roams the WHOLE of Gamma** (`PlaythroughFixtures.kt:1025`), not just its (700,700) kill zone — a new Gamma zone must be disjoint from uc43's entire flight path or a spurious mid-replay natural-spawn diverges it.
- `CombatReputationTest:56` asserts `affiliated == listOf(INDEPENDENT_MARAUDER)` **exactly** — any new archetype with a non-null faction breaks it.
- Player section/hull model: `PlayerCombatInput` already carries `sectionDamage` + `maxSectionHp`, available in `Combat.step`'s hostile-fire block; `ShipStats.sectionHpMap`/`sectionHp` derive max HP; `SectionDamages` holds the pure current-HP/apply-damage helpers; `ShipSection` is the closed enum (HULL/ENGINE/TURRET/WEAPON).
- Persistence: encounter/AI/spawn state stays **transient — no schema bump**. The director's input is derived from already-persisted loadout; no new persistent state.

## Proposed Solution (patterns + reference paths; no code)

**A. Retreat-and-regroup movement behavior (AC#2).** Add `AiBehavior.RETREAT_AND_REGROUP` and archetype field `regroupRange: Float` (authored `< leashRange` so it returns before leashing off). In `EnemyAi.decide`, extend the existing `if/else` — **AGGRESSIVE and FLEE_WHEN_DAMAGED branches stay byte-identical**: for RETREAT_AND_REGROUP, when hull `< fleeHullFraction` → if distance `< regroupRange` flee (heading 180° from player, thrust, hold fire), else (recovered standoff distance) re-engage (turn toward, thrust, fire when in `engageRange`); when healthy → engage. Memoryless/distance-driven ⇒ pure, deterministic, no new hostile state. Files: `core/src/main/kotlin/com/orbitalfrontier/combat/EnemyAi.kt`, `…/combat/Hostile.kt`.

**B. Weakest-section targeting (AC#2).** Archetype flag `targetsWeakestSection: Boolean = false` (default false ⇒ existing archetypes unchanged). New pure helper `WeakestSection.of(sectionDamage, maxSectionHp)` returns the section with the lowest **current-HP fraction** among positive-max sections, tie-break **ascending `ShipSection.name`** (matches `DamageModel`'s ordering convention). Add `targetSection: ShipSection? = null` to `Projectile`. In `Combat.step`'s hostile-fire block, when `archetype.targetsWeakestSection`, compute the weakest section from `player` (already in scope) and stamp it onto the emitted hostile projectile — **"weakest-at-fire-time," not re-retargeted mid-flight** (a deliberate, documented MVP semantic; see ADR 0033). In the HOSTILE-projectile collision branch: if `targetSection != null` and the player has that section (maxHp>0) → apply damage **directly** via `SectionDamages.applyDamage` (no RNG draw) and emit `PlayerHit(section)`; else the **unchanged** `DamageModel.applyHit` RNG-weighted path. Net: RNG consumption is identical for every non-targeting (i.e. all existing) hostile ⇒ existing replays bit-identical. Files: `…/combat/Combat.kt`, `…/combat/Projectile.kt`, new `…/combat/WeakestSection.kt`.

**C. Configurable composition (AC#1, count/type).** Add `data class HostileSpawn(archetypeId, count)` and make `EncounterZone.composition: List<HostileSpawn>` the source of truth, with a **backward-compatible convenience constructor** `EncounterZone(id, sectorId, center, radius, archetypeId, hostileCount)` → `listOf(HostileSpawn(archetypeId, hostileCount))`, plus computed `archetypeId`/`hostileCount` accessors (first entry / sum of counts) so every existing zone literal, the bounty path (`zone.archetypeId`/`zone.hostileCount` at `Simulation.kt:549-551` & `PlayScreen.kt:1561-1562`), and `EncounterSpawnerTest` compile and behave identically. `EncounterSpawner.spawn` iterates composition entries (one `nextFloat` bearing-draw per hostile, in order) — for a single-entry composition this is **byte-identical RNG consumption** to today. `EncounterSpawner.missionSpawn` (bounty path) stays **single-archetype** — composition-awareness is intentionally natural-spawn-only this UC (no multi-entry bounty zones exist or are added). Files: `…/combat/EncounterZone.kt`, `…/combat/EncounterSpawner.kt`.

**D. Spawn director (AC#3).** New pure `object SpawnDirector` + opt-in policy type `SpawnScaling` (`None` default, `ByProgression(...)`). Per-zone field `EncounterZone.scaling: SpawnScaling = SpawnScaling.None`. Director input = **player progression** via pure `ProgressionLevel.of(activeShip.loadout)` (installed-upgrade count → difficulty level by authored thresholds, `[TUNE]`). `SpawnDirector.scale(baseComposition, scaling, progression)` returns base unchanged for `None` (⇒ ALL existing zones — both faction and bounty — provably unscaled regardless of input) and adds hostiles / bumps archetype strength for `ByProgression`. `EncounterSpawner.naturalSpawn` gains a `progression: Int = 0` param (defaulted so existing tests compile; ignored for `None` zones). **Lockstep:** production `PlayScreen.runCombat` and the mirror `sim/Simulation.step` both compute progression from `fleet.active.loadout` and pass it into `naturalSpawn`. Progression chosen over reputation specifically so new archetypes stay unaligned (keeps `CombatReputationTest` green) and the fixture seeds difficulty deterministically via loadout. Files: new `…/combat/SpawnDirector.kt`, `…/combat/SpawnScaling.kt`, `…/combat/ProgressionLevel.kt`.

**E. New zones (AC#1) — disjointness-first; CONDITION #1 applies.** Existing zones untouched.
- **Beta** (zero zones today; only uc03 roams it along y≈0 eastward from ≈(-1300,0)): add one natural zone well off that corridor, candidate center ≈ **(600, 700) r260** (clear of beta-station (300,-300), beta-belt (-500,500), beta-to-gamma gate (650,1126) by >250). Developer verifies disjoint from the uc03 path.
- **Gamma multi-hostile fixture zone (AC#5):** new zone disjoint from BOTH the existing `gamma-independent-marauder` (700,700) AND **uc43's entire Gamma flight path**, candidate center ≈ **(-700, -300) r260**. Multi-entry composition (e.g. base AGGRESSIVE + RETREAT_AND_REGROUP×1 + weakest-section-targeter×1), scaled by `ByProgression`. This is the only zone the new fixture flies into.
This gives natural zones in **all three sectors** (Alpha picket, Beta new, Gamma marauder + new). No 2nd Alpha zone (Alpha is dense with fixture corridors and the Uc42 `.single()`).
**HARD CONDITION #1:** if any existing replay (`uc03`, `uc43`, or any other) diverges, the fix is to **MOVE THE NEW ZONE** — never to regenerate/re-bless the existing fixture. The `PlaythroughFixtureTest` regenerate-and-compare guard catches a bad coordinate; treat a red existing replay as a misplaced zone, not a fixture to update.

**F. New archetypes (AC#2/#4) — CONDITION #2 applies.** Add 1–2 new archetypes in `HostileArchetypes` for the new behaviors (one RETREAT_AND_REGROUP; one AGGRESSIVE + `targetsWeakestSection`). **HARD CONDITION #2: every new archetype MUST be unaligned (`factionId = null`)** — `CombatReputationTest:56` asserts `affiliated == listOf(INDEPENDENT_MARAUDER)` exactly. Existing RAIDER/SCAVENGER/INDEPENDENT_MARAUDER **unchanged**. All stats `[TUNE]`.

**G. AC#5 fixture showcases the new AI (folded condition #3).** The new `uc45` multi-hostile fixture must use **multi-section `sectionHitWeights`** (NOT the HULL-only `{HULL:1}` the other combat fixtures pin) so the recorded fight actually exercises retreat-and-regroup + weakest-section targeting end-to-end (under HULL-only weights the weakest-section archetype is indistinguishable from normal fire). The authoritative behavior proofs remain the `WeakestSection`/`CombatTest` targeting unit tests; the fixture demonstrates it in a real replay.

**H. Docs/ADR — deliverable.** New **ADR 0033** (highest existing is 0032) capturing all five decisions: (1) opt-in-default AI behaviors for backward-compat/fixture stability; (2) deterministic direct-apply weakest-section targeting with the documented **"weakest-at-fire-time"** semantic; (3) progression-driven opt-in spawn director; (4) `EncounterZone` composition list; (5) transient state, no schema bump. Update `docs/design/combat.md:147-148` to mark retreat-and-regroup, weakest-section targeting, and spawn-director difficulty scaling as **built** — **formations stay deferred / out of scope** (not in any AC; the AC pitfall mentions formations only as an auto-aim stress concern).

**I. Auto-aim pitfall (AC#1 open question).** No production change: `TargetingPriority.selectTarget` / `Combat.nearestHostileWithin` already total-order (distance²-then-id), verified sane under multi-hostile. Covered by the new multi-hostile fixture + a `TargetingPriority` multi-hostile unit test.

## Files Affected

**Production code (developer):**
- `core/src/main/kotlin/com/orbitalfrontier/combat/Hostile.kt` — add `AiBehavior.RETREAT_AND_REGROUP`; add `HostileArchetype` fields `regroupRange`, `targetsWeakestSection`; add new **unaligned** archetype(s). Existing archetypes unchanged.
- `core/src/main/kotlin/com/orbitalfrontier/combat/EnemyAi.kt` — additive RETREAT_AND_REGROUP branch (AGGRESSIVE/FLEE branches byte-identical).
- `core/src/main/kotlin/com/orbitalfrontier/combat/Projectile.kt` — add `targetSection: ShipSection? = null`.
- `core/src/main/kotlin/com/orbitalfrontier/combat/Combat.kt` — stamp weakest `targetSection` on targeting hostiles' fire; direct-apply targeted hostile hits (no RNG); non-targeting path unchanged.
- `core/src/main/kotlin/com/orbitalfrontier/combat/EncounterZone.kt` — `composition: List<HostileSpawn>` + `HostileSpawn` + convenience ctor + computed `archetypeId`/`hostileCount` accessors + `scaling: SpawnScaling = None`.
- `core/src/main/kotlin/com/orbitalfrontier/combat/EncounterSpawner.kt` — composition-aware natural spawn; apply `SpawnDirector`; new `progression` param. `missionSpawn` stays single-archetype.
- **NEW** `core/src/main/kotlin/com/orbitalfrontier/combat/SpawnDirector.kt`
- **NEW** `core/src/main/kotlin/com/orbitalfrontier/combat/SpawnScaling.kt`
- **NEW** `core/src/main/kotlin/com/orbitalfrontier/combat/ProgressionLevel.kt`
- **NEW** `core/src/main/kotlin/com/orbitalfrontier/combat/WeakestSection.kt`
- `core/src/main/kotlin/com/orbitalfrontier/world/MvpSectorMap.kt` — add new Beta + Gamma natural zones (disjoint per condition #1); existing zones unchanged.
- `core/src/main/kotlin/com/orbitalfrontier/screen/PlayScreen.kt` — compute + pass `progression` in `runCombat` (lockstep with Simulation mirror).
- **NEW** `docs/adr/0033-richer-enemy-ai-and-spawn-director.md` — **ADR 0033 deliverable** (five decisions above).
- `docs/design/combat.md` — mark retreat-and-regroup / weakest-section / spawn-director scaling as built (formations remain deferred).

**Test code (qa):**
- `core/src/test/kotlin/com/orbitalfrontier/sim/Simulation.kt` — **lockstep test-source mirror**: compute + pass `progression` in the natural-spawn loop (must match `PlayScreen.runCombat` exactly).
- `core/src/test/kotlin/com/orbitalfrontier/playthrough/PlaythroughFixtures.kt` — new `uc45…` builder (multi-hostile Gamma zone, **multi-section `sectionHitWeights`** per condition #3) + register in `ALL`; change `encounterZones(gamma).single()` (line 1028) to an **id-based lookup** of `gamma-independent-marauder` (returns the same zone ⇒ uc43 stays byte-identical).
- **NEW** `core/src/test/resources/playthroughs/uc45-*.json` — committed fixture (regen via `-Dfixture.regen=true`).
- **NEW** `core/src/test/kotlin/com/orbitalfrontier/playthrough/Uc45…ReplayTest.kt` — multi-hostile encounter cleared + bit-for-bit determinism (+ assert the weakest-section/retreat effects show in the recording).
- `core/src/test/kotlin/com/orbitalfrontier/combat/EnemyAiTest.kt` — RETREAT_AND_REGROUP cases (flee below threshold inside regroupRange; re-engage beyond it; healthy = engage).
- **NEW** `core/src/test/kotlin/com/orbitalfrontier/combat/SpawnDirectorTest.kt` — `None` returns base unchanged; `ByProgression` scales by threshold; determinism.
- **NEW** `core/src/test/kotlin/com/orbitalfrontier/combat/WeakestSectionTest.kt` — lowest-fraction pick + `ShipSection.name` tie-break + no-section edge case.
- `core/src/test/kotlin/com/orbitalfrontier/combat/CombatTest.kt` — weakest-section targeting case (targeted hostile hit lands on weakest section, no RNG draw).
- `core/src/test/kotlin/com/orbitalfrontier/combat/TargetingPriorityTest.kt` — multi-hostile total-order sanity (auto-aim pitfall).
- `core/src/test/kotlin/com/orbitalfrontier/combat/EncounterSpawnerTest.kt` (and/or an EncounterZone test) — multi-entry composition spawn coverage; single-entry parity.
- *(No change to `Uc42LootSalvageReplayTest.kt`'s alpha `.single()` — no Alpha zone is added. Flagged only in case scope changes.)*

## Risks & Considerations
- **R1 (top): RNG/replay stability.** Mitigated by opt-in defaults: existing archetypes keep AGGRESSIVE/FLEE + `targetsWeakestSection=false`; existing zones keep `scaling=None`; single-entry composition preserves the one-`nextFloat`-per-hostile draw order; weakest-section direct-apply only fires for new archetypes. No existing replay's code path or RNG stream changes. Backstop: `PlaythroughFixtureTest` regenerate-and-compare + per-UC replay tests.
- **R2: New-zone placement (CONDITION #1).** Beta must clear the uc03 y≈0 corridor; Gamma must clear uc43's entire flight path AND the existing gamma zone. If a replay diverges, **move the zone — do not regenerate the fixture.**
- **R3: `CombatReputationTest` exact-list assertion (CONDITION #2).** New archetypes stay unaligned (`factionId = null`). A faction-scaled director would require changing that test — out of scope.
- **R4: composition-list scope.** A bounded refactor of `EncounterZone`/`EncounterSpawner`; justified by AC#1's literal "configurable hostile composition (count/type)" and AC#5's "varied AI" intent. Challenger approved over the lean fallback. Zone equality doesn't touch serialization.
- **R5: spawn-director input.** Progression (installed-upgrade count) chosen over reputation to keep archetypes unaligned and seed difficulty deterministically via loadout; AC#3 explicitly allows progression. Challenger approved.
- **R6: weakest-section semantic & tuning.** "Weakest-at-fire-time," lowest current-HP fraction, `ShipSection.name` tie-break — documented in ADR 0033. All spawn/AI numbers are `[TUNE]` placeholders per house convention.
- **R7: lockstep drift.** The progression computation must be identical in `PlayScreen.runCombat` and `sim/Simulation.step`; QA verifies via the new uc45 replay (record-vs-replay parity) plus the existing combat replays staying green.

**Conditions #1 and #2 are non-negotiable for QA sign-off.** Approved by challenger.
