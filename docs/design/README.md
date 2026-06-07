# Design Notes

Internal design notes for Orbital Frontier's game systems. Each note captures
**intent and ideas** for one system — mechanics, player-facing behavior, data,
and open questions. They are living documents and may be incomplete; a note marked
`draft (not yet specified)` means the design is still open.

These notes are **advisory**. `PROJECT_BRIEF.md` remains the source of truth — if a
note conflicts with the brief, the brief wins (surface the conflict, don't silently
choose). Decisions that become binding should be promoted to an [ADR](../adr/).

## Index

| System | File | Status | Loop pillar |
|---|---|---|---|
| Ship & Controls | [ship-and-controls.md](ship-and-controls.md) | draft | Roam |
| World & Sector | [world-and-sector.md](world-and-sector.md) | draft | Roam |
| Missions | [missions.md](missions.md) | draft | Earn |
| Combat & Encounters | [combat.md](combat.md) | draft | Earn |
| Economy & Resources | [economy-and-resources.md](economy-and-resources.md) | draft | Earn → Improve |
| Upgrades & Progression | [upgrades-and-progression.md](upgrades-and-progression.md) | draft | Improve |
| Save & Persistence | [save-and-persistence.md](save-and-persistence.md) | draft | (cross-cutting) |
| Station Building | [station-building.md](station-building.md) | draft (post-MVP) | Improve, deeper |

## Adding a note

1. Copy [`_TEMPLATE.md`](_TEMPLATE.md) to `<system>.md`.
2. Fill it in; set `Status` and `Last updated`.
3. Add a row to the table above.

Keep one system per file. Cross-link related notes and the brief sections they serve.
