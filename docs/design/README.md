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
| Ship & Controls | [ship-and-controls.md](ship-and-controls.md) | in-progress | Roam |
| World & Sector | [world-and-sector.md](world-and-sector.md) | in-progress | Roam |
| Missions | [missions.md](missions.md) | in-progress | Earn |
| Combat & Encounters | [combat.md](combat.md) | deferred (real-time decided) | Earn |
| Economy & Resources | [economy-and-resources.md](economy-and-resources.md) | in-progress | Earn → Improve |
| Upgrades & Progression | [upgrades-and-progression.md](upgrades-and-progression.md) | in-progress | Improve |
| Save & Persistence | [save-and-persistence.md](save-and-persistence.md) | in-progress | (cross-cutting) |
| Power & Energy | [power-and-energy.md](power-and-energy.md) | in-progress | (cross-cutting) |
| Station Building | [station-building.md](station-building.md) | in-progress (UC15: sim + persistence; UI/world-surfacing deferred) | Improve, deeper |
| Station Interior (on-foot) | [station-interior.md](station-interior.md) | in-progress (UC19: transient prototype; full interior system deferred) | Improve |

## Candidate notes (surfaced during design, not yet authored)

These systems came up while writing the notes above and are currently captured *inline*
within them. Promote each to its own note when it grows enough to warrant one:

| System | Currently captured in | Notes |
|---|---|---|
| Crew | ship-and-controls, combat, economy | Crew operate (auto-aim) turrets; hireable; upgradeable "to a degree". |
| Sensors / Scanning & Transponders | world-and-sector, upgrades | Beacons advertise POIs; active scan reveals hidden contacts (sensor tech). |
| Jump / Inter-sector travel | world-and-sector | **Fixed jump gates** (ADR 0004); fixed graph across 3 MVP sectors, no MVP fuel cost. |
| Ship Radio / Comms | missions, upgrades | Receives broadcast mission offers; comms upgrade category. |
| Factions & Reputation | missions, economy, upgrades | Station faction state drives offers/prices; reputation gating. **Post-MVP.** |

## Adding a note

1. Copy [`_TEMPLATE.md`](_TEMPLATE.md) to `<system>.md`.
2. Fill it in; set `Status` and `Last updated`.
3. Add a row to the table above.

Keep one system per file. Cross-link related notes and the brief sections they serve.
