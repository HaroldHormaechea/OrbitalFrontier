---
plan_for: use-cases/53-procedural-sector-generation.md
work_branch: feat/uc-53-procedural-sector-generation
team: orbital-frontier-uc-53
approved: 2026-06-20
---

# UC-53 (procedural sector generation) — APPROVED final proposal (analyst↔challenger agreement, 2 rounds).

## Analysis
The world is one hand-authored graph: `MvpSectorMap.build()` → `SectorWorld` (sectors → POIs: `JumpGate`/`Station`/`AsteroidField`/`HiddenContact`). It's the sole world everywhere — production (`OrbitalFrontierGame.kt:171`, shared into `PlayScreen` :589 and `resolveDockedStation` :627) and the test `Simulation` (`Simulation.kt:109`, `world = MvpSectorMap.build()` default). `ReplayRunner` never passes a world. The sector graph is **NOT serialized into playthrough fixtures** (they carry only the combat/mission RNG `seed` + `SimulationState`), so all ~23 fixtures depend solely on `Simulation`'s default being the authored map. There is no world seed today; `DeterministicRng` (fnv1a→LCG, pure 64-bit, no wall-clock) is the seeded primitive. Deltas `field_deposit`/`revealed_contact`/`owned_station` already persist — the world seed scalar is the only new persisted datum. Schema is at v22.

## Zero-fixture-regen strategy (overriding constraint — holds by construction)
Additive generator; authored map stays the canonical default:
- `WorldSeed(Long)` with reserved `MVP = WorldSeed(0L)`.
- `SectorGenerator.generate(seed)`: MVP → `MvpSectorMap.build()` verbatim; else → procedural (pure, `DeterministicRng` only).
- `WorldState.worldSeed` defaults to `WorldSeed.MVP`; DB column `world_seed DEFAULT 0` ⇒ every existing save/fixture/new-game → authored map.
- `Simulation`'s default world + the `MvpSectorMap` call site stay **untouched**; replays carry no procedural seed. A structural guard test pins `generate(MVP)` ≡ `MvpSectorMap.build()`.

## Proposed Solution / Files Affected

**Production code (DEVELOPER):**
- `core/src/main/kotlin/com/orbitalfrontier/world/WorldSeed.kt` (NEW) — `@JvmInline value class WorldSeed(val value: Long)`, companion `MVP = WorldSeed(0L)`. Pure.
- `core/src/main/kotlin/com/orbitalfrontier/world/SectorGenerator.kt` (NEW) — `object SectorGenerator { fun generate(seed): SectorWorld }`. MVP branch → `MvpSectorMap.build()`. Procedural branch: seed via `DeterministicRng.fnv1a("$seed")`→LCG; **connectivity by construction** — N sectors in a RING of reciprocal gates (N=3 ⇒ authored triangle topology) + optional seed-decided chord; templated content per sector (1 station + 1 asteroid field + 0–1 hidden contacts), positions jittered within `CONTENT_EXTENT_WORLD_UNITS` (reuse `Vec2.fromAngle`); globally-unique POI ids (`sector{i}-station`, `sector{i}-belt`, `sector{i}-to-{j}`); hand to `SectorWorld(...)` which re-validates reciprocity/uniqueness. `[TUNE]` defaults: 3 sectors, 1 station/1 field/0–1 contacts, ring+chord — recorded in ADR.
- `core/src/main/kotlin/com/orbitalfrontier/world/WorldState.kt` — add `val worldSeed: WorldSeed = WorldSeed.MVP` (defaulted ⇒ snapshot byte-identical; NOT in `SimulationState`, so replay equality untouched).
- `core/src/main/kotlin/com/orbitalfrontier/screen/PlayScreen.kt` — hold `private val worldSeed: WorldSeed = initialWorldState.worldSeed` (ctor already takes `initialWorldState` ~:218) and thread it into the `WorldState(...)` built by `currentWorldState()` (~:1999). **Critical**: without this, every autosave drops the seed back to MVP and AC#3 is vacuous.
- `core/src/main/kotlin/com/orbitalfrontier/save/SaveVersion.kt` — `CURRENT = 23L`.
- `core/src/main/sqldelight/com/orbitalfrontier/save/OrbitalFrontier.sq` — add `world_seed INTEGER NOT NULL DEFAULT 0` to `game_state` CREATE TABLE; add to `selectGameStateForSlot` SELECT + `updateSlotHeader` SET. (`listSlots`/`insertSlotHeaderIfAbsent` untouched.)
- `core/src/main/sqldelight/com/orbitalfrontier/save/migrations/22.sqm` (NEW, FROM-version=22, additive, minSdk-24-safe, no UPSERT): `ALTER TABLE game_state ADD COLUMN world_seed INTEGER NOT NULL DEFAULT 0;` + `UPDATE meta SET save_version = 23 WHERE id = 0;` (precedent: 17.sqm).
- `core/src/main/sqldelight/databases/23.db` (NEW baseline) — regenerate via `./gradlew :core:generateMainOrbitalFrontierSchema`; `verifyMainOrbitalFrontierMigration` enforces the chain.
- `core/src/main/kotlin/com/orbitalfrontier/save/SqlDelightGameStateRepository.kt` — load: `worldSeed = WorldSeed(header.world_seed)`; save: pass `world_seed = state.worldSeed.value` to `updateSlotHeader`.
- `core/src/main/kotlin/com/orbitalfrontier/app/OrbitalFrontierGame.kt` — replace the fixed `sectorWorld` field with per-entry construction in `enterGame`: `val sectorWorld = SectorGenerator.generate(initialWorldState.worldSeed)`, threaded into `resolveDockedStation(world, ...)` and `PlayScreen`. (Default new game seeds `WorldSeed.MVP` ⇒ authored map ⇒ production behaviour unchanged; no new-random-world UI in this UC.)
- `docs/adr/0041-procedural-sector-generation.md` (NEW) + `docs/adr/README.md` index row; mark the procedural-generation open question resolved in `docs/design/world-and-sector.md`. ADR must record: connectivity is a generator invariant (not enforced by `SectorWorld`); and the scope boundary — a non-MVP world gets templated markets and NO encounter/bounty content (the `ENCOUNTER_ZONES`/`BOUNTY_CONTRACTS`/curated markets are consumed via static `MvpSectorMap.*` keyed by literal "alpha"/"beta"/"gamma" ids; deferred follow-up).

