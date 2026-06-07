# Design Note — Station Building (post-MVP / stretch)

- **Status:** draft (out of MVP scope — vision recorded, detail intentionally deferred)
- **Last updated:** 2026-06-07
- **Related:** PROJECT_BRIEF.md → non_goals #2 (explicitly out of MVP), core_gameplay_loop (Improve, deeper); [economy-and-resources.md](economy-and-resources.md) (credits/resources cost, commerce), [upgrades-and-progression.md](upgrades-and-progression.md) (retrofit), [world-and-sector.md](world-and-sector.md) (where stations sit), [save-and-persistence.md](save-and-persistence.md) (schema extensibility)

## Summary

A post-MVP "Improve, deeper" layer: the player builds and grows **multiple personal
stations** used for **commerce, retrofit, and more**, from **modular snap-together
pieces**, paid for with **credits and/or mined resources**. Single-player and offline like
the rest of the game (no networked base-building). **Explicitly NOT in the MVP** — recorded
here so the idea isn't lost and so the MVP is built without precluding it.

## Goals

- A long-term progression/credit/resource sink beyond ship upgrades, with no multiplayer.
- Player-owned infrastructure that plugs into the existing economy (commerce) and
  outfitting (retrofit) systems.

## Mechanics / ideas (deferred — light sketch only)

- **Multiple stations**, each serving roles such as **commerce** (trade hub / passive
  income) and **retrofit** (outfitting/refit, à la a player-owned shipyard/junkyard).
- **Modular, snap-together construction** — assemble stations from modules; expand over
  time.
- **Cost:** built from **credits and/or resources** (the mined materials from
  [economy-and-resources.md](economy-and-resources.md)).
- _Everything below is intentionally undefined for now: module catalog, placement rules,
  defense (ties to deferred combat), passive economics, and crew needs._

## Player-facing behavior

_TODO (post-MVP): build/edit UI, where stations are placed, how they surface in the world
and on the map._

## Data & state

- Station ownership, layouts, and module state will be persisted in the SQLite save.
- **Guardrail (matters for the MVP now):** design the MVP save schema (see ADR 0002 /
  [save-and-persistence.md](save-and-persistence.md)) so player-owned stations can be added
  **as a new versioned migration without a breaking change** — e.g. keep player-owned
  entities and world objects modeled in a way that a "stations" table/section can be
  appended later.

## Dependencies & interactions

- **Economy** (commerce, build costs, passive income), **upgrades/progression** (retrofit),
  **world & sector** (placement near asteroids / jump points), **combat** (defense, if any —
  deferred), **crew** (staffing, if any — deferred).

## Open questions (all deferred)

- Is this ever actually built, or aspirational? If pursued, what's the minimum viable
  version?
- Module catalog and what each module provides.
- Placement (anchored in a sector, at jump points, mobile?).
- Defense vs. purely economic/utility.

## References

X4 Foundations player stations (modular, single-player); Starminer modular stations. See
PROJECT_BRIEF.md → Reference Points & Inspiration.
