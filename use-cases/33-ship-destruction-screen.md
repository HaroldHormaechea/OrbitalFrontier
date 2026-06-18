# Use Case 33: Ship-destruction / game-over feedback

## Summary
Give ship destruction real player-facing feedback. Today `Respawn` exists and `PlayScreen.runCombat` **silently teleports** the player back to `lastDockedStation` when the ship is destroyed — no "ship destroyed" screen, no indication of what was lost, no acknowledgement of the event. Add a **destruction screen** that interrupts play, communicates the loss (cargo lost, credits/insurance cost, where the player respawns), and requires a deliberate continue before returning to control at the respawn station.

## Acceptance Criteria
1. On ship destruction, the simulation halts and a destruction/game-over screen is shown instead of a silent teleport.
2. The screen reports the consequences: cargo lost, any credit/insurance penalty, and the respawn location.
3. The player must confirm ("Continue") to respawn; respawn then restores control at `lastDockedStation` as today.
4. The respawn state is saved so a crash/close after destruction does not duplicate or skip the penalty.
5. `./gradlew :core:ktlintCheck :core:test` green; a test drives a destruction event and asserts the consequence summary + respawn state.

## Potential Pitfalls & Open Questions
- **Decision** — death model: full game-over with permadeath, or respawn-with-penalty (current behavior). MVP keeps respawn-with-penalty but surfaces it; permadeath is out of scope unless the brief says otherwise.
- **Edge case** — destruction with no prior docked station (very start of game) needs a defined fallback respawn point.
- **Open question** — exact penalty (cargo loss vs. credit cost) is a `[TUNE]` value to confirm with the owner.

## Original Description
Autonomously captured from the UI/UX analysis: destruction currently teleports the player back with no feedback. A game-over/consequence moment is a core readiness gap.
