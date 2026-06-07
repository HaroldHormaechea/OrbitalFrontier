# Design Note — Power & Energy

- **Status:** in-progress (confirmed as a feature; mechanics to define)
- **Last updated:** 2026-06-07
- **Related:** [economy-and-resources.md](economy-and-resources.md) (fuel = hydrogen; consumption), [upgrades-and-progression.md](upgrades-and-progression.md) (modules, reactor/power category), [ship-and-controls.md](ship-and-controls.md) (engine/RCS draw), [combat.md](combat.md) (weapons/shields draw — later)

## Summary

A ship **power/energy** system: installed modules **consume energy**, and that energy
demand is what drives **fuel (hydrogen) burn** (see
[economy-and-resources.md](economy-and-resources.md) → fuel consumption = base ship draw +
**installed-module energy use** + active engine/RCS use). Confirmed as its own feature
rather than folded into a single fuel number. Exact model is **to be defined** — see Open
questions and the proposal below.

## Goals

- Make ship fitting a meaningful tradeoff: more/heavier modules cost more energy (and thus
  fuel), so builds balance capability against running cost.
- Keep it legible on mobile — not a spreadsheet.

## Mechanics / ideas

_To be defined with the owner._ Starting proposal (placeholder — confirm/replace):
- A **reactor** (ship-type base + a power/reactor upgrade category) produces an **energy
  output**.
- **Modules draw energy** (weapons, shields-later, engines, sensors/scanner, comms,
  turrets, life support, etc.).
- **Fuel (hydrogen) feeds the reactor**; burn scales with how much energy is being
  produced/drawn.
- Possible **power budget cap**: total module draw can't exceed reactor output (forces
  fitting tradeoffs), with an optional **capacitor/battery** buffer for bursts.

## Player-facing behavior

_TODO: how power is surfaced (a power bar / per-system indicators), and whether the player
manages allocation or it's automatic._

## Data & state

- Per-ship reactor output and per-module energy draw derive from ship config + installed
  upgrades (persisted via the ship loadout — see
  [save-and-persistence.md](save-and-persistence.md)).
- Runtime energy/capacitor levels are transient.

## Dependencies & interactions

- **Fuel/economy** — energy demand sets hydrogen burn rate.
- **Upgrades** — a reactor/power category; every module carries an energy cost.
- **Ship & controls** — engine/RCS draw.
- **Combat** (later) — weapons/shields as major energy consumers.

## Open questions

- **Pool vs. rate:** is there a hard **power budget** (reactor output caps total module
  draw), or is energy just a **consumption rate** feeding fuel burn with no cap?
- **Reactor:** a dedicated upgrade category? Does ship type set base output?
- **Fuel relationship:** does fuel feed a reactor that makes energy, or is "energy" just an
  accounting layer over fuel draw?
- **Capacitor/battery** buffer for burst loads (weapons, jump)?
- **Allocation:** can the player prioritize power between systems (X-series / Trek style),
  or is it automatic?
- **Brownout behavior:** what happens when demand > supply (throttle systems? disable
  lowest-priority? slow the ship)?

## References

X-series power/shield management; FTL-style power allocation (as a simplicity reference).
See PROJECT_BRIEF.md → Reference Points & Inspiration.
