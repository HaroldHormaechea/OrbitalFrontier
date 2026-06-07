# Use Case 01: Flyable ship in an empty sector

## Summary
The foundational vertical slice: render an empty sector with a single player-controlled ship and implement the movement model + touch controls so the ship can be flown on Android. Movement is **inertial drift with auto-decay to a stop** on release (~50/50 arcade/sim), integrated via **libGDX Box2D**. The **left virtual joystick** sets a target movement direction; the hull **rotates toward it** (`rotation_acceleration` → `max_rotation_speed`) then thrusts, and **pushing the stick opposite the hull facing applies reverse thrust** (capped at `max_reverse_speed`). A **right-side action cluster** is shown as a placeholder (no actions wired), and a **settings toggle swaps sides for handedness**, **persisted to SQLite via SQLDelight** (standing up a minimal settings table + versioned-migration scaffolding early, per ADR 0002/0003). The blank sector uses a **parallax starfield** with a **camera that follows the ship** so motion is perceptible on an unbounded map. A minimal HUD shows **speed and heading**. Excluded: combat, missions, economy, fuel effects, other entities, jump gates. Code follows the binding coding guidelines — movement logic is JVM-testable in `core` via DIP, package-by-feature, saves transactional — targeting 60 FPS.

## Acceptance Criteria
1. Launching the app shows an empty sector (parallax starfield) with exactly one ship, kept on-screen by a **camera that follows it**.
2. Pushing the **left** joystick sets a target direction; the hull **rotates toward it** using `rotation_acceleration` up to `max_rotation_speed`, then thrusts forward.
3. The ship **retains momentum** (inertial drift) rather than halting instantly when input changes.
4. **Releasing** the movement stick makes velocity **decay smoothly to zero** (`drift_decay`) — neither an instant halt nor an infinite coast.
5. Pushing the stick **opposite the hull facing** applies **reverse thrust**, capped at `max_reverse_speed`.
6. Movement obeys the per-ship parameters: speed ≤ `max_speed`, acceleration by `max_acceleration`, turn ramp by `rotation_acceleration` / `max_rotation_speed`.
7. A **right-side action cluster** is displayed opposite the movement stick (placeholder controls; no gameplay effect).
8. A **settings option swaps left/right** (movement stick ↔ action cluster); the change takes effect immediately **and persists across app restarts** (stored in SQLite via SQLDelight).
9. The **HUD shows current speed and heading**, updating in real time.
10. Movement/physics is integrated through **Box2D**.
11. The **parallax starfield conveys motion** as the ship moves.
12. The **movement model is unit-testable on the JVM** (drift, decay, reverse, parameter clamping) without Android/libGDX platform dependencies (DIP / `core` purity).
13. The SQLite settings store is created with a stored **`saveVersion`** and writes are **transactional** (per the coding guidelines); a missing/first-run settings row is handled gracefully.
14. Frame rate stays smooth (target 60 FPS) on a mid-range Android device during continuous movement.

## Potential Pitfalls & Open Questions
- **Missing input** — Concrete numeric values for the movement parameters do not exist yet; the implementation will use **placeholder defaults** (documented in code) to be tuned later. *(Open, low risk.)*
- **Edge case** — Multi-touch: both on-screen controls must register simultaneously even though the right cluster is inert in this slice.

## Original Description
Create the use case for the first iteration. Blank map, one ship, movement and controls

## Clarifications
- Q: How should the left movement stick behave (facing-direct vs. rotate-toward)?
  A: Rotate-toward — the stick sets a target movement direction and the hull rotates toward it using the rotation params, then thrusts.
- Q: How is reverse / braking triggered with a single movement stick?
  A: Stick-opposite reverse — pushing the stick opposite the hull facing applies reverse thrust (capped at `max_reverse_speed`); auto-decay still applies on release.
- Q: What visual reference should the "blank" sector have so movement is perceptible?
  A: A parallax starfield with the camera following the ship.
- Q: Where does the handedness (left/right swap) setting live this slice?
  A: Persist it to SQLite now via SQLDelight (minimal settings table), exercising the persistence stack early.
