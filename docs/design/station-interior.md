# Design Note — Station Interior (on-foot walk-around)

- **Status:** in-progress  <!-- prototype shipped under UC19; a full interior system is future work -->
- **Last updated:** 2026-06-09
- **Related:** PROJECT_BRIEF.md → core_gameplay_loop (Improve), non_goals (space-station building is a separate stretch), Architecture → service_shape; [ADR 0016](../adr/0016-on-foot-station-mode.md); use case [19-station-walkaround-prototype](../../use-cases/19-station-walkaround-prototype.md); ship-and-controls.md (shared virtual-joystick input).

## Summary

When docked at a station the player can **optionally** leave the ship and walk around its interior
on foot, as an early prototype of station interiors. Exiting is one extra path — the existing docking
menus stay exactly as they are. The prototype interior is a zoomed-in landing area (with the player's
ship), a corridor, and a square room containing the shop and its shopkeeper. The avatar is drawn as a
ball with a small facing dot and is driven by the same virtual joystick the ship uses. Walking near
the shopkeeper surfaces an interact prompt that opens the **existing** shop UI. This serves the
*Improve* pillar (it is the on-foot route to the trade desk) without redesigning the shop itself.

## Goals

- Let the player disembark and walk a small, hand-authored interior while docked (optional, additive).
- Reuse the ship's virtual-joystick input scheme so on-foot controls feel consistent.
- Reach the **existing** shop UI from on foot — no new shop screen.
- Keep the avatar inside the walkable area with loose collision (prototype-grade is fine).
- Stay deliberately low-fidelity: programmer-art ball/dot/box geometry, single corridor + single room.
- Be repeatable: re-boarding and re-exiting must not corrupt state; the avatar spawns sensibly each time.

## Mechanics / ideas

- **Layout (union of overlapping rectangles).** The interior is three axis-aligned rectangles —
  `landingArea` → `corridor` → `room` — that **overlap** at their junctions (they share area, not just
  touch). Walkability is *union membership*: a point is walkable iff it is inside any one rectangle.
  Because adjacent areas overlap, there is no internal seam to get trapped on; the only thing collision
  clamps against is leaving the interior entirely.
- **Collision (loose, single-point).** Each frame the model computes the avatar's desired destination,
  then snaps it back to the nearest point of the walkable union if it left it (`clampToWalkable`). This
  is a single destination clamp, so fast-tunneling across a thin gap is *accepted* for the prototype.
- **Movement (direct, no inertia).** Unlike the ship (which drifts — see `ship-and-controls.md`), the
  avatar moves exactly `dir · moveSpeed · magnitude · dt` while the stick is active and stops instantly
  on release. Facing follows the stick direction and is retained when the stick is released.
- **Interaction.** When the avatar is within `shopkeeperInteractRadius` of the shopkeeper, an INTERACT
  button appears; activating it opens the existing `TradeScreen`. Only the shopkeeper is interactive in
  this prototype — refuel/missions/outfit stay on the hub menus.

## Player-facing behavior

- The station hub gains an **EXIT SHIP** button (additive; every existing menu is unchanged).
- Tapping it shows a zoomed-in view: the ship on its landing pad and the avatar (a ball + facing dot).
- The left virtual joystick walks the avatar; the facing dot tracks the movement direction.
- Walking down the corridor into the room brings up the shopkeeper; near it, **INTERACT** opens the shop.
- **RE-BOARD** (always visible) returns to the station hub, restoring the normal docked state.

## Data & state

- **Nothing here is persisted.** The interior layout and the avatar's position are transient screen
  state, rebuilt from `StationInterior.prototype()` each time the player disembarks. There is **no save
  schema change** and no new save version. The docked `WorldState` is never touched while on foot, so
  re-boarding restores the docked state exactly (it was never modified). Opening the shop on foot goes
  through the same pure economy path as the hub, so trades persist exactly as they already do.
- The avatar instance is kept alive across a shop visit (the screen is only hidden, not disposed), so
  the avatar stands where it was when the trade desk closes.

## GL / pure split (ADR 0001)

- **Pure (JVM-unit-testable, no libGDX types) — package `com.orbitalfrontier.walkaround`:**
  `Rect` (in `common`), `StationInterior` (layout + `isWalkable`/`clampToWalkable`), `Avatar`,
  `WalkaroundParams`, and `WalkaroundModel` (the deterministic integrator + `isNearShopkeeper`). These
  carry all the geometry and movement rules and are covered by unit tests.
- **GL/screen (thin, not headlessly tested):** `WalkaroundRenderer` (ShapeRenderer programmer-art) and
  `StationWalkaroundScreen` (camera + Scene2D controls + per-frame glue). The screen takes only the
  interior, its params/logger, and `onReboard`/`onInteract` callbacks — no world/save/PlayScreen
  coupling — keeping the mode structurally decoupled from persisted state.

## Dependencies & interactions

- **Input:** reuses `MovementJoystick` / `MovementInput` from the ship-controls rig.
- **Shop:** opens the existing `TradeScreen`; BACK from the shop returns to the walk-around (not the
  hub), so the avatar is preserved. The owning `OrbitalFrontierGame` routes hub ⇄ foot ⇄ shop.
- **Hub:** the EXIT SHIP button is additive and defaulted, so existing hub call sites/tests are unaffected.

## Open questions

- Should other station services (refuel, missions, outfit) eventually have on-foot objects, or stay on
  the menus? (UC19 keeps only the shopkeeper interactive.)
- Real art + multiple rooms / multiple stations with distinct layouts (currently one shared prototype).
- Tighter collision (swept/continuous) if avatar speed or thin walls make tunneling visible.
- Whether the avatar's on-foot position should ever be persisted (currently deliberately transient).

## References

- Use case 19 (this prototype's acceptance criteria).
- `ship-and-controls.md` — the shared virtual-joystick input scheme.
- Starsector/Naev station interactions inform the *feel*; scope here is intentionally a single
  corridor + room (see PROJECT_BRIEF.md → scope guardrail).
