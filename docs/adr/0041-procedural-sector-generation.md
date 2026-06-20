# ADR 0041 — Procedural sector generation

- **Status:** Accepted
- **Date:** 2026-06-20

## Context

UC53 turns the single hand-authored 3-sector map (`MvpSectorMap.build()` → `SectorWorld`) into
**seed-based procedural generation**, the open item in `docs/design/world-and-sector.md` ("Layout —
procedural, with hand-authored test maps" / "Procedural generation: how is content density and
placement determined? Seed source and what parameters vary per sector?"). The design goal there is
explicit: *persist the seed + deltas, regenerate the graph* rather than serialize every object.

Binding constraints:

- **Zero fixture regen (overriding).** The world graph is **not** serialized into the ~23 replay
  fixtures — they carry only the combat/mission RNG `seed` + `SimulationState`. Every fixture, every
  save, and every new game today resolves to `MvpSectorMap.build()` via the test `Simulation`'s
  default world and the production call site. Any change that moves an authored POI coordinate, or
  changes the default world, breaks all of them.
- **Determinism (ADR 0006/0011/0012).** `core` stays JVM-testable (ADR 0001); generation must be a
  pure function of the seed (no wall clock, no `Math.random`, no enum/identity `hashCode`) so a
  replay capture can be re-pinned to a fixed seed (AC#4).
- **Connectivity (ADR 0004, AC#2).** A generated world must stay a connected jump-gate graph — every
  sector reachable.
- **minSdk-24.** The one new persisted datum (the seed scalar) lands via `ALTER TABLE ADD COLUMN`
  only — no UPSERT, no `java.time`.

## Options considered

| Option | For | Against |
|---|---|---|
| **Additive generator behind `SectorGenerator.generate(seed)`; reserved `WorldSeed.MVP = 0` returns `MvpSectorMap.build()` verbatim; `WorldState.worldSeed` defaults to MVP; one additive `world_seed` DB column DEFAULT 0** | Zero fixture regen *by construction* — the default path is byte-identical authored data; the procedural branch is new behaviour reachable only via a non-zero seed; only a scalar is persisted | One new column + migration + regenerated `.db` + the version-tripwire churn that every schema bump carries |
| Replace `MvpSectorMap` with generation tuned to reproduce the authored coordinates | One world path | Reproducing authored coordinates bit-exactly is fragile; any drift re-records every fixture; throws away a known-good authored map |
| Serialize the generated graph into the save / fixtures | World fully reproducible from the row | Defeats the design goal (store seed + deltas, not the graph); fattens every fixture; forces a fixture re-record |

## Decision

**Additive generation; the authored map stays the canonical default.**

- `WorldSeed(Long)` (`@JvmInline value class`) with a reserved `companion val MVP = WorldSeed(0L)`.
- `SectorGenerator.generate(seed)`: for `WorldSeed.MVP` it returns `MvpSectorMap.build()` **verbatim**
  (delegation — same authored contents); for any non-zero seed it generates procedurally, deriving
  every choice from `DeterministicRng` (fnv1a seed → LCG) **only**.
- **Connectivity is a generator invariant, not a `SectorWorld` backstop.** Generated sectors are
  wired into a **ring** of reciprocal gates (`sector_i ↔ sector_{i+1 mod N}`), plus an optional
  seed-decided **chord** for N ≥ 4. A ring is connected for any N ≥ 2; for N = 3 it is the complete
  triangle (the authored topology). `SectorWorld` still re-validates reciprocity + graph-global POI-id
  uniqueness and fails fast, but it does **not** enforce reachability — a mandatory AC#5 reachability
  test (BFS over gates) pins the invariant.
- **Templates + jitter** (the use-case "seeded-but-curated" decision, not full proc-gen): per sector,
  1 station + 1 asteroid field + 0–1 hidden contacts, positions placed by seeded angle/radius (reused
  `Vec2.fromAngle`), with globally-unique ids (`sector{i}-station`, `sector{i}-belt`,
  `sector{i}-to-{j}`, `sector{i}-contact`). `[TUNE]` defaults: N ∈ [3, 5] sectors; ring + optional
  chord; a fixed templated market per station; asteroid deposits drawn in `[10, 40]` units.
- **Persistence:** `WorldState.worldSeed: WorldSeed = WorldSeed.MVP` (the only new persisted datum),
  carried on the save header via a new `game_state.world_seed INTEGER NOT NULL DEFAULT 0` column
  (additive minSdk-24-safe migration `22.sqm`, regenerated `23.db`, schema v22 → v23). It is
  deliberately **NOT** part of `SimulationState`, so replay equality is untouched. `PlayScreen` holds
  the session seed and re-emits it from `currentWorldState()` so every autosave persists it (without
  this, autosave silently reverts the seed to MVP and AC#3 is vacuous).
- **The default new-game path keeps seeding `WorldSeed.MVP`** ⇒ the authored map ⇒ production
  behaviour unchanged. There is no "new random world" UI in this UC.

## Consequences

- **Zero fixture regen holds by construction:** the test `Simulation` default world and the
  `MvpSectorMap` call site are untouched; the DB column DEFAULTs to 0; the MVP-seed branch returns the
  authored map verbatim. A structural guard test pins `generate(MVP) ≡ MvpSectorMap.build()`
  field-by-field (sector ids/order, per-POI ids/positions/subtype) — `SectorWorld` has no `equals`, so
  the guard is structural, not `==`.
- **Scope boundary — markets only.** A non-MVP world gets templated **markets**; it has **no
  encounter / bounty content**. The natural-encounter zones, bounty zones, bounty contracts and the
  curated per-station markets are consumed via the static `MvpSectorMap.*` tables keyed by the literal
  `"alpha"`/`"beta"`/`"gamma"` ids, so they simply never apply to a generated sector. Wiring
  encounters/bounties/curated economy into generated worlds is a deliberate **deferred follow-up**.
- **Migration cost:** one additive column + `22.sqm` + regenerated `23.db`; `SaveVersion.CURRENT` →
  23; the version-tripwire tests (`SaveMigrationTest` new v22→v23 step, `SqlDelightSettingsRepositoryTest`
  22→23 literals) move forward. A pre-UC53 save backfills `world_seed = 0` ⇒ the authored map ⇒
  behaviourally identical.
- **Reversibility:** generation is purely additive; reverting means routing `generate` to always
  return `MvpSectorMap.build()`. The persisted seed column is harmless at 0.
- **Deferred:** density/parameter tuning (`[TUNE]`), encounter/bounty/curated-market generation, and
  any new-random-world entry point.
