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
| 19 | [use-cases/19-station-walkaround-prototype.md](use-cases/19-station-walkaround-prototype.md) | Station walk-around (on-foot prototype) | done | 2026-06-09 |
| 20 | [use-cases/20-station-menu-grid-layout.md](use-cases/20-station-menu-grid-layout.md) | Station menu grid layout (≤4 rows × N columns) | done | 2026-06-09 |
| 21 | [use-cases/21-main-menu-start-continue.md](use-cases/21-main-menu-start-continue.md) | Main menu with Start / Continue and overwrite warnings | done | 2026-06-09 |
| 22 | [use-cases/22-map-reposition-top-right.md](use-cases/22-map-reposition-top-right.md) | Reposition map to top-right | done | 2026-06-09 |
| 23 | [use-cases/23-map-click-to-zoom-overlay.md](use-cases/23-map-click-to-zoom-overlay.md) | Click map to open full-height zoomed overlay | done | 2026-06-09 |
| 24 | [use-cases/24-map-item-labels.md](use-cases/24-map-item-labels.md) | Show name labels for map items | done | 2026-06-09 |
| 25 | [use-cases/25-debug-point-and-go-navigation.md](use-cases/25-debug-point-and-go-navigation.md) | Debug point-and-go navigation (debug builds only) | done | 2026-06-09 |
| 26 | [use-cases/26-bottom-right-action-arc.md](use-cases/26-bottom-right-action-arc.md) | Semicircular bottom-right action cluster | done | 2026-06-10 |
| 27 | [use-cases/27-integrate-design-system-art.md](use-cases/27-integrate-design-system-art.md) | Integrate design-system art atlas and palette | done | 2026-06-10 |
| 28 | [use-cases/28-scalable-game-font.md](use-cases/28-scalable-game-font.md) | Scalable game font / real typography | done | 2026-06-18 |
| 29 | [use-cases/29-final-ui-skin.md](use-cases/29-final-ui-skin.md) | Final UI skin / theme (replace placeholder skin) | done | 2026-06-18 |
| 30 | [use-cases/30-final-sprite-art-world-rendering.md](use-cases/30-final-sprite-art-world-rendering.md) | Final sprite art for world rendering | done | 2026-06-18 |
| 31 | [use-cases/31-audio-system.md](use-cases/31-audio-system.md) | Audio system — SFX & music | done | 2026-06-18 |
| 32 | [use-cases/32-pause-overlay.md](use-cases/32-pause-overlay.md) | Pause overlay | done | 2026-06-18 |
| 33 | [use-cases/33-ship-destruction-screen.md](use-cases/33-ship-destruction-screen.md) | Ship-destruction / game-over feedback | done | 2026-06-18 |
| 34 | [use-cases/34-expanded-flight-hud.md](use-cases/34-expanded-flight-hud.md) | Expanded flight HUD | done | 2026-06-18 |
| 35 | [use-cases/35-notification-event-feed.md](use-cases/35-notification-event-feed.md) | In-game notification / event feed | done | 2026-06-18 |
| 36 | [use-cases/36-tutorial-onboarding.md](use-cases/36-tutorial-onboarding.md) | First-run tutorial & onboarding | done | 2026-06-19 |
| 37 | [use-cases/37-settings-screen.md](use-cases/37-settings-screen.md) | Full settings screen | done | 2026-06-19 |
| 38 | [use-cases/38-save-slot-management.md](use-cases/38-save-slot-management.md) | Save-slot management UI | done | 2026-06-19 |
| 39 | [use-cases/39-accessibility-options.md](use-cases/39-accessibility-options.md) | Accessibility options | done | 2026-06-19 |
| 40 | [use-cases/40-purchase-confirmation-feedback.md](use-cases/40-purchase-confirmation-feedback.md) | Purchase/sale confirmation & economy feedback | done | 2026-06-19 |
| 41 | [use-cases/41-combat-bounty-missions.md](use-cases/41-combat-bounty-missions.md) | Combat / bounty mission type | done | 2026-06-19 |
| 42 | [use-cases/42-loot-salvage-economy.md](use-cases/42-loot-salvage-economy.md) | Loot & salvage from destroyed hostiles | pending | 2026-06-18 |
| 43 | [use-cases/43-combat-driven-reputation.md](use-cases/43-combat-driven-reputation.md) | Combat-driven reputation | pending | 2026-06-18 |
| 44 | [use-cases/44-combat-hud-feedback.md](use-cases/44-combat-hud-feedback.md) | Combat HUD — targeting, health bars & hit feedback | pending | 2026-06-18 |
| 45 | [use-cases/45-enemy-ai-spawn-director.md](use-cases/45-enemy-ai-spawn-director.md) | Richer enemy AI & encounter variety | pending | 2026-06-18 |
| 46 | [use-cases/46-dynamic-station-pricing.md](use-cases/46-dynamic-station-pricing.md) | Dynamic station pricing | pending | 2026-06-18 |
| 47 | [use-cases/47-junkyard-buy-used-parts.md](use-cases/47-junkyard-buy-used-parts.md) | Buy used parts at junkyards | pending | 2026-06-18 |
| 48 | [use-cases/48-reputation-gated-upgrades.md](use-cases/48-reputation-gated-upgrades.md) | Reputation-gated upgrade & ship availability | pending | 2026-06-18 |
| 49 | [use-cases/49-power-brownout-and-ui.md](use-cases/49-power-brownout-and-ui.md) | Power brownout/throttle & power UI surfacing | pending | 2026-06-18 |
| 50 | [use-cases/50-crew-depth-fleet-management.md](use-cases/50-crew-depth-fleet-management.md) | Crew depth & fleet/crew management screen | pending | 2026-06-18 |
| 51 | [use-cases/51-station-building-ui-and-surfacing.md](use-cases/51-station-building-ui-and-surfacing.md) | Station-building UI & world surfacing | pending | 2026-06-18 |
| 52 | [use-cases/52-autosave-and-save-robustness.md](use-cases/52-autosave-and-save-robustness.md) | Periodic autosave, indicator & save robustness | pending | 2026-06-18 |
| 53 | [use-cases/53-procedural-sector-generation.md](use-cases/53-procedural-sector-generation.md) | Procedural sector generation | pending | 2026-06-18 |
| 54 | [use-cases/54-additional-poi-types.md](use-cases/54-additional-poi-types.md) | Additional POI types — derelicts, distress, hazards | pending | 2026-06-18 |
| 55 | [use-cases/55-refresh-readme-and-docs.md](use-cases/55-refresh-readme-and-docs.md) | Refresh stale README & status docs | pending | 2026-06-18 |
