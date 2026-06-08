# ADR 0010 — Crew: persisted per-ship count, derived capacity, one-time hire, turret operability as a pure flag

- **Status:** Accepted
- **Date:** 2026-06-08
- **Refines:** [ADR 0002](0002-persistence-sqlite-migrations.md) / [ADR 0003](0003-persistence-access-layer-sqldelight.md) (persistence + sequential migrations), [ADR 0008](0008-fleet-and-outfitting-persistence.md) (per-ship state; capacity is a *derived* stat, never stored), [ADR 0007](0007-trading-prices.md) (authored station data reconstructed, not row-persisted), [ADR 0006](0006-determinism-and-playthrough-harness.md) (record/replay determinism), and [ADR 0001](0001-engine-choice.md) (`core` stays JVM-testable). Realizes use-case 11 (crew).

## Context

`PROJECT_BRIEF.md` → core_gameplay_loop ("Improve") and the ship/combat design notes call for
**crew** as a ship resource: a ship has a crew **count** and a crew **capacity** (from its
crew-quarters slot, UC09), crew is **hired at stations for credits** (UC08) up to capacity, and
**turrets require crew to operate** — an under-crewed turret is inoperable, a flag the future combat
model (UC13) reads. Crew state must persist (AC#4) and the logic must be pure/JVM-testable (AC#5),
with a recorded playthrough asserting both the crew count and the turret-operability flag (AC#6).

UC09 already added `ShipStats.crewCapacity(type, loadout)` (base + crew-quarters deltas) and
`StatDelta.crew`, so **capacity already exists as a derived stat**. UC11 adds only the *count* and the
*operations* on it. Three forces shape the decision:

- **Combat does not exist yet.** Turrets are a UC13 concern. UC11 must deliver turret *operability*
  without building any combat, or it over-reaches and couples to a system that isn't designed.
- **The byte-identical / determinism contract** (ADR 0006/0008): adding crew must not change any
  pre-UC11 number, so existing fixtures still replay bit-for-bit.
- **MVP economy scope:** the brief defers ongoing systems; wages would add a per-tick credit drain
  and a whole balancing surface.

## Options considered

| Option | For | Against |
|---|---|---|
| **Persist crew *count* on `OwnedShip`; capacity stays derived; one-time hire cost; turret operability = pure derived flag `(crew, requiredCrew)`** | Mirrors the UC06/07/08 "store the variable, derive the capacity" precedent exactly; additive `ship.crew DEFAULT 0` migration → old saves read back uncrewed (byte-identical); no combat built, yet UC13 has a stable seam to extend; pure `Hiring`/`TurretOperability` are trivially JVM-testable | Adds one more per-ship field to thread through the sim + snapshot DTO + recorder (the same plumbing every prior per-ship resource needed) |
| Store crew capacity as a column too | Simpler load (no derivation) | Re-introduces the stale-capacity bug ADR 0008 deliberately avoided; a crew-quarters retune would silently invalidate saves |
| Build turret entities now and gate them imperatively | "Complete" combat-ish feature | Invents UC13's combat model unspecified; couples crew to non-existent turrets; large, speculative, untestable surface |
| Ongoing wages (per-tick credit drain) | Richer economy | Out of MVP scope (brief defers ongoing systems); adds a balancing + determinism surface for no MVP payoff |

## Decision

1. **Crew is a persisted per-ship COUNT; capacity stays derived.** `OwnedShip` gains `crew: Int = 0`
   (new ships start uncrewed). Capacity is **not** stored — it remains `ShipStats.crewCapacity(type,
   loadout)`, re-derived on load and after every fit change. `OwnedShip.withCrew(n)` clamps to
   `0..crewCapacity` (the single crew-mutation point), and `withLoadout` re-clamps crew to the new
   capacity so removing a crew-quarters part can never strand crew above its berths. AC#1's "capacity
   from the crew-quarters slot" is satisfied via `base + Σ crew deltas`; today capacity equals
   `baseCrewCapacity` because no crew-quarters upgrade exists in the catalog yet — the seam is in place
   for one to be added.
2. **Hiring is pure and one-time-cost.** `Hiring.resolve(credits, currentCrew, crewCapacity,
   offersCrew, order, pricePerCrew = HIRE_COST_PER_CREW)` returns a `HireResult`, mirroring
   `Trading`/`FleetResolver`. Hire amount = `min(requested, crewCapacity − currentCrew, credits /
   pricePerCrew)` — **clamp-to-remaining** (over-capacity excess is rejected, the rest is hired, like
   `Trading.resolveBuy`), `≤ 0` is a no-op, a station that doesn't hire crew (`offersCrew = false`) is
   a no-op, and `pricePerCrew > 0` is required (div-by-zero guard). **No ongoing wages** in the MVP
   (a one-time credit cost only); wages are deferred future work.
3. **Turret operability is a PURE derived flag — no combat.** `TurretOperability.turretsOperable(crew,
   requiredCrew = MVP_TURRET_CREW_REQUIREMENT)` = `requiredCrew <= 0 || crew >= requiredCrew`. UC11
   builds **no** turrets and **no** combat; it only exposes the flag the UC13 combat model will read.
   The `(crew, requiredCrew)` signature is the deliberate **stable seam** UC13 swaps per-turret
   crew-requirement logic into (computing `requiredCrew` per turret instead of the single MVP
   constant).
4. **Authored tunables.** `HIRE_COST_PER_CREW = 100` and `MVP_TURRET_CREW_REQUIREMENT = 1` are single
   authored `[TUNE]` constants. The requirement (1) is at/below the starter ship's crew capacity (2),
   so the **first** hire flips turrets from inoperable to operable.
5. **Hiring is a station capability, gated on a flag.** `Station.hiresCrew: Boolean = false` (authored
   map data, **not** persisted — the ADR 0007/0008 precedent); one station (Alpha Station) sets it
   `true`. The device path and the deterministic simulation's docked-hire branch MUST resolve against
   the **same** capacity source (`active.type + active.loadout`) and post-fleet credits, so live and
   replayed hiring match.
6. **Persistence is additive (save v8 → v9).** `8.sqm` does `ALTER TABLE ship ADD COLUMN crew INTEGER
   NOT NULL DEFAULT 0` + `UPDATE meta SET save_version = 9` (minSdk-24-safe). A migrated save reads
   back with 0 crew and inoperable turrets until crew is hired. The repository load coerces a
   corrupt/over-capacity `crew` value into `0..crewCapacity` ("never stranded"); `SaveVersion.CURRENT`
   is bumped to 9 to match the generated schema version.

## Consequences

- **Determinism preserved.** `crew` is additive and defaulted to 0 on `OwnedShip`; a new game seeds
  crew 0; pre-UC11 fixtures default crew 0 with no hire order → byte-identical replay. The
  record/replay harness threads a `HireOrder` like every prior per-ship action.
- **UC13 is unblocked, not pre-empted.** Combat reads `TurretOperability.turretsOperable` and overrides
  `requiredCrew` per turret without touching crew storage, hiring, or the save schema. The flag is
  computed on demand from `crew` — there is no operability state to persist or keep in sync.
- **Capacity correctness is free.** Because capacity stays derived, adding a crew-quarters upgrade
  later (a `StatDelta.crew` > 0 part) raises capacity with no migration, and `withLoadout` already
  re-clamps crew on removal.
- **Economy stays simple.** One-time hire cost means no per-tick wage accounting and no new
  determinism surface. Reintroducing wages later is a new resolver + an ADR, not a save-schema change
  (crew count is already persisted).
- **Reversibility.** The hire price, the per-crew/per-turret requirements, and which stations hire crew
  are all authored data — retunable without touching saved data. Crew count, once persisted, is the
  durable bit; the rules around it are cheap to change.
