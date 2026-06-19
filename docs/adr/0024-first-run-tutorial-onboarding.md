# ADR 0024 — First-run tutorial & onboarding

- **Status:** Accepted
- **Date:** 2026-06-19

## Context

A new player is dropped straight into flight facing an action arc of icon buttons and a virtual
joystick with no explanation of the controls or the core loop (UC36). The brief's first success
criterion is that the full loop is playable end-to-end by an early tester — but nothing today teaches
steer → dock → accept a mission → gather → refuel → fire.

The forces:

- **Determinism is binding.** ADR 0001 (JVM-testable core) and ADR 0006 (record/replay harness)
  require that gameplay-affecting state come only from the pure simulation. Onboarding must not alter
  simulation rules or consume RNG, or it would desync the harness (UC36 AC#4).
- **It must compose with the existing in-flight overlays.** Pause (ADR 0021/UC32) freezes the
  per-frame advance; the destruction screen (ADR 0022/UC33) nests under it; the notification feed
  (ADR 0023/UC35) owns a top-centre toast band. The tutorial is another in-flight layer that has to
  coexist with all three.
- **Persistence is additive-migration-only** (ADR 0002/0003): a first-run flag means a schema bump
  with a version-by-version `.sqm` and a regenerated baseline.
- **Art/time budget is MVP.** A fully scripted, set-piece tutorial is out of scope; the use case
  explicitly allows scoping to lightweight staged control hints as long as the whole loop is covered.

## Options considered

| Option | For | Against |
|---|---|---|
| **Lightweight staged hint overlays driven by a pure step machine, advanced by observing existing event seams** | No new simulation; reuses the UC31/UC35 event seams; the step logic is a pure, JVM-tested value type; composes with pause/destruction by riding the same gated advance | Cross-screen steps (refuel, the trade path of gather) can only show their hint on the flight screen, so their copy must point the player off-screen |
| Fully scripted, gated tutorial (force each step, block other input) | Most hand-held | Gates input (fights AC#4's "annotate, don't alter"); large art/scripting cost; brittle against every later UC |
| Static one-shot help card / text screen | Cheapest | Doesn't teach by doing; players skip walls of text; fails AC#1's "guided through each action" |

## Decision

Implement onboarding as **lightweight staged control hints backed by a pure step machine**:

- A new engine-free `tutorial` package — `TutorialStep` (ordered STEER → DOCK → ACCEPT_MISSION →
  GATHER → REFUEL → FIRE, each carrying its ASCII copy, highlighted control, and completing event),
  `TutorialEvent`, `TutorialHighlight`, and the immutable **`TutorialState`** (stepIndex-based;
  `advancedBy`/`skipped`/`dismissed`; `activeStep`/`isComplete`). `TutorialState` is the non-trivial
  unit-tested logic (AC#5) and imports no libGDX (kept honest by a purity guard, like `audio`/`notify`).
- The play screen **observes** the same gameplay seams the audio (UC31) and notifications (UC35)
  systems already use — the thrust edge, a dock, a productive mining tick / a station trade, a radio or
  board mission accept, a hydrogen-conversion or credits refuel, and a weapon fire — recording a
  `TutorialEvent` into a buffer. The buffer is drained and the state advanced **once per frame inside
  the gated per-frame advance**, beside the notification update, so the tutorial only ever progresses
  while the simulation is running and **freezes under pause and the destruction screen**. The tutorial
  never feeds the simulation or touches RNG — it only reads events the sim already produced (AC#4).
- A thin draw-only `TutorialOverlay` (a hint band placed by the pure `TutorialOverlayLayout` in the
  bottom-centre, clear of the top-centre toast band) shows the active step's copy plus **SKIP** (this
  step) and **SKIP ALL** buttons, and the highlighted control (joystick or an action-arc button) is
  **tinted, never gated** (AC#2/#4). The band sits below the pause/destruction backdrops in z-order and
  is hidden under any full-screen overlay, so its SKIP taps are suppressed while paused.
- A persisted **`settings.tutorial_completed`** flag (additive **v14 → v15** migration, `14.sqm`, with a
  regenerated `databases/15.db` baseline) gates first-run: the tutorial starts only when the flag is
  unset, and completion / skip-all persists it once through the SaveExecutor single writer. It is
  **replayable** from the settings panel's REPLAY TUTORIAL button, which resets the in-memory state
  without clearing the flag (AC#3).

## Consequences

- **Determinism preserved.** The simulation, the record/replay harness, and the autosave snapshot are
  byte-unchanged — the tutorial is a pure observer on the gated advance and the control highlight is a
  cosmetic tint. No sim rule changed and no RNG is consumed.
- **Accepted limitation — cross-screen steps.** REFUEL and the trade path of GATHER complete at a
  docked station, off the flight screen where the hint band lives. Rather than build a second overlay
  into every hub/desk screen, their copy is deliberately **two-part** ("... or DOCK then TRADE",
  "DOCK then REFUEL") so the flight-screen hint still tells the player where to go. This is the
  documented lightweight-staged scope; a richer in-hub hint can be layered on later without touching
  the step machine.
- **Onboarding-after-upgrade.** A returning player who upgrades across this version has
  `tutorial_completed` backfilled to 0 by the additive migration, so they see the tutorial once. This
  is acceptable — the flag exists to stop it *re-triggering every launch*, not to suppress it for
  pre-existing saves — and it is skippable/replayable regardless.
- **Schema floor moves to v15.** Any later migration chains from `15.db`; `SaveVersion.CURRENT` and the
  generated schema version are tied together by the existing fail-fast check.
- **Copy is font-constrained.** Hint text stays within the bundled game font's ASCII + `°` + `→` set
  (UC28); the label wraps over-long copy.
- **Reversibility.** The `tutorial` package and overlay are additive and isolated; removing the feature
  is deleting the package + overlay and the wiring seams. The schema column would remain (migrations are
  never rewritten) but is harmless if unread.
