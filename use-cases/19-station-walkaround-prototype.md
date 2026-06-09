# Use Case 19: Station walk-around (on-foot prototype)

## Summary
When docked/landed at a station, let the player optionally leave the ship and walk around on foot as an early prototype of station interiors. Exiting the ship is optional — the current docking menus stay exactly as they are, and walking around is an additional path the player can choose. The prototype layout: a zoomed-in landing area showing the player's ship, from which the avatar (rendered simply as a ball with a small dot indicating facing direction) can walk down a corridor that leads to a square room containing the shop and its shopkeeper. Avatar movement uses a virtual joystick (consistent with ship controls), and facing follows the movement direction. Walking near the shopkeeper shows an interact prompt that opens the existing shop UI — no new shop screen is built. Visual fidelity is deliberately low (programmer-art ball/dot/box geometry is acceptable for this prototype).

## Acceptance Criteria
1. While docked at a station, an optional "exit ship" / "disembark" action is available alongside (not replacing) the existing station menus; the current menus remain reachable and unchanged.
2. Choosing to exit shows a zoomed-in landing-area view containing the player's ship and the avatar.
3. The avatar is rendered as a ball with a small dot/marker showing its current facing direction.
4. The avatar is moved with a virtual on-screen joystick; its facing updates to match the movement direction.
5. The space is laid out as: landing area (with ship) → corridor → square room containing the shop and a shopkeeper figure. The avatar can traverse from the landing area through the corridor into the shop room.
6. Walking near the shopkeeper displays an interact prompt/button; activating it opens the existing shop UI (the same one reachable from the current menus).
7. The player can return from the on-foot mode back to the ship / station menus (re-board), restoring the normal docked state.
8. Basic boundaries keep the avatar within the walkable area (landing area, corridor, room) — it cannot walk through the outer walls. (Loose collision is acceptable for a prototype.)

## Potential Pitfalls & Open Questions
- **Assumption** — The shop opened on foot is the existing shop UI; this UC does not redesign the shop itself (menu-grid layout is covered separately by UC20).
- **Edge case** — Re-boarding and re-exiting should be repeatable without state corruption; the avatar should spawn at a sensible point (near the ship) each time.
- **Edge case** — Joystick + facing-dot must behave when the stick is released (avatar stops; facing retained).
- **Assumption** — Only the shopkeeper is interactive in this prototype; other station services (refuel, missions, etc.) remain accessed via the existing menus, not via on-foot objects (unless trivially free to add).
- **Risk** — Scope creep toward a full station-interior system; keep this to the single corridor + single room described.

## Original Description
"When landing at a station, you should be able to walk around. As prototype we can have the landing area with your ship there zoomed in, your avatar can be a ball with a small dot to see where it is facing, and a corridor that leads to a square room with the shop and it's shopkeeper. Getting out of the ship should be optional so keep current menus."

## Clarifications
- Q: On-foot avatar movement control scheme?
  A: Virtual joystick (consistent with ship controls); facing follows movement direction.
- Q: How does the avatar open the shop in the room?
  A: Walk near the shopkeeper → interact prompt opens the existing shop UI.
