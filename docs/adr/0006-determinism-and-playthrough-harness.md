# ADR 0006 — Determinism and the playthrough record/replay harness

- **Status:** Accepted
- **Date:** 2026-06-07
- **Refines:** [ADR 0001](0001-engine-choice.md) (`core` stays JVM-testable) and
  [ADR 0005](0005-movement-integration.md) (pure velocity model vs. Box2D integrator). Realizes
  use-case 02 (deterministic playthrough record & replay test harness), the testing foundation for
  every later use case.

## Context

`PROJECT_BRIEF.md` sets a coverage target on core game logic and the coding guidelines require
determinism (injected RNG/clock ports, no wall-clock in sim logic). Use-case 02 asks for a harness
that records a play session as a serializable artifact and replays it headlessly on the JVM so QA
can assert on the resulting state — the same record→persist→replay→assert pattern reused by every
subsequent feature.

Two forces shape the design:

- **Determinism must be reproducible across runs and machines.** Identical seed + identical input
  script + identical fixed `dt` must yield an identical end state (AC#1). That rules out
  `Math.random`, `kotlin.random.Random` seeded from the clock, and any wall-clock read inside sim
  logic, and it requires the timestep to be *fixed*, not the per-frame `delta` the live screen uses.
- **Replay must run in a plain JVM unit test.** Per ADR 0005, Box2D is the on-device integrator but
  is a JNI/native binding that cannot run in a JVM unit test, and its native step is outside any
  JVM determinism guarantee. So replay cannot go through Box2D.

We also need a serialization mechanism for the artifact that is stable and diffable, and that fits
the Kotlin/Gradle toolchain.

## Options considered

| Option | For | Against |
|---|---|---|
| **Pure fixed-step sim + injected RNG/clock ports + kotlinx.serialization artifact** | Replays the pure model headlessly (AC#5); reproducible (AC#1/#2); JSON is stable/diffable (AC#8); ports establish determinism for all future systems; stays on Gradle/JUnit. | A second (DTO) shape to keep in sync with the domain types; the live screen's variable timestep needs a fixed-step accumulator before it can feed the recorder. |
| Replay through Box2D on device / instrumented tests | "Real" physics path. | Not JVM-runnable, not CI-fast; native step is non-deterministic across versions/devices — fails AC#1/#5/#9. |
| Hand-rolled serialization (manual JSON / java.io.Serializable) | No new dependency. | Brittle and verbose; `Serializable` is unstable/non-diffable; reinvents what kotlinx.serialization gives for free. |

## Decision

Adopt a **pure, deterministic, fixed-timestep simulation** with an injected randomness and time
seam, plus a **kotlinx.serialization** JSON artifact. The whole harness is **test infrastructure**
and lives in the `core` **test** source set (`core/src/test/kotlin/com/orbitalfrontier/...`); nothing
below ships in production. Recording is a test-time concern — if live in-game recording is ever
wanted, the recorder (and the ports/sim it needs) would be promoted to the main source set then.

- **Ports (`com.orbitalfrontier.platform`).** `Rng` (impl `SeededRng` over `java.util.Random` —
  deterministic per the JDK spec, plain JDK types only) and `TimeSource` (impl `TickTimeSource`,
  `seconds = tick · dt`, **no wall clock**). Seeded/configured per playthrough. No UC02 sim system
  consumes them (movement is already deterministic); they define the determinism seam the harness
  steps through, and are the shape a production system would adopt (promoted to main) when it first
  needs seeded randomness or sim time.
- **Pure sim (`com.orbitalfrontier.sim`).** `SimulationState` (immutable snapshot — `tick` + ship
  kinematics for UC02, a documented extension point) and `Simulation`, a fixed-step stepper
  `step(state, input, dt)` wrapping `ShipMovementModel` with pinned `ShipMovementParams`. No GL,
  Android, or Box2D. This is the **single** stepper used by both recording and replay, which is what
  guarantees record/replay parity.
- **Artifact + codec (`com.orbitalfrontier.playthrough`).** `Playthrough` (`@Serializable`:
  formatVersion, name, seed, dtSeconds, tickCount, pinned config, optional initial state, and a
  tick-stamped `InputEvent` script) round-tripped by `PlaythroughCodec` through one configured
  `Json` (prettyPrint + `encodeDefaults` ⇒ stable/diffable). `InputEvent` is a `sealed` polymorphic
  hierarchy (Open/Closed) — `MovementEvent` today, room for `ActionTapEvent` — supporting **0..N
  events per tick**. Domain types stay annotation-free; serialization lives only in DTOs
  (`StateSnapshotDto`, `MovementParamsDto`) with mappers.
- **Recorder + runner.** `PlaythroughRecorder` accumulates events/seed/dt/config into a
  `Playthrough`; `ReplayRunner` seeds the RNG, sets the initial state, steps `0 until tickCount`
  applying all events grouped per tick, and returns the final `SimulationState` (optionally per-tick
  snapshots). The runner is pure JVM with **no test-framework dependency** and **must never import
  `ShipPhysics` or `com.badlogic.gdx.physics.box2d.*`**.

`kotlinx-serialization-json` is a **`testImplementation`** dependency — the harness is test-only, so
the codec never enters the production classpath. The serialization Gradle plugin stays applied so it
processes the test source set.

### Scope: replay targets the pure model, not on-device Box2D

Per ADR 0005, Box2D owns position integration *on device*. The harness deliberately replays the
**pure model** (`ShipMovementModel` integrates position via semi-implicit Euler for self-contained
math). Full Box2D replays are treated as a device-tier concern, **not** the CI default — the native
solver is neither JVM-runnable nor guaranteed deterministic across versions/devices. Float math in
the pure model is deterministic enough for CI; assert with tolerance where rounding matters.

### Caveat: the live screen uses a variable timestep

`PlayScreen` steps with a variable per-frame delta (`delta.coerceIn(MIN_DT, MAX_DT)`). A playthrough
requires a **fixed** `dt`. UC02's recorder is therefore standalone and does not hook `PlayScreen`. A
future *live* recorder must drive the sim through a **fixed-step accumulator** (accumulate real
frame time, step whole fixed `dt` slices) so recorded ticks line up 1:1 with replayed ticks.

## Consequences

- Determinism is reproducible and CI-fast: replays run headlessly on the JVM and wire into
  `./gradlew :core:test` (AC#9). The named-replay verification test uses JUnit's `Assume` so a plain
  `:core:test` skips it unless `-Dplaythrough.name=<name>` is supplied.
- The harness is reused by every later UC: record a playthrough exercising the feature, persist it
  under `src/test/resources/playthroughs/`, replay it, assert on state. See
  [PLAYTESTING.md](../PLAYTESTING.md).
- Two shapes (domain types and DTOs) must be extended together when sim state grows; the mappers and
  `formatVersion` localize and version that change.
- Hooking a live recorder to `PlayScreen` is deferred and carries the fixed-step-accumulator
  requirement above; ignoring it would desync recorded vs. replayed ticks. Going live also means
  promoting the recorder (and the ports/sim it depends on) from the test source set to main.
- The harness adds no production dependency: `kotlinx-serialization-json` is `testImplementation`
  only, so the production classpath is unchanged. It is a JetBrains-maintained, Gradle-native library
  consistent with the Kotlin toolchain.
