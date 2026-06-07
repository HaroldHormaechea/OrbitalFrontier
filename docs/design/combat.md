# Design Note — Combat & Encounters

- **Status:** deferred (real-time decided; full design intentionally postponed)
- **Last updated:** 2026-06-07
- **Related:** PROJECT_BRIEF.md → in_scope #4 (encounters), core_gameplay_loop (Earn); [ship-and-controls.md](ship-and-controls.md) (turrets/crew, sectional damage), [missions.md](missions.md) (combat missions = later MVP phase)

## Summary

Ship-to-ship combat and hostile encounters during roaming and missions. **Combat is
real-time.** Beyond that, detailed design is **intentionally deferred** — combat missions
are a later MVP phase, and the combat system will be specified then. This note still holds
the carried-over constraints (auto-aim crew turrets, sectional damage) so they aren't lost.

## Goals

- _TODO: satisfying top-down combat that's fair on touch and scales with ship upgrades._

## Mechanics / ideas

_TODO: weapons (projectile/beam), targeting, enemy AI (rule-based per brief — no ML), shields/hull/armor, damage model._

**Carried over from [ship-and-controls.md](ship-and-controls.md) (to specify here):**
- **Turrets — auto-aim, crew-gated.** Turreted weapons acquire and track targets
  automatically (no manual aiming) and **require crew** to operate. Specify auto-aim
  targeting priority (nearest / designated / highest-threat), turret arcs, fire rate, and
  the crew-per-turret requirement. Distinct from **fixed/forward weapons**, which fire
  along hull facing via a player action control.
- **Sectional damage** — the HUD shows a ship schematic with **damage per section**, so
  the damage model is **per-section/component**, not a single hull bar. Specify sections,
  per-section effects (e.g. an engine hit reduces speed, a turret hit disables that
  turret, a crew-quarters hit reduces crew), and whether sectional damage persists or
  repairs on dock.
- **Crew** — turrets (and possibly other systems) depend on crew; see the Crew concept
  flagged in [ship-and-controls.md](ship-and-controls.md). May become its own design note.

## Player-facing behavior

_TODO: fire controls, aiming, damage feedback, death/respawn or destruction consequences._

## Data & state

_TODO: combat is runtime; outcomes (loot, bounty payout) flow to economy/progression._

## Dependencies & interactions

_TODO: reads ship stats from upgrades; spawns from world/missions; feeds economy (loot/salvage)._

## Open questions

**Decided:** combat is **real-time**.

_Deferred (to specify when combat is built): weapon types (kinetic/beam/missile),
defenses (shields/armor/hull, per-section), enemy AI behaviors & difficulty scaling,
auto-aim targeting priority, death/stakes (roguelite vs. forgiving), and flee/disengage
options._

## References

Star Valor / Starsector combat feel; libGDX Box2D collision.
