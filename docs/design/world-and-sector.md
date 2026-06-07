# Design Note — World & Sector

- **Status:** in-progress (sector model & MVP scope decided; jump/scan/proc-gen detail open)
- **Last updated:** 2026-06-07
- **Related:** PROJECT_BRIEF.md → in_scope #4, core_gameplay_loop (Roam); non_goals (no procedurally-infinite universe in MVP); [missions.md](missions.md), [economy-and-resources.md](economy-and-resources.md) (asteroids/stations), [upgrades-and-progression.md](upgrades-and-progression.md) (scanner/sensor tech), [save-and-persistence.md](save-and-persistence.md) (world state)

## Summary

A **sector** is an **unbounded** 2D area whose relevant content is clustered near its
**center** — you can fly far out, but there's nothing there (the **Egosoft X-series**
model: stations and points of interest sit between the gates, the rest is empty space).
Sectors connect to each other through **jump points**. The MVP ships **3 small sectors**
(roughly **30 seconds to cross** the content area side-to-side) specifically so the
**jump system between sectors** can be built and tested. This is the "Roam" pillar.

## Goals

- A roamable space that feels open but stays **content-dense at the center** — no empty
  grind to reach the fun.
- **Inter-sector travel via jumps** that's exercisable early with a few small sectors.
- **Persist the world; regenerate as little as possible.**

## Mechanics / ideas

**Sector shape — unbounded, center-clustered.** Each sector is effectively an open plane
with all meaningful content (stations, asteroid fields, jump points) gathered near the
middle. Flying outward yields empty space rather than hitting a hard wall. MVP sectors
are small — ~30s to traverse the content area.

**Inter-sector travel — fixed jump gates (ADR 0004).** Sectors are linked by **fixed jump
gates**: each sector has gates at fixed locations, each linking to a gate in an adjacent
sector (a **fixed graph** across the 3 MVP sectors). Flying into a gate transports the ship
to its linked gate in the destination sector. **No fuel cost for the MVP** (soft-fuel
affects in-sector max speed only). The 3-sector MVP exists to validate this loop end-to-end.

**Layout — procedural, with hand-authored test maps.** Content placement is generated
**procedurally**, but the project will start with **hand-made test maps** for
deterministic development/testing. Generation should be **seed-based** so a sector is
reproducible and cheaply persistable (store the seed + deltas rather than every object).

**Points of interest (MVP set):**
- **Jump gates** — fixed sector-to-sector connections (fly in → arrive at linked gate; see
  ADR 0004).
- **Asteroid fields** — mining nodes (→ [economy-and-resources.md](economy-and-resources.md)).
- **Stations** — docking, trade, missions, services. Some are **junkyards** (a station
  variant where used upgrades are sold/removed and ships refitted — see
  [upgrades-and-progression.md](upgrades-and-progression.md)).
- _(Later: derelicts/wrecks, distress signals, hazards — not MVP.)_

**Detection — transponders & active scanning (NEW):**
- Stations and ships broadcast **beacons/transponders** advertising their identity (and,
  for stations, their offers). Transponder-broadcasting POIs appear automatically on the
  HUD/minimap.
- **Hidden contacts** — ships/stations running **without a transponder** are not visible
  until the player **actively scans** for them, using a **ship scanning ability / sensor
  technology** (a progression upgrade). Couples to the sensor/scanner upgrade and a ship
  action control.

**Encounters:**
- **Natural/ambient** — encounters that simply exist in the living world (traffic,
  patrols, pirates roaming), in the spirit of Starsector / X4.
- **Spawned** — encounters driven by **missions or player activity**.

## Player-facing behavior

- Fly out from the central content cluster (the periphery is mostly empty). The minimap /
  sector map shows transponder-broadcasting POIs; **scan** to reveal hidden ones.
- Use **jump points** to travel between the 3 sectors.

## Data & state

Persist aggressively (the design goal is minimal regeneration):
- **Sector seed/layout** so each sector is stable across sessions.
- **POI state** — known/discovered POIs, **asteroid field depletion status**, **station
  offers/prices/inventory**.
- **Player-revealed hidden POIs** (once scanned, remembered).
- Encounter/spawn bookkeeping as needed.
- Serialized via [save-and-persistence.md](save-and-persistence.md).

## Dependencies & interactions

- **Hosts** missions, combat, and economy (asteroid mining nodes, station markets).
- **Transponder/scan** couples to **upgrades & progression** (sensor/scanner tech) and a
  ship ability + the HUD.
- The **jump mechanic** is effectively its own sub-system (candidate for an ADR once the
  approach — gates vs. jump drive — is chosen).
- **Save & persistence** owns serialization of all sector state above.

## Open questions

- ~~Jump mechanic~~ — **RESOLVED: fixed jump gates, no MVP fuel cost (ADR 0004).** Remaining
  detail: gate visuals and the jump transition (loading/animation).
- **Procedural generation:** how is content density and placement determined? Seed source
  and what parameters vary per sector?
- **Scanning:** scan range/time, what qualifies as "hidden," and scanner upgrade tiers.
- **Unboundedness:** truly infinite plane vs. a very large soft boundary?
- **Station services scope** for MVP (trade + missions + repair — which are in?).

## Decided

- Sector = **unbounded, content-clustered-at-center** (Egosoft X-series style).
- **MVP = 3 small sectors** (~30s to cross), to build and test **jumps**.
- **Inter-sector travel = fixed jump gates** (fixed graph; no MVP fuel cost) — **ADR 0004**.
- **Procedural** layout, with **hand-authored test maps** early.
- POI MVP set = **jump points, asteroid fields, stations**.
- **Transponders/beacons** advertise POIs; **active scanning** reveals hidden ones.
- Encounters are **natural + spawned**.
- **Persist** POIs, asteroid statuses, station offers; regenerate as little as possible.

## References

X-series / X4 (Egosoft) sector model and ambient traffic; Starsector ambient encounters;
Naev sensors/beacons. See PROJECT_BRIEF.md → Reference Points & Inspiration.
