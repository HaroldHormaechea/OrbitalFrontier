# ADR 0017 — Typography: a pre-baked bitmap game font (UC28)

- **Status:** Accepted
- **Date:** 2026-06-18

## Context

Until now every piece of on-screen text — the HUD readouts ([HudRenderer]), the map name
labels ([MinimapRenderer], [MapOverlayRenderer]), and all Scene2D screens via
[PlaceholderControlsSkin] — used libGDX's **built-in** `BitmapFont` (Arial-15), constructed
with the no-arg `BitmapFont()`. That font is a small ~15px master; under the ADR 0015 UI ×2
scale knob it was *up*-scaled (`data.setScale(uiScale)`), so the glyphs were bilinearly
stretched and admittedly blurry — `HudRenderer` literally documented "slightly blurry;
acceptable as a placeholder" and that the degree sign "may render as a blank". UC27 deferred
real typography "via freetype, later".

UC28 asks for a real, scalable game typeface that is crisp at the supported phone resolutions,
covers every glyph the UI draws today (including `°` and the mission-board `→`), and still
flows through the existing `UiScale.factor` knob.

Constraints in force:

- **ADR 0001 — `core` stays JVM-testable; no engine natives on the JVM test path.**
  `gdx-freetype` drags a platform `.so` (freetype native) that would load on the JVM test
  classpath. It must therefore **never** be a `:core` or `:android` dependency.
- **ADR 0015 — `UiScale.factor` is the single knob;** base sizes stay authored at ×1 and the
  factor is applied at the use site, never baked into a constant.
- **UC27 AC#8 — design-system colour tints** (`font.color`) must keep working.
- **60 FPS / texture-memory budget** (`PROJECT_BRIEF.md` → performance_budgets); min-spec
  devices already carry the 3.06 MiB `orbital.png` atlas page.

## Options considered

| Option | For | Against |
|---|---|---|
| **Pre-baked bitmap font (`.fnt` + page PNG), baked white-on-transparent at 48px, downscaled at runtime (chosen)** | No runtime freetype → ADR 0001 honoured; one tiny committed asset; crisp because glyphs are *minified* (Linear) not magnified; trivial to assert coverage in a headless guard (parse the `.fnt` text) | Fixed master size — extreme zoom past the bake cap would soften; a re-bake step is needed to add glyphs |
| Runtime freetype (`FreeTypeFontGenerator` on device) | Any size on demand; smallest asset (just the `.ttf`) | **Rejected** — puts gdx-freetype (a native) on `:core`/`:android`, breaking ADR 0001's JVM-test purity |
| SDF / MSDF font | Smooth at *any* scale from one small master | More pipeline + a custom shader; overkill for a fixed-DPI-bucket phone HUD; heavier to integrate now |

## Decision

Bundle a **pre-baked bitmap font** at `assets/fonts/orbital.fnt` (+ its single `orbital.png`
page, ≤ 512²) and ship the face's `OFL.txt`. The face is **JetBrains Mono** (SIL OFL 1.1) — a
monospace "tech readout" face that suits the HUD and, unlike the first-choice Share Tech Mono,
covers **both** `°` (U+00B0) and `→` (U+2192), so the mission-board arrow is kept verbatim (no
source substitution needed).

- Glyphs are baked **white on transparent** so `font.color` tints survive (UC27 AC#8).
- Charset = printable ASCII (`U+0020`..`U+007E`) + `°` + `→` — pinned in `GameFont.REQUIRED_GLYPHS`.
- Bake cap `BAKE_CAP_PX = 48`. At runtime each consumer **downscales** by
  `NORM = LEGACY_BASE_PX / BAKE_CAP_PX = 15/48 = 0.3125`, so the switch is visually
  size-neutral versus the old 15px built-in font, but rendered from a 48px master with
  **Linear** filtering → crisp instead of stretched.
- `GameFont` is engine-free constants (mirrors `AtlasRegions`); `GameFontLoader.load()` is the
  GL-thread loader that applies Linear min/mag filtering. Both live in `core`’s `render` package.

**Bake-time-only freetype.** The `.fnt`/PNG were generated **out-of-tree** by a throwaway Java
baker using `gdx-freetype` + `gdx-tools` `BitmapFontWriter` under a headless gdx app — run once,
not committed, and **not** a module dependency. To re-bake (e.g. to add a glyph): rasterize the
OFL `.ttf` at size 48, white, into a 512² `PixmapPacker` (RGBA8888), then
`BitmapFontWriter.writePixmaps` + `writeFont`. The committed runtime artifact is a plain
`.fnt` + `.png` loaded by gdx-core only.

**Per-consumer ownership (unchanged).** Each consumer keeps owning and disposing its **own**
`BitmapFont` over the shared `.fnt`, exactly as before — zero dispose-guard churn (the UC24
`labelFont` name, `glyphLayout.setText(labelFont, …)`, `labelFont.draw`, `labelFont.dispose`
contract is preserved). Regime-specific scale at the use site:

- `PlaceholderControlsSkin`: `setScale(NORM)` — **no** `uiScale`, because the Scene2D screens
  render through a ×`UiScale.factor` viewport (ADR 0015) that already magnifies. All eight
  Scene2D screens inherit the new font with zero constructor changes.
- `HudRenderer`: `setScale(NORM × uiScale)`.
- `MinimapRenderer` / `MapOverlayRenderer`: `setScale(NORM × uiScale × LABEL_FONT_SCALE)`.

This **supersedes** UC27's "custom fonts deferred via freetype" stance (its code comments in
`Palette.kt` / `HudRenderer.kt` / `PlaceholderControlsSkin.kt` are updated accordingly).

## Consequences

- Text is crisp across the DPI buckets (UC28 AC#2); `°` and `→` always render (AC#3); colour
  tints and the `UiScale` knob keep working (AC#4, UC27 AC#8).
- `core`/`android` gain **no** native dependency — ADR 0001 stays intact; the JVM test path is
  unaffected and a headless guard can assert glyph coverage by parsing the `.fnt` text.
- Memory cost is a handful of small `BitmapFont`s sharing one ~35 KiB page — negligible beside
  the existing atlas; well inside the min-spec budget.
- Adding a glyph or changing the face is a deliberate **re-bake** step (documented above), not a
  code change — the small price for keeping freetype off the shipped classpath.
- `android/build.gradle.kts` needs no change: `assets/` is already a registered source dir and
  `.fnt`/`.png` are already in `androidResources.noCompress`, so `fonts/orbital.fnt` packages
  and `Gdx.files.internal` resolves on device.
