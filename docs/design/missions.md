# Design Note — Missions

- **Status:** in-progress (MVP mining + courier **implemented in UC12** — see ADR 0011; markers/faction-gen/combat still deferred)
- **Last updated:** 2026-06-08
- **Related:** PROJECT_BRIEF.md → in_scope #2, core_gameplay_loop (Earn); [world-and-sector.md](world-and-sector.md) (stations, asteroids), [economy-and-resources.md](economy-and-resources.md) (rewards, cargo), [upgrades-and-progression.md](upgrades-and-progression.md) (crew rewards), [combat.md](combat.md) (combat missions, later phase), [save-and-persistence.md](save-and-persistence.md) (mission persistence), [adr/0011-missions.md](../adr/0011-missions.md) (the MVP decision record)

## MVP implementation (UC12)

The two MVP types and both sources are implemented as a pure, JVM-testable `mission` package
(ADR 0011). What landed, concretely:

- **Types.** `MINING` (gather a resource quota into the cargo hold, turn it in at any mission-board
  station) and `COURIER` (deliver a **virtual** parcel — a `pickedUp` flag, never a hold item — from
  station A to station B before a tick-based deadline). Two handcrafted templates; **Abandon** was
  dropped from MVP scope and **combat** missions remain a later phase.
- **Procedural instancing — deterministic.** `MissionGenerator` instances offers as a pure function of
  the **static authored** world (stations, sectors, asteroid-field deposits) + the authored
  `MissionParams`. Every procedural choice (resource, quota size, courier destination, deadline, reward
  jitter) is drawn from an explicit **FNV-1a string-hash → LCG** seeded **only** by stable String
  primitives (`PoiId.value` / `SectorId.value` / `ResourceType.name`). It never uses
  `enum`/data-class/identity `hashCode()` — that would be run-dependent and break byte-identical replay.
  Mining quotas are drawn from (and clamped to) the offering sector's field resources, so a mission is
  always completable.
