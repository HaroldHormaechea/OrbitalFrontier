# Design Note — Ship & Controls

- **Status:** draft (not yet specified)
- **Last updated:** 2026-06-07
- **Related:** PROJECT_BRIEF.md → in_scope #1, core_gameplay_loop (Roam); ADR 0001 (libGDX)

## Summary

The player-controlled single ship and its 2D top-down movement, tuned for touch. The "Roam" pillar of the core loop. _TODO._

## Goals

- _TODO: e.g. responsive touch control that feels good on a phone; readable at a glance._

## Mechanics / ideas

_TODO: movement model (Newtonian drift vs. arcade snap — note the leaning option), turn rate, speed, thrust, Box2D vs. custom._

## Player-facing behavior

_TODO: touch scheme (virtual stick, tap-to-move, drag-to-aim), HUD elements._

## Data & state

_TODO: ship position/velocity is runtime; persisted ship identity/loadout belongs to upgrades-and-progression._

## Dependencies & interactions

_TODO: feeds combat, world-and-sector; reads upgrades (stats)._

## Open questions

_TODO: arcade vs. simulation feel? one control scheme or options?_

## References

Starsector / Star Valor ship handling; libGDX input + Box2D docs.
