# ADR 0005 — Movement integration: pure velocity model + Box2D as integrator

- **Status:** Accepted
- **Date:** 2026-06-07
- **Refines:** [ADR 0001](0001-engine-choice.md) (`core` stays JVM-testable); realizes the movement
  design in [ship-and-controls.md](../design/ship-and-controls.md). First applied in use-case 01
  (flyable ship in an empty sector).

## Context

Use-case 01 has two acceptance criteria that pull in opposite directions:

- **AC#10** — movement/physics must be **integrated through Box2D**.
- **AC#12** — the **movement model must be unit-testable on the JVM** (drift, decay, reverse,
  parameter clamping) **without** Android or libGDX-platform dependencies.

Box2D (`com.badlogic.gdx.physics.box2d`) is a JNI binding backed by native `.so` libraries; it
cannot run in a plain JVM unit test. If the movement *math* lived inside Box2D callbacks or
depended on a live `World`, AC#12 would be impossible. Conversely, if we hand-integrated
position ourselves and ignored Box2D, AC#10 would be unmet and we'd have no path to collisions
later. We need both: testable decision math **and** Box2D doing the integration.

This mirrors the persistence split already established by [ADR 0003](0003-persistence-access-layer-sqldelight.md)
(pure `core` logic + an injected platform driver).

## Options considered

| Option | For | Against |
|---|---|---|
| **Split: pure velocity model + Box2D integrator** | Movement decision math is pure/JVM-tested (AC#12); Box2D integrates position/rotation (AC#10); single source of velocity truth, no double integration; collisions available later for free. | Two pieces to keep in contract-sync; a per-frame protocol the screen must follow exactly. |
| All movement inside Box2D (forces/impulses in callbacks) | "Physically pure"; one system. | Not JVM-testable (AC#12 fails); arcade feel (instant rotate-toward, auto-decay) is awkward to express as forces; tuning fights the solver. |
| Hand-rolled integrator, Box2D only for collision | Fully testable. | AC#10 ("integrated through Box2D") unmet; double bookkeeping once collisions arrive; divergence between our integrator and Box2D's. |

## Decision

Split responsibilities:

- **`ShipMovementModel` is the velocity authority.** A pure function
  `update(state, input, params, dt)` computes the next **velocity** and **angular velocity**
  (rotate-toward via an acceleration ramp, forward/reverse thrust cones, inertial drift,
  release decay, and the speed/turn-rate clamps). It uses plain `Float`/`Vec2` only — no libGDX
  types — so it is fully JVM-unit-testable (AC#12).
- **`ShipPhysics` (wrapping a Box2D `World` + dynamic body) is the integrator of record.** It
  owns position/rotation integration (AC#10). It is an on-device boundary class and is **not**
  unit-tested.

### Per-frame contract (binding)

Each frame the play screen MUST run exactly this order:

1. `state = physics.readKinematics()` — read the body transform + velocity back into the pure value type.
2. `next = model.update(state, input, params, dt)` — compute the new **velocity** (and angular velocity).
3. `physics.applyKinematics(next)` — write **`setLinearVelocity` / `setAngularVelocity` only**.
   **Never** set the body transform on this path.
4. `physics.step(dt)` — let Box2D integrate position/rotation from that velocity.
5. Render/HUD read from `physics.readKinematics()`.

The body transform is set directly **only** at spawn/reset (`ShipPhysics.resetTo`). Box2D runs
with zero gravity, zero linear/angular damping, and sleeping disabled, so the velocity the model
sets each frame is exactly what Box2D integrates — the model alone governs acceleration/decay,
Box2D alone governs position/rotation. There is no double integration.

Units: the model works in *world-units*; Box2D works in metres. `ShipPhysics` scales by a
pixels-per-metre (PPM) constant on the way in/out so body sizes and per-step translations stay in
Box2D's numerically-stable range (avoiding the `b2_maxTranslation` velocity clamp at top speed).

## Consequences

- AC#10 and AC#12 are both satisfied: Box2D integrates; the decision math is pure and tested.
- The contract is a real coupling — `applyKinematics` writing a transform, or the screen calling
  steps out of order, would reintroduce double integration. The contract is enforced by code
  comments here and in `ShipPhysics`/the play screen, and by review.
- Collisions, sensors, and forces are available later through the same `World` without
  re-architecting movement.
- The model's own position field is integrated (semi-implicit Euler) purely for self-contained
  tests; on device that field is ignored — Box2D's body position wins.
