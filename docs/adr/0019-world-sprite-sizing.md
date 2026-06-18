# ADR 0019 — Per-type world sprite sizing (single source of truth) (UC30)

- **Status:** Accepted
- **Date:** 2026-06-18
- **Refines:** [ADR 0015](0015-ui-scale-and-universal-object-glyphs.md) (universal in-world object
  glyphs) and the UC27 art integration that replaced the generated-shape glyphs with atlas sprites.
  Cross-references [ADR 0006](0006-determinism-and-playthrough-harness.md) for determinism only.

## Context

Since UC27 the live world no longer renders from `ShapeRenderer` primitives or generated glyphs: the
player ship, hostiles, projectiles, stations, asteroid fields, jump gates and revealed hidden contacts
all draw from the committed design-system texture atlas (`assets/orbital.png` / `assets/orbital.atlas`).
UC30 ("final sprite art for world rendering") was framed as if that art still had to be produced — but
that work was effectively delivered by UC27. The world already renders from real, committed sprite art.

**This UC therefore produced no new art.** There is no in-repo atlas packer, no art bundle was supplied,
and UC27's committed atlas is treated as the final art for the MVP. A genuine art *upgrade* (higher-fidelity
sprites, more frames/variants) is a legitimate follow-up use case, but it would require two things this
repo does not currently have: an art bundle (the source sprites) and a packer step to rebuild the atlas.
Recording that plainly here is mandatory so the scope reality is not masked by the UC's original title.

What remained genuinely open after UC27 was a *code-quality and documentation* gap that UC30 AC#3 targets:

1. The per-object-type world sprite **half-extents** were scattered as inline literals across three files
   (`ShipRenderer`, `HostileRenderer`, `WorldGlyphs`) and several still carried KDoc describing themselves
   as "unchanged from the old placeholder" — i.e. inherited defaults rather than deliberate per-type sizes.
2. Several renderers (`AsteroidFieldRenderer`, `GateRenderer`, `ShipRenderer`, `HostileRenderer`,
   `WorldGlyphs`) still self-described as "placeholder art", which is stale now that they draw delivered
   sprites or are intentional gameplay-range overlays.

`PROJECT_BRIEF.md` § Quality & Standards sets the texture-memory / performance budget (smooth 60 FPS on a
mid-range phone, reasonable APK size, stable frame-time) — that brief is the authority for the texture
budget; this ADR does not invent a new one.

## Options considered

| Option | For | Against |
|---|---|---|
| **Extract per-type half-extents into one `WorldSpriteSizes` object; reword stale KDoc; document here** | Sizes become a single auditable, deliberate per-type table; zero behaviour change; renderers stop lying about being placeholders | A small refactor touching three renderers; the size constants gain one level of indirection |
| Leave the literals inline, only reword the KDoc | Smallest diff | AC#3 ("set deliberately per object type, not inherited from placeholder defaults") is not really met — the values stay scattered and undocumented |
| Produce new sprite art / rebuild the atlas now | Would match the UC's literal title | No art bundle supplied, no packer exists; out of scope and would be inventing assets — explicitly deferred to a follow-up UC |

## Decision

Introduce `core/.../render/WorldSpriteSizes.kt` — a pure, engine-free object holding the world-space
**half-extent** (centre-to-edge, in world units) of each world sprite type, as the single source of truth.
`ShipRenderer`, `HostileRenderer` and `WorldGlyphs` now read their sizes from it instead of inline literals.
The reworded KDoc across the five renderers stops describing them as placeholders.

**The values are preserved exactly** — this is a zero-behaviour-change refactor. Verified against the prior
code:

| Type | Half-extent | Prior location (old → new, value identical) |
|---|---|---|
| Player ship | **18** | `ShipRenderer.DEFAULT_SIZE = 18f` → `WorldSpriteSizes.SHIP` |
| Hostile ship | **18** | `HostileRenderer.SIZE = 18f` → `WorldSpriteSizes.HOSTILE` |
| Projectile | **5** | `HostileRenderer.SHOT_RADIUS = 5f` → `WorldSpriteSizes.PROJECTILE` |
| Jump gate | **28** | `WorldGlyphs.GATE_GLYPH 28f` → `WorldSpriteSizes.GATE` |
| Asteroid field | **26** | `WorldGlyphs.ASTEROID_GLYPH 26f` → `WorldSpriteSizes.ASTEROID_FIELD` |
| Station | **22** | `WorldGlyphs.STATION_GLYPH 22f` → `WorldSpriteSizes.STATION` |
| Hidden contact | **16** | `WorldGlyphs.HIDDEN_CONTACT_GLYPH 16f` → `WorldSpriteSizes.HIDDEN_CONTACT` |

**Sizing rationale (relative scale).** Sizes are authored per type to give the world a readable visual
hierarchy: the jump gate (28) is the largest, reading as a navigation beacon; the asteroid field (26) is a
sizeable cluster just below it; the station (22) is a prominent destination; ship and hostile share one
scale (18) so allies and enemies read at the same size; the revealed hidden contact (16) is the smallest
discoverable icon; and the projectile (5) is deliberately tiny so shots read as ordnance, not ships.

**Visual size is decoupled from gameplay range — and that gap is intentional.** `WorldSpriteSizes` holds
*visual* half-extents only. The gameplay ranges live in the world/combat **model**, not in renderers, and
are unchanged by this UC:

- `JumpGate.triggerRadius`, `Station.dockingRadius`, `AsteroidField.miningRadius` — drawn as separate ring
  overlays by `GateRenderer` / `AsteroidFieldRenderer` (and docking visuals), tracing the actual model
  circle independent of the marker sprite's size.
- `CombatParams.hitRadius = 28`. The player ship's *visual* half-extent is 18 but its combat hit circle is
  **28** — deliberately larger than the sprite. This gap is **kept**, not a bug: combat tuning owns the hit
  circle, and shrinking it to match the sprite would change UC13 combat feel and desync recorded captures.
  The sprite is the silhouette; the hit radius is the gameplay rule. They are allowed to differ, and they
  do. (Ranges stay data-driven in the model so collision/dock/mine/jump captures — UC05, UC06, UC13 — are
  unaffected by any visual size.)

**Texture / POT-rounding assumption.** The committed atlas page is 1024×748. Many mobile GPUs sample
non-power-of-two textures by rounding up to the next POT, so the page is assumed to occupy a 1024×1024
(≈4 MB at RGBA8888) texture-memory footprint on device. That single-page budget sits comfortably inside the
`PROJECT_BRIEF.md` performance budget, which remains the authority for the texture budget (this ADR does not
set a competing number). A future art upgrade that grows the atlas past one POT page must re-check that
budget.

## Consequences

- World sprite sizes are now decided in one place with a documented per-type rationale; changing the
  on-screen scale of an object type is a one-line edit in `WorldSpriteSizes`, not a hunt across renderers.
- The renderers no longer mis-describe themselves as placeholder art; `GateRenderer` and
  `AsteroidFieldRenderer` are documented as intentional gameplay-range ring overlays (per ADR 0015), with
  the marker sprite drawn by `WorldObjectRenderer`.
- **No new art was produced and no gameplay range changed.** An art-fidelity upgrade remains a future UC,
  gated on supplying an art bundle and adding an atlas packer; until then UC27's atlas is the final art.
- Risk is minimal: the change is a pure extract-constant refactor with values preserved exactly, so the
  existing replay/screen smoke paths exercise it unchanged.
