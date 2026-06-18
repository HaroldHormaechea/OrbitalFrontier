# Use Case 41: Combat / bounty mission type

## Summary
Add a **combat mission type** that ties the existing combat system (UC13) to the mission board. Today `MissionType` has only MINING and COURIER, combat.md notes "the full mission flow (objectives, bounty payout) is unbuilt", and `EncounterSpawner` carries only a "thin mission-spawn hook". Introduce bounty / clear-out missions: accept a contract to destroy a designated hostile or clear an encounter zone, track the objective, and pay a bounty (plus reputation, per UC43) on completion.

## Acceptance Criteria
1. A new combat/bounty `MissionType` is offered on the mission board and via radio (UC12), with a target (specific hostile or encounter zone) and a bounty reward.
2. Accepting the mission spawns/targets the relevant hostile(s) via the existing encounter system and tracks kill progress.
3. Destroying the target(s) completes the mission and pays the bounty; failing/abandoning is handled like other mission types.
4. Objective progress surfaces on the HUD objective line (UC34).
5. `./gradlew :core:ktlintCheck :core:test` green; a recorded playthrough (UC02) accepts a bounty, destroys the target, and asserts payout.

## Potential Pitfalls & Open Questions
- **Dependency** — pairs with combat reputation (UC43) and loot/salvage (UC42); bounty payout vs. salvage drops should not double-count.
- **Edge case** — target destroyed by something other than the player, or player death mid-mission (UC33).
- **Open question** — bounty values and target selection are `[TUNE]`.

## Original Description
Autonomously captured from the feature catalog (missions.md "combat missions remain a later phase", combat.md "full mission flow unbuilt", EncounterSpawner "thin mission-spawn hook") and code (MissionType has only MINING/COURIER).
