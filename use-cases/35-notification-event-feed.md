# Use Case 35: In-game notification / event feed

## Summary
Add a **transient on-screen notification system** for discrete game events. Today events like jump completion, docking, mission acceptance/timeout/completion, low fuel, combat start, and credit changes are written **only to the logger** (INFO lines) and never surfaced to the player; the UC26 context readout is a single status label. Introduce a small queue of toast-style notifications (and optionally a scrollable recent-events feed) that surfaces these moments with appropriate styling and auto-dismiss, fed by the same gameplay event seams the audio system (UC31) uses.

## Acceptance Criteria
1. A notification appears on screen for at least: jump completed, docked/undocked, mission accepted/completed/failed-timeout, entered/left combat, low fuel, and credit gain/loss.
2. Notifications queue (don't overlap), auto-dismiss after a readable duration, and are styled by severity (info vs. warning).
3. Notifications are driven by events emitted from the pure core, not by polling, so they stay consistent with the simulation.
4. Notifications do not obstruct the action arc, minimap, or critical HUD readouts.
5. `./gradlew :core:ktlintCheck :core:test` green; a test asserts the expected events enqueue notifications.

## Potential Pitfalls & Open Questions
- **Edge case** — bursty events (combat) must coalesce/throttle so the queue doesn't flood.
- **Decision** — whether to also keep a persistent recent-events log (scrollable) or purely transient toasts; toasts are the MVP, the feed is optional.
- **Dependency** — shares the event-emission seam with audio (UC31); build that seam once.

## Original Description
Autonomously captured from the UI/UX analysis — discrete events are logged but never shown to the player.
