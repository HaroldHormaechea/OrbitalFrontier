# Use Case 38: Save-slot management UI

## Summary
Add **save-slot management**. Today the main menu is a binary Start/Continue over a single autosave (UC21), with no named slots, manual save, timestamps, or multiple profiles. Build a save UI that lists multiple slots with metadata (slot name, credits, sector, play time, last-saved timestamp), and supports **manual save**, **load**, **delete**, and **new game into a chosen slot**, layered on top of the existing single-DB persistence (ADR 0002) by partitioning saves per slot.

## Acceptance Criteria
1. A save/load screen lists multiple slots, each showing name, last-saved timestamp, and a short state summary (credits, sector, play time).
2. The player can manually save to a slot, load a slot, delete a slot (with confirmation), and start a new game into an empty slot.
3. The existing single-slot autosave continues to work and appears as a slot.
4. Slot data is isolated so loading/deleting one slot never corrupts another.
5. `./gradlew :core:ktlintCheck :core:test` green; slot isolation and round-trip save/load are unit-tested.

## Potential Pitfalls & Open Questions
- **Decision** — multiple DB files vs. a slot column within the single DB (ADR 0002 chose one DB); pick one and, if it changes the schema, add a migration + ADR.
- **Edge case** — overwriting an occupied slot needs the same overwrite warning UC21 introduced.
- **Risk** — interacts with autosave (UC52); define which slot autosave targets.

## Original Description
Autonomously captured from the UI/UX analysis — there is only a single autosave with Start/Continue; no slot management exists.
