# Use Case 30: Final sprite art for world rendering

## Summary
Replace the programmer-art / placeholder primitives that currently render the live world with **final sprite art** for the player ship, hostiles, stations, asteroid fields, jump gates, and the universal world-object glyphs. Multiple renderers admit they are placeholders: `AsteroidFieldRenderer` ("Placeholder art until real asteroid sprites exist"), `GateRenderer` ("Placeholder art until real gate art"), `ShipRenderer`/`HostileRenderer` (half-extents "unchanged from the old placeholder"), and `WorldGlyphs` ("placeholder primitives pending delivered design-system art"). Deliver and pack real sprites into the atlas and wire each renderer to draw them at correct world scale.

## Acceptance Criteria
1. Player ship, hostile ships, stations, asteroid fields, and jump gates each render from a real sprite (not a `ShapeRenderer` primitive or generated glyph).
2. `WorldGlyphs` and the minimap draw art-backed object icons instead of placeholder primitives.
3. Sprite world-sizes (half-extents) are set deliberately per object type, not inherited from the placeholder defaults, and collision/encounter ranges still match the visuals.
4. New art is packed into the texture atlas within the min-spec texture-memory budget.
5. `./gradlew :core:ktlintCheck :core:test` green; rendering is exercised by the existing replay/screen smoke paths.

## Potential Pitfalls & Open Questions
- **Missing input** — the actual art assets must be produced/sourced; this UC assumes they are delivered (or generated) for packing.
- **Edge case** — sprite rotation must follow ship heading; asteroid fields and stations may need multiple frames or variants.
- **Risk** — changing half-extents can desync hit/dock/mine ranges; keep gameplay ranges data-driven and re-verify captures (UC05 dock, UC06 mine, UC13 combat).

## Original Description
Autonomously captured from the capture analysis listing five+ renderer files self-described as placeholder art. The world is largely drawn with procedural shapes; this is a core readiness gap for a shippable build.
