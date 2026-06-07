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

**Soft content extent (authored constant, implemented UC03).** "Unbounded" is realized as
a **soft content radius**, not a hard wall: each MVP sector has a `contentExtent` of
**~1800 world-units (~3600 wu across)**, which at the ship's max speed (120 wu/s,
`ShipMovementParams.maxSpeed`) is the **~30s-to-cross** figure above. The value is an
**authored tunable** carried on each `Sector` (source of truth: `MvpSectorMap`,
`CONTENT_EXTENT_WORLD_UNITS`), used to scale the minimap and to place content/gates near the
centre — the ship may still fly arbitrarily far past it into empty space. It is a balancing
knob, **not a contract**, so it is tuned here / in the map data, **not via a new ADR**.
Gates orbit the centre at ~1300 wu with a per-gate trigger radius (~80 wu); on a jump the
ship arrives offset toward the destination centre by `triggerRadius + margin` (~40 wu) so it
lands just outside the destination gate's trigger circle and cannot immediately bounce back.

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

**Stations & docking (implemented UC05).** Stations are a POI type (`world.Station`) that
broadcasts a `Transponder` (`contactKind = STATION`), so they appear automatically on the minimap
alongside gates — the minimap renders every transponder POI, keyed by contact kind (station = filled
square, gate = dot), an Open/Closed seam so future broadcasting POIs need no minimap rewrite. Each
station carries an authored **`dockingRadius`** (~100 wu) circle; the MVP map authors one station in
Alpha and one in Beta (Gamma has none), placed clear of the gate triggers and inside the content
extent.

- **Dock trigger — proximity + explicit action (never automatic).** Flying within a station's
  `dockingRadius` makes it *dockable*: the HUD shows an "IN RANGE: <name>" prompt and a context
  **DOCK** button. Docking only happens when the player taps DOCK — proximity alone never docks
  (the chosen disambiguation; auto-dock was rejected). The dock/undock decision is pure and
  JVM-testable (`world.Docking`: `availableStation(...)` for the in-range check, `resolve(...)` for
  the state transition), the docking analogue of `GateTraversal`.
- **Dock state is game state and persists.** `WorldState.dockedStation: PoiId?` (null = in flight)
  is part of the save header (`game_state.docked_station_id`, schema v3; sequential migration
  `2.sqm`, ADR 0002/0003). Saving while docked and reloading resumes **docked at the same station**.
  On load a docked state whose station no longer resolves (stale id / map change) **degrades
  gracefully to flight** with a WARN — never a crash or a stranded player.
- **Movement freezes while docked.** Docking hands control to a dedicated **station-hub screen**
  (`screen.StationHubScreen`); the flight screen (`PlayScreen`) is not rendered or updated while
  docked, so the ship's movement/simulation is frozen — there is no flying inside a station. The hub
  shows the station name and **inert** service stubs (TRADE / OUTFIT / MISSIONS — labels with no
  behaviour yet; wired by economy UC08, upgrades UC09, missions UC12) plus one active **UNDOCK**
  control that returns to flight (resolving dock state back to null, then autosaving). Because the
  dock control is just another HUD actor and undocking restores the same flight screen, **flight
  controls and multitouch are untouched while undocked** (the hub is a separate screen, so it cannot
  interfere with the joystick/action cluster). The game owns both screens and disposes each
  explicitly (libGDX `setScreen` only hides the previous screen) to avoid leaking GL resources.

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
- ~~Unboundedness~~ — **RESOLVED (UC03): a soft content extent, not a hard wall.** Each MVP
  sector carries an authored `contentExtent` (~1800 wu radius); the ship may fly past it into
  empty space. A tunable, not an ADR-level decision (see "Soft content extent" above).
- ~~Docking trigger~~ — **RESOLVED (UC05): proximity + explicit DOCK action**, never auto-dock
  (see "Stations & docking" above). A tunable (`dockingRadius`), not an ADR-level decision.
- **Station services scope** for MVP (trade + missions + repair — which are in?). UC05 lands the
  hub **shell** with inert TRADE/OUTFIT/MISSIONS stubs; *which* services and their behaviour are
  still open and owned by the later service UCs (economy UC08, upgrades UC09, missions UC12).

## Decided

- Sector = **unbounded, content-clustered-at-center** (Egosoft X-series style).
- **MVP = 3 small sectors** (~30s to cross), to build and test **jumps**.
- **Inter-sector travel = fixed jump gates** (fixed graph; no MVP fuel cost) — **ADR 0004**.
- **Procedural** layout, with **hand-authored test maps** early.
- POI MVP set = **jump points, asteroid fields, stations**.
- **Transponders/beacons** advertise POIs; **active scanning** reveals hidden ones.
- **Docking = proximity + explicit action** (UC05); dock state persists; movement freezes while
  docked; station hub holds inert service stubs for later UCs.
- Encounters are **natural + spawned**.
- **Persist** POIs, asteroid statuses, station offers; regenerate as little as possible.

## References

X-series / X4 (Egosoft) sector model and ambient traffic; Starsector ambient encounters;
Naev sensors/beacons. See PROJECT_BRIEF.md → Reference Points & Inspiration.
