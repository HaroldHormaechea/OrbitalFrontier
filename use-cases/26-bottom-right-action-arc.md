# Use Case 26: Semicircular bottom-right action cluster

## Summary
Replace the current vertical action stack in `PlayScreen` with a **semicircular arc of circular action buttons anchored in the bottom-right corner** (mirrored to bottom-left when the handedness setting is `LEFT_HANDED`). Each button is a **circle showing a generated placeholder icon glyph plus a text label**. **All** player actions move onto this arc: the always-present **FIRE** control plus the actions that today live in their own context panels — **DOCK, MINE, SCAN, RADIO** mission offers, and the **debug point-and-go** nav (debug builds only). Those dedicated panels (`positionDockPanel`/`positionMinePanel`/`positionScanPanel`/`positionRadioPanel`/`positionPointAndGoPanel`) are retired in favour of arc buttons. Buttons are placed at equal radius from the corner pivot so the set follows the natural sweep of a thumb; contextual actions appear on the arc only while their action is possible and the arc reflows to stay evenly spaced, while **FIRE is always visible and enabled** — including during combat encounters, where the cluster is currently hidden. FIRE keeps its held-action semantics (`isFirePressed()`) and multi-touch composition with the movement joystick. The arc's bounding box must update the layout reservations the UC22 minimap band and any remaining HUD elements rely on, so nothing overlaps.

## Acceptance Criteria
1. Action buttons render along a semicircular arc pivoting on the bottom-right corner (bottom-left when `LEFT_HANDED`), each button centre at approximately equal radius from the pivot.
2. Each action button is circular and shows **both a generated icon glyph and a text label**.
3. **FIRE is always visible and enabled** whenever the on-screen controls are shown — including during combat encounters (it is never removed or disabled by game state) — and retains held-action `isFirePressed()` semantics driving the combat tick.
4. FIRE composes with the movement joystick under multi-touch (steer and fire simultaneously).
5. All previously panel-based actions — **DOCK, MINE, SCAN, RADIO, and debug point-and-go** — are triggered from arc buttons; the standalone context panels for them are removed.
6. A contextual action's button appears on the arc only when that action is currently available (e.g. DOCK only when a station is in range); the debug point-and-go button appears only in debug builds.
7. When the set of visible buttons changes, the arc re-distributes them so spacing stays even and continues to follow the corner sweep; FIRE anchors the arc.
8. Triggering each action from its arc button produces the same game-state effect as the old panel did (dock, mine, scan, accept mission, point-and-go teleport).
9. Layout-reservation values are updated so the UC22 minimap band and other HUD elements do not overlap the arc at supported screen sizes.
10. Affected automated tests (UC22 layout guard, point-and-go placement, combat fire, and any dock/mine/scan/radio UI tests) are updated to the new geometry; `./gradlew :core:ktlintCheck :core:test` is green.

## Potential Pitfalls & Open Questions
- **Assumption** — contextual buttons appear only when available + FIRE always present, and the arc reflows to stay even. Defaulted during clarification; not explicitly confirmed by the user.
- **Edge case** — with all actions on one arc, the visible count varies (1 up to 6 with debug). The arc geometry (radius, angular span, button size) must stay reachable and non-overlapping across that range and across supported screen sizes.
- **Edge case** — DOCK/MINE/SCAN/RADIO availability can overlap (e.g. station + asteroid field both in range); the arc must handle several simultaneous contextual buttons.
- **Ambiguity** — text label placement on a round button (over the icon, below the icon inside the circle, or as a caption beneath) is left to the implementer's best on-device legibility.
- **Risk** — removing the dedicated panels may drop secondary info those panels showed (e.g. station name, mission text). The arc button may need a small label or the info relocates; verify nothing essential is lost.
- **Assumption** — the movement joystick stays in its current opposite corner, unaffected.

## Original Description
I want the actions to move to the bottom right action section. Actions should be circles with icons and text. The actions should form a semicircle in the bottom right corner so each button follows the natural movement of the finger across that part of the screen.
Also firing should always be available

## Clarifications
- Q: There's a handedness setting today (actions mirror to the bottom-LEFT for left-handed players). Should the new semicircle respect it?
  A: Mirror with handedness — bottom-right for right-handed (default), mirror the arc to bottom-left when LEFT_HANDED.
- Q: There's no icon/art pipeline yet (controls are generated solid shapes). How should the per-button icons be handled?
  A: Generated placeholder glyphs — keep the programmatic skin and draw a simple generated shape/glyph per action; no asset pipeline introduced now.
- Q: How many buttons should the arc contain, and what are they?
  A: Wire real actions onto the arc; all actions should move there (FIRE + DOCK, MINE, SCAN, RADIO, and debug point-and-go).
- Q: During a combat encounter the whole action cluster is currently hidden. Given "firing should always be available", what should happen?
  A: FIRE stays visible and enabled during encounters; other/contextual buttons may still hide.
