# ADR 0042 — Additional POI types (derelicts, distress signals, hazards)

- **Status:** Accepted
- **Date:** 2026-06-20

## Context

`docs/design/world-and-sector.md` flagged "(Later: derelicts/wrecks, distress signals, hazards — not
MVP.)". UC54 fills in that content variety to enrich the **Roam** pillar (PROJECT_BRIEF.md core loop) and
give scanning (UC10) and combat (UC13/UC45) more to interact with. Three new point-of-interest kinds are
needed: a **derelict/wreck** the player scavenges for resources, a **distress signal** that branches into a
mini-event (reward or ambush), and an environmental **hazard zone** that affects the ship while traversed.

The overriding constraint is the project-wide one: **zero regeneration of any committed replay fixture**
(record/replay byte-identity, ADR 0006). New content that perturbs an existing fixture's deterministic
stream — its RNG draws, its serialized snapshot bytes, or its end state — is a release-blocking failure.

## Options considered

| Option | For | Against |
|---|---|---|
| **Additive `Poi` subtypes + reuse of detection/loot/spawn/persistence seams, placed disjoint from every fixture path** | Open/Closed (new subtypes, no central `when` edits); reuses UC10 scan, UC42 loot/fill, UC13/45 spawn, UC10 seed+delta persistence; zero fixture regen by construction | Touches many seams (detection, render exhaustiveness, schema); requires careful disjoint placement |
| New world layer / separate "events" system parallel to `Poi` | Conceptually clean separation | Forks detection + rendering + persistence; duplicates the loot/fill/spawn logic; large surface; higher regression risk |
| Procedural placement (pair with UC53) | Natural fit for variety | The replay harness builds `Simulation` with the default `MvpSectorMap.build()` and injects no non-MVP world, so a generated placement can't be replayed — **out of scope**, documented follow-up |

## Decision

Add three additive subtypes of the sealed `Poi` hierarchy:

- `Derelict` (`Poi, Contact` — **not** `Transponder`): scan-only, `salvageRadius`, `ContactKind.DERELICT`.
- `DistressSignal` (`Poi, Transponder`): `triggerRadius`, `ContactKind.DISTRESS`.
- `HazardZone` (`Poi, Transponder`): `radius` + `fuelDrainPerSecond`, `ContactKind.HAZARD`.

**Detection split (AC#3).** `Scanning.contactsInRange` is generalized from "hidden contacts" to **any
`Contact` that is not a `Transponder`** — so derelicts (scan-only) reveal into the same monotonic
`revealedContacts` set as `HiddenContact`s, through the shared `Contact` capability, with no forked detection
path. Distress + hazard broadcast (`Transponder`) and auto-show.

**Shared resolvers, wired in lockstep.** Three pure, engine-free, JVM-testable resolvers — `DerelictSalvage`
(`ScavengeAction{NONE,SCAVENGE}`), `DistressEvent` (edge-triggered reward XOR ambush), `HazardEffect`
(per-tick fuel drain) — are wired **identically** into `screen/PlayScreen.kt` (production) and
`sim/Simulation.kt` (the test-set mirror), run after movement/mining/scan/missions/salvage and before combat,
so live == replay (project rule #1).

**Reuse, not duplication.** (1) Loot rolls through an extracted `LootTable.roll(loot: ArchetypeLoot, seedKey)`
core with a first-class `LootTable.DERELICT` profile (no fake hostile archetype). (2) Cargo fill goes through
one shared `Salvage.fillCargo(cargo, resources)` helper that both `Salvage.collect` and the new resolvers
call.

**Distress branch + ambush.** The reward/ambush branch is decided by a fresh `DeterministicRng` namespace
`"distress:$id"` (one FNV-1a→LCG step, 2-bucket draw) — independent of every existing stream. An ambush
spawns via `EncounterSpawner.missionSpawn(zoneId = "distress:$id")` and is deliberately **NOT** added to
`ENCOUNTER_ZONES`, so UC42's `encounterZones(alpha).single()` invariant is untouched; distress lives in Beta.

**Hazard never bricks (AC#2).** The per-tick drain is `fuelDrainPerSecond × dt`, clamped at an empty tank by
`Fuel.consume`; the existing `FuelParams.floorSpeedFraction > 0` speed floor guarantees the ship can always
limp out of a hazard — slowed, never stranded.

**Persistence (AC#4).** One new save-wide delta `consumedPois: Set<PoiId>` (scavenged derelicts + triggered
distress signals), mirroring `revealedContacts` exactly: monotonic, `INSERT OR IGNORE`, never re-emitted.
Schema bump **v23 → v24** adds a per-slot `consumed_poi(slot_id, poi_id)` table (additive, minSdk-24-safe, no
UPSERT — precedent `20.sqm`); the baseline `24.db` is regenerated. The `StateSnapshotDto.consumedPois`
serialized field is `@EncodeDefault(NEVER)` so a pre-UC54 snapshot omits the key → byte-identical bytes.

**Zero-regen Beta placement.** The three POIs are authored in the **deep south of Beta** —
`beta-derelict (-600,-1000)`, `beta-distress (-300,-1050)`, `beta-hazard (-750,-700)` — provably disjoint from
every committed fixture's Beta path: the UC03 jump flies the `y≈0` corridor east from `beta-to-alpha (-1300,0)`;
`beta-station (300,-300)`, `beta-belt (-500,500)` and the `beta-regroup-picket` zone `(600,700)` all sit at
`y ≥ -300`; the nearest is ~1100 wu from the cluster (far outside any new POI's radius) and the corridor is
>460 wu away. Triggers are action-/geometry-gated, new state fields default empty, and all new outcomes use
fresh RNG namespaces — so existing fixtures touch these POIs **zero** times.

## Consequences

- New POI variety plugs in through the existing Open/Closed seams; future kinds add a subtype + a
  `ContactKind` value + render cases, no central rewrites.
- The `consumed_poi` table is the only schema change; everything else is additive/defaulted, so the upgrade
  path is a single forward migration and every prior save reads back with nothing consumed.
- **Procedural placement is deferred** — the replay harness cannot inject a non-MVP world, so generated
  hazards/derelicts/distress can't be replayed today. When UC53's generator content is replay-pinnable, these
  POIs can be templated into generated sectors behind the same `SectorWorld` type.
- Distress reward/ambush balance (`DistressParams`) and the per-POI tunables are `[TUNE]` placeholders; a
  later balance pass can adjust them. The branch outcome depends only on the signal id, so tuning the
  reward/ambush payloads never changes which signals reward vs. ambush.
- No new atlas art ships this UC — the new world glyphs / minimap markers reuse existing regions
  (contact / asteroid), documented at the call sites; bespoke art is a later polish pass.
