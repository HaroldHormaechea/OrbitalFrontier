# Playtesting — record & replay harness

This is the standard way to verify gameplay in Orbital Frontier: record a deterministic
**playthrough**, persist it as a diffable JSON artifact, and replay it headlessly on the JVM in a
unit test that asserts on the resulting state. Every use case after UC02 follows this pattern, so
QA's per-feature test is always "record → persist → replay → assert".

The harness is **test infrastructure**: it lives entirely in the `core` **test** source set
(`core/src/test/kotlin/com/orbitalfrontier/...`, in the packages below) and is pure JVM — no GL, no
Android, no Box2D (see the scope note below). Recording is a test-time concern; if live in-game
recording is ever wanted, the recorder would be promoted to the main source set then. Key types:

| Type | Package | Role |
|---|---|---|
| `Rng` / `SeededRng` | `com.orbitalfrontier.platform` | Injected, seeded randomness (deterministic). |
| `TimeSource` / `TickTimeSource` | `com.orbitalfrontier.platform` | Injected time; `seconds = tick · dt` (no wall clock). |
| `SimulationState` | `com.orbitalfrontier.sim` | Immutable per-tick snapshot you assert against. |
| `Simulation` | `com.orbitalfrontier.sim` | The single pure fixed-step stepper. |
| `Playthrough` / `InputEvent` | `com.orbitalfrontier.playthrough` | The serializable artifact + tick-stamped input script. |
| `PlaythroughRecorder` | `com.orbitalfrontier.playthrough` | Builds a `Playthrough`. |
| `PlaythroughCodec` | `com.orbitalfrontier.playthrough` | `Playthrough` ↔ stable JSON. |
| `ReplayRunner` | `com.orbitalfrontier.playthrough` | Replays a `Playthrough` → final (and optional per-tick) `SimulationState`. |
| `PlaythroughResources` | `com.orbitalfrontier.playthrough` | Loads `playthroughs/<name>.json` from the classpath. |

## Where artifacts live

Playthrough JSON files are committed under:

```
core/src/test/resources/playthroughs/<name>.json
```

`PlaythroughResources.load("<name>")` resolves them from the classpath. They are pretty-printed with
defaults encoded, so they diff cleanly in review — treat a noisy diff as a signal that behaviour
changed.

## Recording a new playthrough

Build a `Playthrough` with `PlaythroughRecorder`, encode it with `PlaythroughCodec`, and write the
text under `core/src/test/resources/playthroughs/`. A recording is just: a seed, a fixed `dt`, the
pinned movement config, an optional initial state, and the tick-stamped input script.

```kotlin
val recorder = PlaythroughRecorder(
    name = "uc01-thrust-north",
    seed = 1L,
    dtSeconds = 1f / 60f,            // a FIXED timestep — not the live frame delta
    // config = ShipMovementParams(...),   // defaults pinned if omitted
    initialState = SimulationState(),       // starts at origin, at rest
)

// Thrust "north" for 60 ticks, then coast for 30 (0..N events per tick are supported).
val north = MovementInput(targetDirection = Vec2(0f, 1f), magnitude = 1f, released = false)
for (tick in 0 until 60) recorder.recordMovement(tick, north)
recorder.extendToTick(89)                  // 30 trailing no-input (drift) ticks

val json = PlaythroughCodec.encode(recorder.build())
// write `json` to core/src/test/resources/playthroughs/uc01-thrust-north.json
```

To regenerate an artifact deliberately (e.g. after an intended tuning change), re-run the builder
and overwrite the file; commit the diff.

## Adding a replay test

A replay test loads the artifact, replays it, and asserts on `ReplayResult`. Two patterns QA uses on
every feature:

```kotlin
class Uc01MovementReplayTest {
    @Test
    fun `thrusting north moves the ship north`() {
        val playthrough = PlaythroughResources.load("uc01-thrust-north")
        val result = ReplayRunner().run(playthrough)
        // Assert with tolerance — float math is deterministic but not exact.
        assertEquals(0f, result.finalState.ship.position.x, 1e-3f)
        assertTrue(result.finalState.ship.position.y > 0f)
    }

    @Test
    fun `replay is deterministic`() {                       // AC#11 determinism guard
        val playthrough = PlaythroughResources.load("uc01-thrust-north")
        val a = ReplayRunner().run(playthrough)
        val b = ReplayRunner().run(playthrough)
        assertEquals(a.finalState, b.finalState)            // identical snapshots
    }
}
```

Pass `capturePerTickStates = true` to `run(...)` when you need to assert on intermediate ticks
(`result.perTickStates[k]` is the state after `k` steps; index 0 is the initial state).

### The named-replay verification test

There is one parameterized test that replays whichever playthrough is named on the command line —
this is what the `playtest` skill drives:

```
./gradlew :core:test --tests *NamedPlaythroughReplayTest -Dplaythrough.name=<name>
```

That test uses JUnit's `Assume` (`assumeTrue(name != null)`), so a plain `./gradlew :core:test`
**skips** it (it only runs when `-Dplaythrough.name=` is supplied) while the feature-specific replay
tests above always run. See `.claude/skills/playtest/SKILL.md`.

## Two things to keep in mind

**Replay targets the pure model, not on-device Box2D.** Per [ADR 0005](adr/0005-movement-integration.md)
Box2D integrates position *on device*, but it is a native binding that can't run in a JVM test and
whose step isn't guaranteed deterministic across versions/devices. The harness therefore replays the
**pure** `ShipMovementModel` (it integrates position via semi-implicit Euler for self-contained
math). Full Box2D replays are a device-tier concern, not the CI default. Assert with tolerance where
float rounding matters. See [ADR 0006](adr/0006-determinism-and-playthrough-harness.md).

**The live screen uses a variable timestep; a playthrough needs a fixed one.** `PlayScreen` steps
with a per-frame `delta.coerceIn(MIN_DT, MAX_DT)`. Playthroughs require a **fixed** `dt`, which is
why UC02's recorder is standalone and does not hook `PlayScreen`. A future *live* recorder must drive
the sim through a **fixed-step accumulator** (accumulate real frame time, step whole `dt` slices) so
recorded ticks map 1:1 onto replayed ticks. Recording off the raw variable delta would desync replay.
