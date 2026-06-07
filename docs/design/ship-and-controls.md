# Design Note — Ship & Controls

- **Status:** in-progress (core model decided; action set, crew & some params open)
- **Last updated:** 2026-06-07
- **Related:** PROJECT_BRIEF.md → in_scope #1, core_gameplay_loop (Roam); ADR 0001 (libGDX); [combat.md](combat.md) (turrets/weapons, auto-aim), [upgrades-and-progression.md](upgrades-and-progression.md) (per-ship stats, crew), [save-and-persistence.md](save-and-persistence.md) (loadout/damage/settings state)

## Summary

The player controls a single ship in a 2D top-down sector. Movement uses **inertial
drift** — the ship carries momentum — but to keep it easy on touch, releasing the
controls makes the ship **decelerate smoothly to a stop** rather than coasting forever.
The feel is deliberately **~50% arcade / 50% simulation**. Control is a **single
movement joystick on the left** plus a set of **action controls on the right**;
turreted weapons are **auto-aim and require crew** (so the player never manually aims
turrets — that would overload touch controls). A **settings option swaps the left/right
layout** for handedness (e.g. left-handed players). This is the "Roam" pillar of the
core loop.

## Goals

- Pick-up-and-play on a phone, but with momentum-based depth (not pure arcade snap).
- Keep on-screen controls minimal — one movement stick + a small action cluster.
- Different ship configurations feel meaningfully different to fly (per-ship params).
- Accessible: support left- and right-handed layouts.

## Mechanics / ideas

**Movement model — inertial drift with auto-decay.** The ship accelerates under thrust
and retains velocity (momentum). When the player releases the movement stick, velocity
**decays gradually to zero** (a configurable deceleration) so the ship eases to a stop —
simplifying control while preserving a sense of mass.

**Controls layout:**
- **Left — movement virtual joystick.** Sets the direction the ship moves/thrusts; the
  hull rotates toward that direction using the ship's rotation params (see open question
  on facing vs. vector).
- **Right — action controls.** Buttons for ship actions (fire fixed/forward weapons,
  activate abilities/devices, etc. — _TODO: enumerate the MVP action set_).
- **Handedness setting.** A configuration option mirrors the layout (movement stick on
  the right, actions on the left) for left-handed players.

**Weapons & turrets:**
- **Fixed/forward weapons** fire along hull facing, triggered by an action control.
- **Turrets are auto-aim.** They acquire and track targets automatically (no manual
  aiming). **Turrets require crew to operate** — a ship without sufficient crew cannot
  run all its turrets. This introduces a **Crew** concept (see Dependencies). Turret
  count/arcs and crew requirements are part of the ship config. Targeting/firing detail
  lives in [combat.md](combat.md).

**Per-ship movement parameters** (part of ship config; modified by upgrades):
- `max_acceleration` — forward thrust
- `max_speed` — top forward speed
- `max_reverse_speed` — braking / reverse cap
- `rotation_acceleration` — how fast turn rate ramps up
- `max_rotation_speed` — turn rate cap (º/sec)
- `drift_decay` — auto-stop deceleration when controls released _(TODO: global constant or per-ship?)_

**Physics:** libGDX **Box2D** for movement integration and collision.

**Fuel coupling.** Fuel is the **Hydrogen** resource. `max_speed` is **modulated by fuel**
— low fuel lowers the effective top speed (a soft constraint; the player is never
stranded). **Engine/RCS thrust consumes fuel when triggered** (coasting on momentum is
cheap; burning thrust costs fuel), on top of a base ship draw and installed-module energy
use. Full fuel model lives in [economy-and-resources.md](economy-and-resources.md).

**Multiple ships.** The player can own several ships and **switch the active one while
docked**; each ship has its own movement params, loadout, cargo, and fuel. See
[economy-and-resources.md](economy-and-resources.md) and
[upgrades-and-progression.md](upgrades-and-progression.md).

## Player-facing behavior

- **Left** movement joystick, **right** action-button cluster (swappable via the
  handedness setting).
- **HUD:**
  - current **speed**
  - **heading** indicator
  - small **minimap** — _tap to open a larger map (post-MVP feature)_
  - small **ship schematic showing per-section damage** (damage info per ship section)
- _TODO: thrust/maneuver visual feedback; how active turrets/targets are indicated._

## Data & state

- **Runtime only:** position, velocity, rotation, angular velocity.
- **Persisted (via ship loadout):** ship config — movement params, turret hardpoints,
  and crew assignment — owned by [upgrades-and-progression.md](upgrades-and-progression.md),
  serialized through [save-and-persistence.md](save-and-persistence.md).
- **Settings:** handedness/control-layout preference is persisted.
- **Per-section damage state:** ties to the combat damage model; _TODO: persist between
  sessions or reset on dock/repair?_

## Dependencies & interactions

- Reads ship stats from **upgrades & progression** (engine/hull upgrades change movement
  params; turret slots and crew capacity are ship-config concerns).
- **NEW — Crew system.** Turrets require crew; crew may gate other systems too. Where
  crew comes from (hired/found), per-ship capacity, and what else needs crew are open —
  this likely warrants its **own design note** if it grows beyond turret-gating.
- Couples to **combat** (auto-aim turret targeting, fixed-weapon fire, sectional damage).
- Operates within **world & sector** as the play space.

## Open questions

- **MVP action set:** exactly which right-side actions exist (fire fixed weapons,
  boost/afterburner, special ability/device, dock/interact)?
- **Crew:** source, per-ship capacity, crew-per-turret ratio, and whether anything
  besides turrets requires crew. → candidate for a dedicated Crew design note.
- **Auto-aim targeting priority:** nearest hostile, player-designated target, or
  highest-threat? (overlaps [combat.md](combat.md))
- **Left stick semantics:** hull *facing* directly, or a *movement vector* the hull
  rotates toward via `rotation_acceleration` / `max_rotation_speed`? _(Leaning:
  rotate-toward.)_
- **`drift_decay`:** one global constant, or a per-ship tunable?
- Minimap expand-to-fullscreen confirmed as **post-MVP**.

## References

Star Valor (ship handling, turrets/crew flavor), Starsector (crewed ships, ship variety),
libGDX Box2D docs. See PROJECT_BRIEF.md → Reference Points & Inspiration.
