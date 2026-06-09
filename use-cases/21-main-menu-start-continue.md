# Use Case 21: Main menu with Start / Continue and overwrite warnings

## Summary
On launching the game, present a main/title menu with at least two options: Start (begin a new game) and Continue (resume from the existing save). Currently the game presumably boots straight into play or a save; this adds an explicit entry menu in front of it, building on the save/load system (UC04). Because Start begins a fresh game and would discard existing progress, when a saved game already exists, choosing Start must prompt two warnings (a double confirmation) before wiping the save and starting over. Continue loads the existing save and resumes.

## Acceptance Criteria
1. Launching the app shows a main menu with Start and Continue options before gameplay begins.
2. Continue loads the existing saved game and resumes play.
3. When a saved game exists and the player taps Start, the game shows two sequential warning confirmations; the new game only begins (and the old save is overwritten/reset) if the player confirms both. Cancelling either returns to the main menu with the save intact.
4. When no saved game exists, Start begins a new game without the warnings, and Continue is unavailable (disabled or hidden).
5. The menu is reachable on every launch (not just the first), and choosing options leaves the game in the correct state (new vs. resumed).

## Potential Pitfalls & Open Questions
- **Assumption** — "2 warnings" = two sequential confirmation dialogs (double-confirm) before destroying the existing save; both must be accepted to proceed.
- **Assumption** — With no save present, Continue is shown disabled or hidden (chosen for clarity); no warning on Start in that case.
- **Edge case** — A corrupt/partial save should not crash the menu; treat as "no usable save" (Continue disabled) or surface a clear message.
- **Ambiguity** — Exact wording of the two warnings is left to implementation; both must clearly communicate that existing progress will be lost.

## Original Description
"When loading the game put some menu with options, start and continue options. Start should prompt 2 warnings if a game is already saved."

## Clarifications
(None — went to save with the assumptions noted above.)
