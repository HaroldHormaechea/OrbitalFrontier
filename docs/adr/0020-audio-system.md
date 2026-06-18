# ADR 0020 — Audio system: SFX & music (UC31)

- **Status:** Accepted
- **Date:** 2026-06-18
- **Relates to:** [ADR 0001](0001-engine-choice.md) (libGDX + `core` JVM-testability),
  [ADR 0002](0002-save-and-persistence.md) / [ADR 0003](0003-sqldelight.md) (versioned SQLite save +
  SQLDelight driver injection), and [ADR 0006](0006-determinism-and-playthrough-harness.md)
  (deterministic record/replay — the core must stay engine-free and headless).

## Context

Before UC31 the game had **zero audio** — no `Sound`, `Music`, or asset references anywhere in `core`
or `android`. All three readiness-gap analysis passes independently flagged the complete absence of
audio as a pre-alpha gap. UC31 adds sound effects for the core gameplay events (thrust, weapon fire,
hit/explosion, mining, docking, jump, UI taps, mission accept/complete) and ambient music (a flight
track and a station track), plus a master mute and independent SFX/music volume that persist across
launches.

The hard constraint is **determinism** (ADR 0001/0006): the pure simulation is stepped by the replay
harness on the JVM with no audio device, and must stay byte-for-byte reproducible. Audio therefore must
not be reachable from, or embedded inside, the simulation — routing sound through the sim would break
record/replay and the headless tests. Audio also must degrade gracefully when no audio device exists
(the replay harness) and when a clip is missing (placeholder assets not yet replaced).

A second-order constraint: real audio asset content (formats/licensing) has to be sourced. UC31 ships
**placeholder** synthesised clips so the whole system — service, event wiring, settings, headless
safety — is real and testable now; only the clip *content* is a placeholder.

## Options considered

| Option | For | Against |
|---|---|---|
| **Event-driven port, injected into the screen layer (chosen)** | Mirrors the existing `Logger`/`SaveExecutor` DIP ports; pure core emits events, the screen layer plays sound; core stays audio-free + JVM-testable; headless uses a no-op | One more injected dependency; UI-tap needs a cross-screen hook |
| Call libGDX audio directly from gameplay/sim code | Fewer indirections | Pulls engine types into (or near) the deterministic core; breaks replay + headless tests — a non-starter under ADR 0001/0006 |
| Defer audio entirely | No new surface | Leaves the flagged pre-alpha gap open |

## Decision

Introduce a pure **`AudioService`** port in `platform` (the audio analogue of `Logger`/`SaveExecutor`),
with engine-free value types in the `audio` package (`Sfx`, `MusicTrack`) and `settings`
(`AudioSettings`). The libGDX implementation, **`LibGdxAudioService`**, lives in `render/` — outside the
purity-guarded `audio` package — because it imports libGDX `Sound`/`Music`. The app builds the single
instance on the GL thread, applies persisted settings, and disposes it once on shutdown (single owner,
single dispose — the same discipline as `GameAssets`). Headless/JVM contexts use `NoOpAudioService`.

**Sound is event-driven.** SFX are triggered from the *same* gameplay event seams the autosave/HUD use:
combat cues from the pure `Combat.step` events (`Sfx.forCombatEvent`), and thrust/mining/dock/jump/
mission cues from the existing `PlayScreen` per-frame flow. The pure simulation is never touched (AC#4).
Music switches on screen transitions — `FLIGHT` while roaming, `STATION` while docked — using an
**idempotent `playMusic`** so re-asserting the current track is a no-op and `STATION` spans the
walkaround/sub-desks/hub-return gap-free.

**Looping thrust + an added port method.** The engine cue (`Sfx.THRUST`) is a *continuous* looping
sound — a real polish win for a space game — gated on the thrust start/stop transition. The original
`play(sfx)`-only surface could *start* a loop but never cleanly *stop* it, risking a stuck loop. We
therefore added a minimal **`stopSfx(sfx: Sfx)`** to the port: `play(THRUST)` starts the loop on the
not-thrusting→thrusting rising edge and `stopSfx(THRUST)` ends it on the falling edge;
`LibGdxAudioService` tracks the loop's instance id so it can stop exactly that voice. One-shot cues have
nothing sustained to stop, so `stopSfx` is a no-op for them. `PlayScreen.hide()` also stops the loop so
it can't bleed into other screens. (The looping variant was chosen over a one-shot ignition blip.)

**UI taps** play through a shared hook on `OrbitalUiSkin` (`uiTapSound`) plus an `installTapSound(stage)`
helper that adds one stage-root `ChangeListener` per UI screen, firing only on `Button` change-events
(so a ScrollPane scroll doesn't machine-gun the cue). The play screen is intentionally excluded so its
gameplay buttons keep their own cues. The app wires the hook to `Sfx.UI_TAP` once and clears it on
dispose.

**Settings & persistence.** Audio preferences live on the single-row `settings` table via an **additive
v13→v14 migration** (`13.sqm`: three `ALTER TABLE … ADD COLUMN` with defaults — `master_muted` 0,
`sfx_volume` 1.0, `music_volume` 0.5 — so a migrated save reads back audio-enabled at default levels;
`SaveVersion.CURRENT = 14`; regenerated `databases/14.db` baseline). The old whole-row
`INSERT OR REPLACE` handedness write is replaced by **per-field UPDATE**s (`updateHandedness` /
`updateAudioSettings`) over a seeded row, so toggling one preference never clobbers another's column.
The in-flight `SettingsOverlay` exposes a mute toggle and stepped SFX/music volume controls that apply
to the live service immediately on the render thread and persist asynchronously through the single-writer
`SaveExecutor`, exactly like the handedness toggle.

## Consequences

- **Determinism preserved.** The `audio`/`settings` value types and the `AudioService`/`NoOpAudioService`
  port are engine-free and stay within the UC31 purity-guard scope; `LibGdxAudioService` is isolated in
  `render/`. The replay harness runs headless with no audio device (AC#5).
- **Graceful degradation.** A clip that fails to load is logged and skipped (never fatal); a missing cue
  or a `stopSfx` on a one-shot is a silent no-op. Until real assets land, the game simply runs quieter.
- **Placeholder content, real system.** Clips are synthesised by a committed generator
  (`tools/gen-audio-placeholders.mjs`, Node stdlib, deterministic) into `assets/audio/sfx/*.wav` and
  `assets/audio/music/*.wav`. THRUST and the music pads are authored as seamless loops. Replacing them
  with licensed audio is a content-only follow-up — no code change needed (same file names/paths).
- **Audio focus scope (MVP).** App background/foreground pauses/resumes music via libGDX
  `Game.pause()/resume()`. Full Android `AudioManager` audio-focus handling (transient ducking for
  notifications, focus loss on a phone call/other media) is **deferred** as a known limitation — a
  best-effort follow-up, not required for the MVP.
- **Schema cost.** v14 is now the floor; the additive migration is minSdk-24-safe (`ALTER TABLE ADD
  COLUMN` long predates SQLite on API 24) and reversible only by a forward migration, per ADR 0002.
- **One new port method.** `stopSfx` is a permanent part of the audio contract; any future backend must
  implement it (the no-op backend already does).
