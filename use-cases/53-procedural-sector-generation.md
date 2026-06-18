# Use Case 53: Procedural sector generation

## Summary
Replace the single hand-authored 3-sector map with **seed-based procedural sector generation**. Today the whole world is one hand-authored map (`MvpSectorMap`) with hardcoded POI positions; world-and-sector.md leaves open "Procedural generation: how is content density and placement determined? Seed source and what parameters vary per sector?" Generate sectors from a seed (placing stations, asteroid fields, gates, and POIs by density parameters), persisting the seed plus any player-caused deltas (depleted fields, built stations) so worlds are reproducible and save-stable.

## Acceptance Criteria
1. Sectors are generated from a seed: station, asteroid-field, gate, and POI placement derive from seed + per-sector density/parameters rather than hardcoded coordinates.
2. The generated world remains a connected graph via jump gates (ADR 0004) — every sector is reachable.
3. The world seed and player-caused deltas (field depletion, owned stations, revealed contacts) persist across save/reload and regenerate identically.
4. Generation is pure/deterministic so existing replay captures can be re-pinned to a fixed seed.
5. `./gradlew :core:ktlintCheck :core:test` green; a generation test asserts reachability and determinism for a fixed seed.

## Potential Pitfalls & Open Questions
- **Open question** — density/parameter ranges and how much variety per sector are undecided (`[TUNE]`); the MVP could seed-generate but keep counts close to the current authored map.
- **Risk** — many recorded playthroughs pin exact POI positions on `MvpSectorMap`; switching to generation will require re-recording or seeding to reproduce those layouts (coordinate with the playthrough harness, UC02).
- **Decision** — full procedural vs. seeded-but-curated (templates + jitter); default to the latter for control.

## Original Description
Autonomously captured from the feature catalog (world-and-sector.md procedural generation open) and the capture analysis (every scenario pinned to one hardcoded 3-sector map).
