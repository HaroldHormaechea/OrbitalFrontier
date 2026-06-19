# ADR 0025 — Full settings screen, joystick tuning & player-adjustable UI scale

- **Status:** Accepted
- **Date:** 2026-06-19

## Context

Until now "settings" was a single in-flight handedness toggle that grew, ad hoc, into a flat stack of
five buttons (handedness + the UC31 audio controls + the UC36 replay-tutorial action). UC37 asks for a
real settings *surface*: grouped sections (audio, controls, accessibility, gameplay, save management),
reachable from both the main menu and the pause overlay (UC32), with each setting persisted and applied
live where feasible. It also introduces two genuinely new controls — **joystick sensitivity/deadzone**
and a **player-adjustable UI scale** — beyond simply regrouping what already existed.

The forces:

- **Determinism is binding.** ADR 0001 (JVM-testable core) and ADR 0006 (record/replay harness) require
  that gameplay-affecting state come only from the pure simulation. Joystick tuning changes how the stick
  *feels*, so it must not enter the pure movement model or the recorded input stream, or it would desync
  the harness (UC37 Risk: "keep the settings model in the pure layer so it stays testable and
  save-compatible").
- **One surface, two hosts.** The settings UI must appear identically on the main menu (a standalone
  screen, before any game exists) and in flight (the pause Settings sub-view over the frozen game, ADR
  0021/UC32). Two hand-maintained copies would drift.
- **UI scale was a compile-time constant.** ADR 0015 fixed the UI/HUD magnification at ×2 via a `const`.
  Making it a player control means it becomes mutable, persisted state — but it must stay *rendering-only*
  (never in simulation math) exactly as ADR 0015 established.
- **Persistence is additive-migration-only** (ADR 0002/0003): three new preferences mean a schema bump
  with a version-by-version `.sqm` and a regenerated baseline.
- **Some groups depend on unbuilt use cases.** Accessibility rows (text size, colourblind palette) depend
  on UC39; save-management rows depend on the save-slot UC (UC38). The use case explicitly says to ship
  the screen now as a shell that grows, not to fake those rows.

## Options considered

| Option | For | Against |
|---|---|---|
| **Shared grouped panel builder (`SettingsPanel`) hosted by both the in-flight overlay and a new main-menu screen; joystick tuning applied at the joystick boundary; UiScale promoted to a clamped mutable global** | One source of truth for the UI; determinism preserved (tuning never reaches the model/replay); UiScale stays rendering-only; each preference is a pure, JVM-tested value type round-tripping through the store | UiScale becomes mutable global state (a deliberate exception to the value-type leaning); in-flight HUD renderers that captured the factor at construction need a screen rebuild to reflect a live UI-scale change |
| Apply joystick tuning inside the pure `ShipMovementModel` | "Tuning is movement, put it with movement" | Breaks ADR 0006 — the recorded input would no longer reproduce; every replay fixture would have to encode the live tuning. Rejected outright |
| Two separate settings UIs (keep the in-flight overlay, write an independent menu screen) | Simplest per-screen code | Guarantees drift — the two surfaces would diverge as controls are added; fails the use case's "one settings surface" intent |
| Thread UI scale as a value through every screen constructor instead of a global | Avoids mutable global state | UiScale was already a global (ADR 0015) read at dozens of HUD/viewport sites; threading it would be a large, invasive change for no determinism benefit (it is rendering-only) |

## Decision

Implement UC37 as a **single shared grouped panel builder with two thin hosts**, plus two new
determinism-safe preferences:

- **`SettingsPanel`** (new) is the one source of truth: it builds the grouped, scrollable content
  (**AUDIO**, **CONTROLS**, **DISPLAY**, **GAMEPLAY**) and owns every control's apply-live-then-persist
  logic. Both **`SettingsOverlay`** (the in-flight pause Settings sub-view, now a thin wrapper exposing
  the panel in a `ScrollPane`) and the new **`SettingsScreen`** (reached from a main-menu SETTINGS button)
  instantiate it. UC32's pause navigation is untouched; the main-menu path is purely additive.
- **Joystick tuning** is a new pure value type, `JoystickTuning(sensitivity, deadzone)`, applied at
  **exactly one place — `MovementJoystick.currentInput()`** (the input boundary). It gates below the tuned
  deadzone then scales the raw magnitude by sensitivity (capped at 1, no rescale). The pure
  `ShipMovementModel`, `ShipMovementParams`, and the record/replay harness are **never** touched — they
  consume the resulting `MovementInput` only, so determinism is preserved. The deadzone's lower clamp
  (`0.15`) equals `ShipMovementParams.inputDeadzone`, so tuning can only ever *widen* the dead band, never
  narrow it below what the simulation already ignores.
- **UI scale** (ADR 0015) is promoted from a `const` to a **clamped mutable global** (`UiScale.factor`,
  range `1.0..3.0`, NaN/∞ → ×2 default) with `set()`/`coerce()`/`reset()`. It is restored from the store
  into the global knob at startup *before any screen builds*, surfaced as a DISPLAY-group control, and a
  live change re-applies to the active screen's own Scene2D viewport immediately. It remains
  **rendering-only** — never read into movement, combat, or any simulation math (ADR 0006 unaffected).
  In-flight screen-space HUD/minimap renderers that captured the factor at construction reflect a live
  change on the **next screen rebuild** (no app restart); this boundary is accepted honestly rather than
  rebuilding live renderers mid-flight.
- **Persistence** adds three columns to the single-row `settings` table — `joystick_sensitivity`
  (DEFAULT 1.0), `joystick_deadzone` (DEFAULT 0.15), `ui_scale` (DEFAULT 2.0) — via an additive
  `migrations/15.sqm` (v15→v16), a regenerated `databases/16.db` baseline, and `SaveVersion.CURRENT=16`.
  Each gets its own targeted `UPDATE` (the per-field discipline UC31/UC36 established), so persisting one
  preference never clobbers another. A migrated pre-UC37 save reads back the prior implicit behaviour
  (neutral stick, model-floor deadzone, ×2 UI) with no data loss.
- **Accessibility (UC39) and Save Management (UC38) are omitted, not faked.** The panel shows a short
  note row stating they are coming in a later update; the groups slot in additively when those use cases
  land. (The main-menu REPLAY TUTORIAL has no running game to replay into, so it re-arms the first-run
  flag — an honest menu-context meaning rather than a dead button.)

## Consequences

- **Easier:** adding a new setting is one place (`SettingsPanel`) and both surfaces get it for free; the
  pure `JoystickTuning` / `AudioSettings` value types and the repository round-trips are JVM-unit-testable
  without libGDX; the v16 migration follows the well-worn additive pattern.
- **Determinism is preserved by construction:** tuning lives entirely on the device-input side of the
  `MovementInput` boundary, so existing replay fixtures keep reproducing unchanged. This is the load-
  bearing constraint and the reason tuning was kept out of the model.
- **Harder / accepted costs:** `UiScale` is now mutable global state — a deliberate, documented exception
  justified by its single-writer / single-reader-per-frame access pattern and rendering-only blast radius
  (tests that mutate it should `reset()` in tear-down to avoid cross-test bleed). A live UI-scale change
  does not retro-resize already-constructed in-flight HUD renderers until the next screen rebuild; this is
  documented player-facing behaviour, not a bug.
- **Reversibility:** the schema change is additive (columns can be ignored, never removed once shipped —
  ADR 0002); the shared-panel structure can absorb the UC38/UC39 groups without touching the hosts. Moving
  tuning into the model later would require encoding it into the replay format and is intentionally avoided.