**Test code (QA):**
- `core/src/test/kotlin/com/orbitalfrontier/world/SectorGeneratorTest.kt` (NEW): AC#5 **mandatory** reachability (BFS/DFS over gates from start reaches every sector) + determinism (same fixed non-MVP seed → identical world) for a fixed seed; MVP-seed guard as a **field-by-field structural comparison** (sector ids+order, per-POI ids/positions/subtype) vs `MvpSectorMap.build()` — NOT `==` (`SectorWorld` has no `equals`); variety sanity (different seeds → different worlds).
- `core/src/test/kotlin/com/orbitalfrontier/save/SaveMigrationTest.kt`: new v22→v23 step test (column added, DEFAULT 0, prior data survives, version=23, column writable) — this test OWNS the moving "== Schema.version" cross-check; the v21→v22 test gets pinned to literal `22L`.
- `core/src/test/kotlin/com/orbitalfrontier/save/SqlDelightSettingsRepositoryTest.kt`: literals 22→23.
- Seed round-trip test (AC#3) routed **through `PlayScreen.currentWorldState()`** (not the repository in isolation): non-MVP `initialWorldState` → `currentWorldState()` asserts seed survives → repo save+reload → seed preserved AND `SectorGenerator.generate(reloadedSeed)` regenerates identical world.

## Gate command
`./gradlew :core:ktlintCheck :core:test`

## Key risks (all mitigated)
- **Fixture stability:** zero regen by construction (Simulation default + MvpSectorMap call site untouched; DEFAULT-0 column; MVP guard test).
- **AC#3 autosave path:** closed via the PlayScreen seed threading (the one defect found in review).
- **Connectivity:** generator ring invariant + mandatory AC#5 reachability assertion (`SectorWorld` is not a backstop).
- **minSdk-24:** `ALTER TABLE ADD COLUMN`, no `java.time`, no UPSERT.
- **Determinism:** seed-derived via `DeterministicRng` only — no enum/identity hashCode, no wall-clock, no `Math.random`.

## ORCHESTRATOR ROLE-SPLIT NOTE
No lockstep test-source change this run — `sim/Simulation.kt`'s default world stays `MvpSectorMap.build()` (untouched). The DEVELOPER does NOT edit any `core/src/test/**` file; ALL test files (SectorGeneratorTest, the version-tripwire updates in SaveMigrationTest + SqlDelightSettingsRepositoryTest, and the seed round-trip test) are QA's.

## Challenger verdict
**APPROVED** after one revision round. Verified every load-bearing claim against source: fixtures carry no world graph; Simulation default + MvpSectorMap call site untouched; MVP-seed branch returns authored map verbatim → zero fixture coordinate moves; v22→v23 recipe complete and chain-consistent; generation deterministic via DeterministicRng. The Major resolved: `PlayScreen.currentWorldState()` now threads `worldSeed` (else autosave silently reverts seed to MVP and AC#3 is vacuous), with the round-trip test routed through `currentWorldState()`. Minors resolved: structural guard test (SectorWorld has no equals); connectivity documented as a generator invariant with a mandatory reachability test. Scope boundary (non-MVP worlds get templated markets, no encounter/bounty content) recorded in ADR 0041.
