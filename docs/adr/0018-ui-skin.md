# ADR 0018 — Finished UI skin: runtime palette-driven nine-patch chrome (UC29)

- **Status:** Accepted
- **Date:** 2026-06-18

## Context

Every Scene2D screen (MainMenu, StationHub, Trade, Shipyard, Outfit, Hire, MissionBoard, Play,
StationWalkaround) styled its widgets through one class literally named `PlaceholderControlsSkin`.
Its button/panel chrome was flat, single-colour `Pixmap.fill()` rectangles (a 16×16 solid texture
stretched to size) and it only ever styled the **default** button state — pressed/disabled/focused
looked identical to rest, and the MainMenu "CONTINUE" disabled affordance was a manual `Color` tint
applied at the call site rather than a styled state. UC29 asks for a **finished design-system UI
skin**: bevelled, bordered buttons and panels built on the [`Palette`] design tokens (ADR 0015 / UC27)
and the bundled game font (ADR 0017 / UC28), with pressed, disabled and focused states all styled
(AC#4), replacing the "placeholder" naming and the programmer-art fills everywhere they are referenced
(AC#1/#2).

Constraints in force:

- **ADR 0001 — `core` stays JVM-testable; no engine natives / GL on the JVM test path.** Any drawable
  generation that touches `Texture`/`Pixmap` is GL-bound, so it must run only on a live screen (the GL
  thread), never in a headless test. The skin was already constructed only by live screens; that holds.
- **ADR 0015 — `UiScale.factor` is the single knob.** The Scene2D screens render through a
  ×`UiScale.factor` viewport, so the skin must not double-apply scale; authored chrome metrics stay at
  their base values and the viewport magnifies them.
- **ADR 0017 — no in-tree binary art pipeline.** The bitmap font's `.fnt`/PNG were baked **out-of-tree**
  by a throwaway tool and committed as assets; there is deliberately **no** in-repo texture packer or
  asset-bake step. The same constraint applies to any atlas-packed UI art.
- **UC27 AC#3 — the action-arc glyphs and movement joystick are atlas-backed sprites** with a documented
  no-atlas fallback; their geometry must not regress (UC29 AC#3).
- **UC20 / width-budget screens — the station-menu grid fits columns to the live viewport.** A panel
  background must not add layout insets that perturb that fit or clip the smallest supported screen
  (UC29 edge case).

## Options considered

| Option | For | Against |
|---|---|---|
| **Runtime palette-driven nine-patches generated from `Pixmap` on the GL thread (chosen)** | No new asset pipeline (honours ADR 0017's no-in-tree-packer stance); chrome is derived directly from the `Palette` tokens, so one edit re-themes everything; a small generated texture with split insets stretches cleanly to any button/panel size; per-state drawables (up/down/disabled/over/focused) are trivial to author; stays off the JVM test path exactly as the prior generated fills did | Fixed authored border/bevel thickness (not resolution-independent vector art); chrome is procedural, not hand-drawn pixel art |
| Atlas-packed nine-patch UI art (`.9.png` regions in `orbital.atlas`) | Artist-authored look; consistent with the in-world sprite path | **Rejected for the MVP** — requires an out-of-tree pack/bake step for UI chrome (a second binary pipeline beyond ADR 0017's font bake) and couples every theme tweak to a re-pack; disproportionate for flat steel/amber chrome that the palette already describes |
| Keep flat `rect()` fills, only add per-state colours | Smallest change | Fails AC#1 — still reads as placeholder programmer-art; no border/bevel/finished identity |

## Decision

Rename `PlaceholderControlsSkin` → **`OrbitalUiSkin`** (same public surface; consumer call sites change
only by type name) and build its button and panel chrome at runtime as **palette-driven nine-patches**.
A single helper generates a small (`24²`) RGBA texture per drawable — a `Palette` fill, a `STEEL_400`
hairline border, a top/left bevel highlight, and an optional bottom accent edge — and wraps it in a
`NinePatch` whose split insets (`8` px corners) preserve the border/bevel/accent corners while a
one-texel centre stretches to any drawn size.

- **Per-state button drawables (AC#4):** `up` STEEL_600 + STEEL_400 border; `down` STEEL_500 + an AMBER
  accent edge; `disabled` muted VOID_700 + STEEL_300 border; `over`/`focused` STEEL_600 + AMBER_500
  border highlight. Plus per-state font colours (strong / amber-pressed / muted-disabled). The MainMenu
  greyed "CONTINUE" now comes from `isDisabled` driving the `disabled` drawable + `disabledFontColor` —
  the manual `Color` tint is removed.
- **`panel`** is a bevelled VOID_800 + STEEL_400 nine-patch used as each screen root's background, with
  its drawable pad insets pinned to **zero** so it only paints and never perturbs the UC20 grid or any
  width-budget layout.
- **Labels:** body text uses `TEXT_BODY`; a new `titleLabelStyle` (AMBER accent) styles screen titles.
- **`scrollPaneStyle`** is added for completeness (a currently-unused API — no screen mounts a
  `ScrollPane` today) so the finished skin covers every widget kind without a later re-theme.
- **Unchanged:** the UC27 atlas-backed joystick/action-arc sprite path and its no-atlas fallback (AC#3
  geometry untouched); the UC28 font line and its `GameFont.NORM`-only downscale; the texture-ownership
  model (the skin owns/disposes its generated textures + font, borrows the shared atlas).
- Chrome metrics (patch size, corner split, border/accent thickness) are **named constants** so the UI
  layout guard can pin them (AC#5).

### AC#2 interpretation — "no screen falls back to generated solid shapes"

UC29 AC#2 requires that no screen render the old placeholder "generated solid shapes." The
**designed, bevelled, bordered, per-state nine-patch chrome introduced here is not a "generated solid
shape"** in the sense AC#2 retires — that phrase refers to the flat single-colour `rect()` fills and the
no-art fallback circles that read as programmer-art. The new chrome is a finished, palette-derived design
surface; that it is *computed from the palette at runtime* rather than *packed from an artist PNG* is an
implementation detail of how the design system is realised, chosen above precisely because an atlas UI
pipeline was rejected for the MVP (no in-tree packer; an out-of-tree binary step would duplicate ADR
0017's font-bake pipeline for no proportionate gain on flat steel/amber chrome). The no-atlas fallback
shapes survive only on the JVM/no-GL path (and only for the joystick/glyph sprites, per UC27), never on a
real device screen.

## Consequences

- The whole UI re-themes from the `Palette`: changing a token restyles every button, panel and title in
  one place, with no asset re-pack.
- Pressed/disabled/focused are now real styled states; the MainMenu disabled-button special-case (and its
  `DISABLED_TINT` constant + `Color` import) is gone.
- The skin remains GL-thread-only; headless tests continue to assert its wiring by **source scan** (the
  UC27/UC28 guard tests), not by constructing it — those guards reference the skin by file path and are
  updated to `OrbitalUiSkin.kt` alongside this change.
- Chrome resolution is fixed by the authored metrics; if a future high-DPI bucket needs crisper borders,
  bump `PATCH_SIZE`/`BORDER_PX` (still palette-driven, still no asset pipeline) — or, if hand-drawn UI art
  is wanted later, supersede this ADR with an atlas-UI decision that also introduces the packer it needs.
