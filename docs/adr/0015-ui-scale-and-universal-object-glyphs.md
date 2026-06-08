# ADR 0015 — UI ×2 scale knob + universal in-world object glyphs

- **Status:** Accepted
- **Date:** 2026-06-08

## Context

Two rendering problems were reported on the MVP flight build:

1. **The UI/HUD is too small.** On high-density phone screens the placeholder 1:1 Scene2D
   controls, fonts and HUD overlays render uncomfortably small. We want the chrome roughly
   **doubled** — but **only the chrome**: the world view (ship, gates, asteroids, the sense of
   scale and speed) must stay 1:1. Zooming the world camera would change gameplay feel and is
   explicitly out of scope (a user-confirmed product decision).

2. **Some objects render as nothing in the world.** In-world rendering was per-type, hand-wired
   in `PlayScreen.render`: ship, gate, asteroid field and hostiles each had a world renderer, but
   **`Station` had none** — a station showed only as a minimap square and drew nothing in space
   (`HiddenContact` likewise until scanned). The brief's design intent is "every object has a
   graphic" (`PROJECT_BRIEF.md` → in_scope #4; `docs/design/world-and-sector.md`). The real defect
   is *structural*: nothing stopped a POI kind from shipping with no world renderer.

Constraints: the 60 FPS / avoid-GC-stalls performance budget (`PROJECT_BRIEF.md` →
performance_budgets); SOLID + the Open/Closed seam already used for the minimap
(`docs/coding-guidelines.md`); `core` must stay JVM-testable with no engine types leaking into pure
logic (ADR 0001); and the determinism/replay invariant — no rendering value may enter
movement/combat/sim math (ADR 0006).

## Options considered

| Option | For | Against |
|---|---|---|
| **UI scale via the Scene2D viewport's `unitsPerPixel` + a single `UiScale.factor`, applied at use sites** | One knob; scales every actor + font with zero per-widget edits; world camera untouched; constants stay authored at base values | Two application paths (Scene2D viewport vs screen-space overlays); bitmap font scales bilinearly (slightly blurry) |
| Per-widget hardcoded ×2 sizes/fonts | Fully explicit | Bakes the factor into dozens of constants; no single knob; error-prone; easy to drift |
| Zoom the world camera too | "Everything bigger" in one move | Rejected — changes gameplay feel/scale; the product decision is UI-only |
| **Universal glyph resolver + one world renderer over `sector.pois`** (chosen) | "No graphic" impossible by construction (exhaustive `when` over sealed `Poi`); pure + JVM-testable; one render path | A new render seam; gate/asteroid marker shapes must migrate without changing their look |
| Add a bespoke `StationRenderer` (and one per future POI) | Smallest immediate diff | Doesn't fix the *structural* gap — the next new POI can still ship with no renderer |

## Decision

**Part 1 — UI ×2 (UI/HUD layer only).** Introduce `render.UiScale.factor` (= `2f`) as the single
magnification knob. Apply it at exactly two points: (a) Scene2D screens set their `ScreenViewport`'s
`unitsPerPixel = 1 / factor` via the shared `ScreenViewport.applyUiScale()` helper, scaling every
actor + font across all seven screens; (b) the three screen-space overlay renderers (`HudRenderer`,
`ShipSchematicRenderer`, `MinimapRenderer`) scale their built-in font (`BitmapFont.data.setScale`) and
multiply each base layout constant **at its use site**. Base constants stay authored at their ×1
values — the factor is never baked in, so `UiScale.factor` remains the one knob. The **world camera is
not touched**; in-world object sizes are unchanged.

**Part 2 — universal object graphics.** Add a pure, engine-free `render.WorldGlyph` descriptor (shape
enum + RGBA floats + world-unit size + optional label) and `render.WorldGlyphs.forPoi(poi)`, a
**compiler-exhaustive `when`** over the sealed `Poi` hierarchy returning **cached constant** glyphs
(only `Station` allocates, to carry its display-name label). One `render.WorldObjectRenderer` iterates
the whole `sector.pois` list and draws each POI's glyph; visibility mirrors the minimap (skip only an
unrevealed non-`Transponder` `Contact`). Stations get their box glyph through this path; a **revealed**
hidden contact draws a placeholder box too (per the product decision that every object has a graphic).
The bespoke `GateRenderer`/`AsteroidFieldRenderer` slim to **ring-only overlays** (trigger / mining
radius); their marker shapes moved into the base glyphs, reproducing the authored look.

## Consequences

- **The "no in-world graphic" bug cannot recur silently:** adding a new `Poi` subtype fails to compile
  until `WorldGlyphs.forPoi` gives it a glyph. Stations (and revealed hidden contacts) now render in
  the world.
- **One UI knob.** Changing `UiScale.factor` rescales the whole UI/HUD consistently; the world view is
  unaffected. A future per-platform or user-set scale becomes a one-line change.
- **Purity / testability preserved.** `UiScale`, `WorldGlyph` and `WorldGlyphs` are engine-free `core`
  types (ADR 0001); only `WorldObjectRenderer` and `ScreenScaling` touch libGDX. No scale value enters
  sim math, so determinism/replay guards stay green (ADR 0006).
- **Hot-path discipline.** `forPoi` allocates nothing for the fixed POI kinds (cached constants); the
  single per-call allocation is a station's labelled glyph, and sectors hold only a few stations.
- **Known follow-ups (not regressions):** the built-in `BitmapFont` scaled ×2 is bilinearly blurry —
  acceptable as a placeholder until a real scaled font/atlas exists. The scaled HUD context-panel stack
  (dock/mine/scan/radio) occupies more screen area and should be eyeballed on a device for crowding;
  layout is already expressed in scaled-viewport units so elements stay on-screen.
