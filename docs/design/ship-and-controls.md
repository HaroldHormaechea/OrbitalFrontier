# Design Note — Ship & Controls

- **Status:** in-progress (core model decided; action set, crew & some params open)
- **Last updated:** 2026-06-19
- **Related:** PROJECT_BRIEF.md → in_scope #1, core_gameplay_loop (Roam); ADR 0001 (libGDX); ADR 0015 (UI scale); ADR 0025 (settings screen, joystick tuning, player-adjustable UI scale); [combat.md](combat.md) (turrets/weapons, auto-aim), [upgrades-and-progression.md](upgrades-and-progression.md) (per-ship stats, crew), [save-and-persistence.md](save-and-persistence.md) (loadout/damage/settings state)

## Summary

The player controls a single ship in a 2D top-down sector. Movement uses **inertial
drift** — the ship carries momentum — but to keep it easy on touch, releasing the
controls makes the ship **decelerate smoothly to a stop** rather than coasting forever.
The feel is deliberately **~50% arcade / 50% simulation**. Control is a **single
movement joystick on the left** plus a **semicircular action arc in the bottom-right
corner** (the action controls); turreted weapons are **auto-aim and require crew** (so the player never manually aims
turrets — that would overload touch controls). A **settings option swaps the left/right
layout** for handedness (e.g. left-handed players). This is the "Roam" pillar of the
core loop.

## Goals

- Pick-up-and-play on a phone, but with momentum-based depth (not pure arcade snap).
- Keep on-screen controls minimal — one movement stick + a bottom-corner action arc.
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
- **Bottom-right — action arc.** A **semicircle of circular buttons** pivoting on the
  bottom-right corner, each button a **generated icon glyph plus a text label**, laid out
  at equal radius from the pivot so the set follows the natural sweep of a thumb. **FIRE**
  is pinned to a fixed end of the arc and is **always present and enabled — including
  during combat encounters**; the contextual actions (DOCK, MINE, SCAN, RADIO-offer
  accept, and a debug-only point-and-go nav) appear only while available and the arc
  **reflows to stay evenly spaced** as that set changes. Decision-relevant info that no
  longer fits on a button (docked-station name, cargo fill, mission reward) shows in a
  small **context-readout label** beside the arc. The arc reserves a fixed
  `radius + button-diameter` (currently 304) square footprint so the minimap and HUD
  layout above it never overlap it. No art pipeline yet — the glyphs are generated shapes.
- **Handedness setting.** A configuration option mirrors the layout (movement stick on
  the right, action arc pivoting on the bottom-**left** corner) for left-handed players.
  It lives in the **settings surface** below (CONTROLS group), reachable from the main
  menu and the in-flight pause overlay, and applies live (the controls re-lay-out the
  instant it is toggled).

**Settings surface (ADR 0025).** Settings are a single **grouped panel** built once
(`SettingsPanel`) and hosted by two surfaces — a standalone **main-menu settings screen**
and the **in-flight pause Settings sub-view** — so the two can never drift. Groups:
**AUDIO** (mute / SFX / music, UC31), **CONTROLS** (handedness + joystick tuning),
**DISPLAY** (UI scale), **GAMEPLAY** (replay first-run tutorial). Each control applies
live where feasible and persists per-field to the single-row `settings` table. Two groups
the long-term design calls for are **deliberately omitted, not stubbed**, until their use
cases land: **Accessibility** (text size / colourblind palette, UC39) and **Save
Management** (save slots, UC38).

**Joystick tuning (ADR 0025) — determinism-safe.** Two CONTROLS settings shape how the
left stick *feels*: **sensitivity** (a multiplier on the stick magnitude, `0.25..3.0`) and
**deadzone** (the deflection below which the stick reports no input, `0.15..0.9`). These
are applied at **exactly one place — the joystick input boundary** (`MovementJoystick`,
which gates below the deadzone then scales by sensitivity, capped at 1, no rescale). The
pure movement model, `ShipMovementParams`, and the record/replay harness (ADR 0006) never
see the tuning — they consume the resulting `MovementInput` only — so determinism and every
recorded playthrough are unaffected. The deadzone's lower bound equals the model's own
`inputDeadzone` floor (0.15), so tuning can only *widen* the dead band, never shrink it
below what the simulation already ignores.

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

- **Left** movement joystick, **bottom-right** semicircular action arc (swappable to the
  bottom-left via the handedness setting).
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
- **Settings:** handedness/control-layout preference is persisted, as are the joystick
  tuning (sensitivity + deadzone) and the UI scale (ADR 0025). All live in the single-row
  `settings` table (additive v15→v16 migration), each written through its own targeted
  per-field `UPDATE` so toggling one never clobbers another.
- **UI scale (ADR 0015 / ADR 0025):** a single global UI/HUD magnification knob
  (`1.0..3.0`, default ×2), now a player control in the DISPLAY settings group. It is
  **rendering-only** — never read into movement, combat, or any simulation math, so it is
  determinism-neutral. A live change re-applies to the active screen's Scene2D viewport
  immediately; screen-space HUD/minimap renderers that captured the factor at construction
  reflect it on the **next screen rebuild** (no app restart). The world camera is never
  scaled — the playfield stays 1:1.
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

- **MVP action set:** the arc currently carries FIRE (always on) plus the contextual
  DOCK, MINE, SCAN and RADIO-accept actions (and a debug-only point-and-go nav). Still
  open: whether boost/afterburner or a special ability/device join the set.
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
