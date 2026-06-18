# Use Case 54: Additional POI types — derelicts, distress signals & hazards

## Summary
Add world content variety beyond gates, asteroid fields, and stations. world-and-sector.md flags "(Later: derelicts/wrecks, distress signals, hazards — not MVP.)" Introduce a few **new POI types**: **derelicts/wrecks** the player can scavenge (salvage for resources/parts), **distress signals** that lead to a mini-event (rescue/ambush), and environmental **hazards** (e.g. radiation/debris fields) that affect the ship while traversed. These enrich the Roam pillar and give scanning (UC10) and combat (UC13/UC45) more to interact with.

## Acceptance Criteria
1. At least three new POI types exist: a scavengeable derelict/wreck, a distress signal that triggers a mini-event, and a traversal hazard zone.
2. Derelicts yield salvage (resources/parts) on interaction, respecting cargo capacity; distress signals branch into a defined event (e.g. reward or ambush spawn); hazards apply a defined effect while the ship is inside.
3. New POIs surface through the existing detection layer — some visible via transponder, some only via active scan (UC10).
4. POI placement and outcomes are seed-deterministic and persist across save/reload (a scavenged derelict stays empty).
5. `./gradlew :core:ktlintCheck :core:test` green; a playthrough interacts with each new POI type and asserts its outcome.

## Potential Pitfalls & Open Questions
- **Dependency** — distress-signal ambushes lean on the combat/encounter system (UC13/UC45); derelict salvage shares loot logic with UC42.
- **Open question** — hazard effects (damage over time, fuel drain, sensor scramble) and all yields are `[TUNE]`.
- **Decision** — how many of each per sector; pairs naturally with procedural generation (UC53).

## Original Description
Autonomously captured from the feature catalog (world-and-sector.md "Later: derelicts/wrecks, distress signals, hazards — not MVP").