- **Sources.** **Station boards** surface one mining + one courier offer per station (`boardOffers`);
  the **ship radio** surfaces one mining offer per in-range broadcasting station while in flight
  (`radioOffers`, range-filtered exactly like active scanning's `contactsInRange`).
- **Lifecycle.** `Missions.resolve` handles Accept / TurnIn and **automatic courier pickup** on docking
  at the pickup station (no explicit pickup action). `Missions.advance` decrements the courier
  `remainingTicks` once per call; at 0 the mission flips to `FAILED` with a fixed credit penalty (the
  predefined consequence). Mining turn-in consumes the quota from the hold and grants the credit reward.
- **Persistence — regenerate-and-filter.** Only ACTIVE/terminal missions are stored (save schema v10,
  table `mission`). Available offers are **not** persisted: on load they are regenerated from the static
  world and filtered against the persisted accepted/completed/failed ids, so an accepted or resolved
  offer never re-appears and a completed/failed one never re-offers.
- **Timer authority.** The courier timer is **tick-based in the model** (the authority shared by live +
  replay); the device paces one `advance` per fixed real interval so its countdown is frame-rate
  independent, the replay one per fixed sim step.

## Summary

The mission system — **accept → perform → complete → reward** — with a small set of
**handcrafted mission types** that are **procedurally instantiated** from the live sector
state. A primary "Earn" path. MVP launches with **Mining** and **Station-to-station
courier**; **combat missions come in a later MVP phase**.

## Goals

- A few replayable mission types that drive the loop without scripted linearity.
- Missions feel grounded in the world (sourced from sector/faction state, not a static list).
- Available missions **persist** — they are not reset when the game is closed.

## Mechanics / ideas

**MVP mission types (handcrafted templates, procedurally instanced):**
- **Mining** — gather a quota of a resource (from asteroid fields) and deliver/turn it in.
- **Station-to-station courier** — pick up cargo at station A, deliver to station B
  (typically under a time limit).
- _Later MVP phase:_ **Combat** missions (bounties/clear-outs) — see [combat.md](combat.md).

**Generation — procedural from handcrafted types.** Mission *types* are hand-designed
templates; individual mission *instances* are generated procedurally from the current
world state. **Available (offered) missions are persisted** so the offer list doesn't
reset every session.

**Sources (NEW features):**
- **Ship radio communication system** — general mission **broadcasts** the player can
  receive over the ship's radio/comms while roaming. _(New ship system; see Dependencies.)_
- **Station mission boards** — missions available at a station, driven by **the sector(s)
  state** and **the station faction's state**. _(New: station faction state — factions own
  stations and their condition affects what's offered.)_

**Lifecycle:** offered → accepted → in-progress → **completed** | **failed/expired**.
Missions **can fail or time out**, with **predefined consequences** per mission/type.

**Concurrency:** the player can hold **multiple missions at once**.

## Player-facing behavior

- **Mission log** (MVP): view available/active/completed missions, accept and track them.
- Accept missions at a **station** or via the **ship radio** broadcast.
- **Active-mission map/HUD markers:** planned but **post-MVP** — the surfacing approach
  still needs design.

## Data & state

Persisted (see [save-and-persistence.md](save-and-persistence.md)):
- **Available missions per source** (so offers survive a restart).
- **Accepted missions + progress** (quotas filled, cargo carried) and **timers**.
- Completed/failed outcomes as needed for consequences/history.
- Mission **definitions** live as handcrafted **templates** in data; instances reference
  a template + generated parameters + world references (target station, resource, etc.).

## Dependencies & interactions

- **Rewards** flow to [economy-and-resources.md](economy-and-resources.md) (credits,
  items/loot), plus **crew** and **reputation** (tracked for later — see below). _No pilot
  XP — progression is horizontal; see [upgrades-and-progression.md](upgrades-and-progression.md)._
- **Mining** missions depend on **asteroid fields**; **courier** depends on **stations**
  and **cargo** (world & economy).
- **Ship radio/comms** is a new ship system feeding broadcast offers.
- **Station faction state** ties to a **factions/reputation** system.
- **Failure/expiry** needs a time/timer system.

## Open questions

- **Active-mission markers:** how to surface them on map/HUD (still deferred, post-MVP — the mission
  log / board is the MVP surface).
- **Faction/sector-driven generation:** exactly how faction/sector state shapes which
  missions appear and their rewards. _UC12 instances from static sector/asteroid state only;
  faction state does not exist yet (UC14)._
- **Reward balancing + radio cadence:** still placeholders — all live as `[TUNE]` constants in
  `MissionParams` (pinnable per recorded playthrough), so tuning never affects determinism.

_Resolved in UC12 (see ADR 0011 and "MVP implementation" above):_ failure consequence = fixed credit
penalty; radio surfacing = range-based (`MissionParams.radioRange`), no upgrade gating; generation =
deterministic string-hash→LCG from static authored state.

## Decided

- MVP types = **Mining** + **Station-to-station courier**; **Combat = later MVP phase**.
- **Procedural instances from handcrafted mission types.**
- Sources = **ship radio broadcasts** + **station boards (sector/faction-state driven)**.
- **Available missions survive a restart** (no reset on close) — realized via **regenerate-and-filter**
  (ADR 0011): offers are not stored but are regenerated deterministically and filtered against the
  persisted accepted/terminal ids, so the offer list is stable across restarts without storing it.
- **Multiple concurrent missions**; **mission log in MVP**.
- Rewards can include **credits, items/loot, crew, reputation** (no pilot XP — horizontal
  progression).
- Missions **can fail/time out with predefined consequences**.
- **Reputation system: yes, but post-MVP.** Active-mission **markers: post-MVP.**

## References

Starsector contracts/bounties; X4 station/faction missions; Naev mission system (readable
source). See PROJECT_BRIEF.md → Reference Points & Inspiration.
