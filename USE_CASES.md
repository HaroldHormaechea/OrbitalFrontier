# Use Cases

Status ledger for use cases under `use-cases/`. Machine-maintained — the `define-use-case` skill appends rows; the dev-team orchestrator updates the `Status` and `Updated` columns as it works. Do not hand-edit those two columns unless you know why; edit the use-case file or re-run the skill instead.

Statuses:
- `pending` — saved but not yet picked up by the dev-team
- `in-progress` — the dev-team has started analysis
- `done` — implementation and tests completed
- `blocked` — the dev-team escalated (6-round cap hit, user abort, or infeasibility)

| # | File | Title | Status | Updated |
|---|------|-------|--------|---------|
| 01 | [use-cases/01-flyable-ship-empty-sector.md](use-cases/01-flyable-ship-empty-sector.md) | Flyable ship in an empty sector | done | 2026-06-07 |
| 02 | [use-cases/02-playthrough-record-replay-harness.md](use-cases/02-playthrough-record-replay-harness.md) | Deterministic playthrough record & replay test harness | done | 2026-06-07 |
| 03 | [use-cases/03-sector-world-and-jump-gates.md](use-cases/03-sector-world-and-jump-gates.md) | Sector world & fixed jump gates | done | 2026-06-07 |
| 04 | [use-cases/04-full-game-state-save-load.md](use-cases/04-full-game-state-save-load.md) | Full game-state save & load | done | 2026-06-07 |
| 05 | [use-cases/05-stations-and-docking.md](use-cases/05-stations-and-docking.md) | Stations & docking | done | 2026-06-07 |
| 06 | [use-cases/06-asteroid-mining-resources-cargo.md](use-cases/06-asteroid-mining-resources-cargo.md) | Asteroid mining, resources & cargo | done | 2026-06-08 |
| 07 | [use-cases/07-fuel-and-power-energy.md](use-cases/07-fuel-and-power-energy.md) | Fuel & power/energy | done | 2026-06-08 |
| 08 | [use-cases/08-credits-and-trading.md](use-cases/08-credits-and-trading.md) | Credits & inter-station trading | done | 2026-06-08 |
| 09 | [use-cases/09-outfitting-upgrades-junkyards-ships.md](use-cases/09-outfitting-upgrades-junkyards-ships.md) | Ship outfitting, upgrades, junkyards & multiple ships | done | 2026-06-08 |
| 10 | [use-cases/10-scanning-and-transponders.md](use-cases/10-scanning-and-transponders.md) | Active scanning & hidden contacts | done | 2026-06-08 |
| 11 | [use-cases/11-crew.md](use-cases/11-crew.md) | Crew | done | 2026-06-08 |
| 12 | [use-cases/12-missions-mining-courier.md](use-cases/12-missions-mining-courier.md) | Missions — mining & courier | done | 2026-06-08 |
| 13 | [use-cases/13-real-time-combat.md](use-cases/13-real-time-combat.md) | Real-time combat | done | 2026-06-08 |
| 14 | [use-cases/14-factions-and-reputation.md](use-cases/14-factions-and-reputation.md) | Factions & reputation (post-MVP) | done | 2026-06-08 |
| 15 | [use-cases/15-station-building.md](use-cases/15-station-building.md) | Station building (post-MVP stretch) | done | 2026-06-08 |
| 16 | [use-cases/16-fuel-duration-tuning.md](use-cases/16-fuel-duration-tuning.md) | Fuel duration tuning (~30 min under propulsion) | done | 2026-06-09 |
| 17 | [use-cases/17-starting-cash-50k.md](use-cases/17-starting-cash-50k.md) | Starting cash set to 50k | done | 2026-06-09 |
| 18 | [use-cases/18-fix-station-refueling.md](use-cases/18-fix-station-refueling.md) | Fix broken station refuelling | done | 2026-06-09 |
| 19 | [use-cases/19-station-walkaround-prototype.md](use-cases/19-station-walkaround-prototype.md) | Station walk-around (on-foot prototype) | pending | 2026-06-09 |
| 20 | [use-cases/20-station-menu-grid-layout.md](use-cases/20-station-menu-grid-layout.md) | Station menu grid layout (≤4 rows × N columns) | pending | 2026-06-09 |
| 21 | [use-cases/21-main-menu-start-continue.md](use-cases/21-main-menu-start-continue.md) | Main menu with Start / Continue and overwrite warnings | pending | 2026-06-09 |
| 22 | [use-cases/22-map-reposition-top-right.md](use-cases/22-map-reposition-top-right.md) | Reposition map to top-right | pending | 2026-06-09 |
| 23 | [use-cases/23-map-click-to-zoom-overlay.md](use-cases/23-map-click-to-zoom-overlay.md) | Click map to open full-height zoomed overlay | pending | 2026-06-09 |
| 24 | [use-cases/24-map-item-labels.md](use-cases/24-map-item-labels.md) | Show name labels for map items | pending | 2026-06-09 |
