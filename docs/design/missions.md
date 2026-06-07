# Design Note — Missions

- **Status:** in-progress (MVP types & sources decided; markers/faction-gen detail open)
- **Last updated:** 2026-06-07
- **Related:** PROJECT_BRIEF.md → in_scope #2, core_gameplay_loop (Earn); [world-and-sector.md](world-and-sector.md) (stations, asteroids), [economy-and-resources.md](economy-and-resources.md) (rewards, cargo), [upgrades-and-progression.md](upgrades-and-progression.md) (crew rewards), [combat.md](combat.md) (combat missions, later phase), [save-and-persistence.md](save-and-persistence.md) (mission persistence)

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

- **Active-mission markers:** how to surface them on map/HUD (deferred, needs design).
- **Faction/sector-driven generation:** exactly how faction/sector state shapes which
  missions appear and their rewards.
- **Failure consequences** per type (reputation hit, credit penalty, cargo loss?).
- **Radio comms** specifics: range, how offers surface, any tech/upgrade gating.
- **Reward balancing** across types.

## Decided

- MVP types = **Mining** + **Station-to-station courier**; **Combat = later MVP phase**.
- **Procedural instances from handcrafted mission types.**
- Sources = **ship radio broadcasts** + **station boards (sector/faction-state driven)**.
- **Available missions are persisted** (no reset on close).
- **Multiple concurrent missions**; **mission log in MVP**.
- Rewards can include **credits, items/loot, crew, reputation** (no pilot XP — horizontal
  progression).
- Missions **can fail/time out with predefined consequences**.
- **Reputation system: yes, but post-MVP.** Active-mission **markers: post-MVP.**

## References

Starsector contracts/bounties; X4 station/faction missions; Naev mission system (readable
source). See PROJECT_BRIEF.md → Reference Points & Inspiration.
