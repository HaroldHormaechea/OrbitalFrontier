# ADR 0027 — Accessibility options: colourblind-safe palette, UI text scale, reduced motion

- **Status:** Accepted
- **Date:** 2026-06-19

## Context

Before UC39 the only accessibility accommodation was the handedness toggle (UC37). The brief's
`accessibility_target` asks for "legible text, adequate touch-target sizes, and colourblind-friendly
cues as a best-effort guideline." UC39 adds three persisted, live-applied preferences in the existing
settings UI (UC37 / ADR 0025): a colourblind-safe palette, a UI text size independent of the global UI
scale (ADR 0015 / ADR 0025), and a reduced-motion toggle.

The settings infrastructure (the shared `SettingsPanel`, the per-field transactional `SettingsRepository`,
the rendering-only mutable `UiScale` global restored at startup) already established the patterns; UC39
follows them so the three new prefs slot in additively. ADR 0025 had deliberately documented an
"Accessibility" group as omitted-not-faked pending this use case — that group is now built and that note
is retired.

## Options considered

| Option | For | Against |
|---|---|---|
| **Three rendering-only globals (`ColorVisionMode`/`Palette.mode`, `TextScale`, `MotionPreference`), additive v18 settings columns, applied live + restored at startup (chosen)** | Mirrors the proven UC37 `UiScale` pattern exactly; rendering-only so determinism/replay (ADR 0006) is untouched; additive migration is minSdk-24-safe; each pref live-applies then persists via the single save writer | Adds three mutable globals (accepted: single-writer/single-reader-per-frame, like `UiScale`) |
| Recolour by mutating the shared `Palette` `Color` instances in place | No new accessors | Breaks the "Palette colours are immutable shared intent" contract; per-frame aliasing hazards; would corrupt cached instances |
| Attenuate (slow) parallax for reduced motion instead of stopping it | Subtler | Still moving — does not satisfy players who need motion *gone*; "full stop" is unambiguous and cheap |
| Scale ALL text (incl. in-flight HUD) with the text-size knob | One knob covers everything | HUD/world-space readouts are sized to the playfield and already scale with `UiScale`; double-scaling risks layout-guard breakage (UC22/UC26) and font blur |

## Decision

1. **Colourblind-safe palette (AC#1).** `Palette` becomes mode-aware: the STATE-conveying tokens
   (`DANGER`, `SUCCESS`, `WARNING`, `HAZARD_500`) and the new `CONTACT_HOSTILE` / `STATION_FRIENDLY`
   map-marker accessors return one of **two cached, immutable `Color` instances per mode** — the standard
   token or an **Okabe-Ito** colourblind-safe variant — selected by `Palette.mode`. One stable instance
   per mode means no per-frame allocation and reference-identity stays valid. Brand accents (amber/cyan)
   and structural neutrals (void/steel) are never remapped (they do not convey state). The map-overlay
   station/contact markers (the real red/green "hostile vs friendly" site) are migrated to the accessors;
   the minimap markers tint mode-aware (neutral white in standard mode = zero default-mode regression,
   colourblind-safe tint in colourblind mode). A pure, unit-tested `FactionColors.resolve` provides
   colourblind-distinguishable faction tints (League → Okabe-Ito blue `#0072B2`, Independents → orange
   `#E69F00`); `Faction.color` still has **no render site**, so this is the colourblind-correct hook the
   eventual faction-colour render must call — added and tested now, not yet wired.
2. **UI text scale (AC#2).** A new `TextScale` global (shaped like `UiScale`; clamp `0.85f..1.4f`, default
   `1.0f`) multiplies the Scene2D **skin font** size *on top of* `UiScale`. The in-flight HUD/world-space
   text deliberately follows `UiScale` only (it is sized to the playfield, not chrome) — a documented
   exclusion. The simultaneous-max corner (`UiScale ×3 × TextScale ×1.4`) upscales the 48px-baked font by
   ≤1.31×, which softens gracefully (Linear filtering) and never crashes — guarded by a unit test.
3. **Reduced motion (AC#3).** A new `MotionPreference` global (default: motion on). When enabled the
   parallax starfield is drawn **static** (per-layer camera offset zeroed) — a full stop, not an
   attenuation. The MVP has no screen-shake or decorative tweens today; the intent covers them, and any
   future such effect must consult this flag.
4. **Persistence + live-apply (AC#4).** Additive v17→v18 migration adds three global `settings` columns
   (`colorblind_mode TEXT DEFAULT 'STANDARD'`, `text_scale REAL DEFAULT 1.0`, `reduced_motion INTEGER
   DEFAULT 0`) — `ALTER TABLE ADD COLUMN` only (minSdk-24-safe, no UPSERT/rebuild), each with its own
   targeted UPDATE so persisting one never clobbers another. The three globals are restored at startup
   **before any screen builds** (beside `UiScale`), and each settings control applies live (palette/motion
   hit the global immediately; text scale re-applies to the host skin font + re-flows) then persists via
   the single save writer.

## Consequences

- The colourblind palette is opt-in and rendering-only; standard-mode rendering is byte-identical to
  pre-UC39 (the standard cached instances keep the exact UC27 hex / marker float values).
- Determinism/replay is untouched: all three knobs are read only in rendering, never in simulation
  (ADR 0006), exactly like `UiScale`.
- The three globals each expose `reset()` for test tear-down (they are process-global mutable state).
- `Faction.color` remains unrendered; when a faction-colour render site is added it must route through
  `FactionColors.resolve` to stay colourblind-correct.
- A future "reset all to defaults" settings action can call the four globals' `reset()` plus the
  repository defaults uniformly.
