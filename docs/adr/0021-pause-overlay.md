# ADR 0021 — In-flight pause overlay (freezes the simulation)

- **Status:** Accepted
- **Date:** 2026-06-18

## Context

Until now Orbital Frontier had **no general pause**. Flight is the only screen that runs the
deterministic simulation, and nothing stopped its per-frame tick: docking merely hands off to a
separate screen (so movement freezes only because `PlayScreen` isn't rendered), and the click-to-zoom
map overlay (UC23) is deliberately **LIVE** — `MapOverlayLayout.PAUSES_SIMULATION = false` — so opening
it does not suspend the fight (see ADR-less note in `docs/design/world-and-sector.md`). For a mobile
title where interruptions are constant (a call, a notification, putting the phone down mid-encounter)
the absence of any way to *stop time* is a readiness gap: the player cannot step away without taking
damage or drifting, and there is no in-flight route back to the main menu.

UC-32 adds a pause control reachable from flight (HUD button **and** the Android back gesture) that
opens a modal overlay offering **Resume**, **Settings**, and **Quit to main menu**, and **freezes the
deterministic tick** while open so no game time passes (AC#2). This forces three decisions worth
recording: (1) the relationship to the existing LIVE map overlay, (2) what the Android back button does
in flight, and (3) how held multi-touch inputs are handled across the pause transition.

Relevant brief fields: `maturity_target: mvp` (a playable, installable vertical slice — pause is table
stakes for that), the 60 FPS performance budget, and the SOLID coding principle (the pause gate is a
pure, JVM-testable value, mirroring the existing `MapOverlayState`).

## Options considered

| Option | For | Against |
|---|---|---|
| **Pause overlay that freezes the sim (chosen)** | Matches player expectations for a mobile game; lets the player step away safely and reach the menu; the freeze is a one-line gate over the per-frame advance; testable via a pure `PauseState`. | Introduces a second overlay concept whose semantics are the **inverse** of the LIVE map overlay, so the two must be kept clearly distinct. |
| Reuse the map overlay and make *it* pause | One overlay concept | The map overlay is a pure inspection layer (intentionally LIVE in combat); making it pause would silently change UC23's documented behaviour and conflate two different intents. |
| No pause; rely on docking/app-background | No new code | Docking isn't reachable in open space or mid-combat; backgrounding the app isn't a deliberate in-game pause and gives no menu route. Leaves the readiness gap. |
| Pause but keep rendering frozen too (freeze the whole screen) | Simplest gate | The scene would visibly hitch and the modal backdrop would have nothing to dim over; keeping rendering live (camera/ship drawn from the frozen Box2D transform) reads better and matches how overlays already compose. |

## Decision

Add an **in-flight pause overlay that freezes the deterministic simulation** while open.

- **Pure gate.** A new libGDX-free `render/PauseState` (`paused()`/`resumed()`/`toggled()`) is the
  deliberate **inverse** of `MapOverlayState`: `PlayScreen` reads `pauseState.isPaused` once per frame
  and **skips the entire per-frame state-advance** when paused (the extracted `advanceSimulation(dt)` —
  fuel burn, the ADR 0005 movement step, gate traversal, docking, mining, scanning, radio offers, the
  courier/combat tick accumulators, and the periodic autosave). **Rendering still runs** in both states
  (`renderFrame`), drawing the ship from the live Box2D transform (`physics.readKinematics()`), so the
  scene stays visible and the camera holds steady under the modal backdrop. Resuming continues exactly
  where the player left off (AC#4) because no state moved.

- **One overlay at a time (pitfall).** Pause and the map overlay are mutually exclusive: opening pause
  forces `mapOverlayState.dismissed()`, the pause backdrop sits at the top z-order and blocks the
  minimap tap target, and the HUD pause button hides whenever either overlay is open.

- **Android back button → pause (decision).** In flight the back gesture is **caught** (`setCatchKey`,
  released on `hide()`) and mapped to: open pause when flying, **resume** when paused, and **step back
  to the pause menu** from the Settings sub-view. This is the least-surprising mapping for a single
  full-screen gameplay screen (there is no prior screen-back stack to honour), and it gives parity with
  the HUD button. Scoped to `PlayScreen` only.

- **Held inputs neutralized (risk).** On the open→paused transition `PlayScreen` calls
  `stage.cancelTouchFocus()` and stops the looping `THRUST` cue (mirroring `hide()`), so a stick or FIRE
  held at the instant of pause does not "stick" on resume, and the flight controls (joystick + action
  arc) hide while paused.

- **Quit is durable.** Quit to main menu flushes a blocking autosave (`autosave.onPauseOrExit()`)
  **before** handing back to `OrbitalFrontierGame.returnToMainMenu()`, which stops flight music, rebuilds
  the menu from a fresh load (Continue available), disposes the previous menu before reassigning, and
  disposes the play screen — so no progress is lost and no GL leaks.

## Consequences

- The game now has a clear, consistent pause model: **pause freezes, the map overlay stays LIVE.** The
  two overlays are documented as deliberate inverses; future overlays should state which they are.
- The per-frame advance is now an isolated `advanceSimulation(dt)` method (a small SRP win), making the
  freeze a single guard and keeping the deterministic flow byte-identical to the pre-UC32 inline body —
  so the record/replay harness (ADR 0006) is unaffected (it never pauses).
- Pausing composes with combat and the map overlay: pausing mid-combat freezes the encounter and lets
  the player open Settings over it; the LIVE-in-combat tradeoff of the *map* overlay (ADR-less note in
  `docs/design/world-and-sector.md`) is unchanged — only the new pause overlay stops time.
- Reversibility is cheap: the gate is one boolean and a method call; removing pause would mean deleting
  `PauseState`/`PauseOverlay` and calling `advanceSimulation(dt)` unconditionally.
- The Android back-button decision is now binding for `PlayScreen`; if a deeper in-flight navigation
  stack is ever added, revisit it with a new ADR rather than rewriting this one.
