# ADR 0016 — On-foot station mode: transient non-persisted prototype, shop via existing TradeScreen

- **Status:** Accepted
- **Date:** 2026-06-09

## Context

UC19 adds an optional on-foot "walk-around" mode at a docked station: the player leaves the ship and
walks a small interior (landing area → corridor → shop room) as an early prototype of station
interiors. Two decisions shaped how it integrates with the rest of the game and are worth recording:

1. **How much state does the mode own / persist?** The project already has a single versioned SQLite
   save (ADR 0002/0003) with additive, version-by-version migrations. Every prior gameplay system that
   introduced durable state added a save-version bump. An on-foot mode *could* persist the avatar's
   position, the fact the player is on foot, etc. — but the brief lists full station building/interiors
   as a separate stretch (non-goal for the MVP), and this is explicitly a low-fidelity prototype.
2. **What does "open the shop" target?** AC#6 says the interact prompt opens the *existing* shop UI —
   "no new shop screen is built". The docked hub today exposes both a **TradeScreen** (buy/sell goods)
   and an **OutfitScreen** (install/remove upgrades); UC20 separately covers redesigning the shop's
   grid layout. So which existing screen is "the shop" the shopkeeper opens?

## Options considered

| Option | For | Against |
|---|---|---|
| **Transient, non-persisted mode (chosen)** | No save-schema/version change; zero migration risk; docked `WorldState` is never touched so re-boarding trivially restores the docked state; matches the prototype's throwaway scope. | Avatar position is lost across app restart while docked (acceptable — you respawn near the ship). |
| Persist avatar position / on-foot flag | Could resume mid-walk after a restart. | Save-version bump + migration + fixtures for a prototype that may be redesigned; couples transient UI state to the durable save; over-engineered for the scope. |
| **Shop = existing TradeScreen (chosen)** | The shopkeeper is the trader; TradeScreen is the canonical "shop" reachable from the hub; reuses it verbatim (no new screen, AC#6). | Outfitting is not reachable on foot in this prototype. |
| Shop = OutfitScreen, or a new combined screen | Outfit on foot too. | Building/choosing a new screen contradicts "no new shop screen"; UC20 owns shop-layout redesign; scope creep. |

## Decision

1. **The on-foot mode is transient and non-persisted.** The interior layout and avatar position are
   rebuilt from `StationInterior.prototype()` each time the player disembarks; nothing about being on
   foot is written to the save. There is **no new save version and no migration** for UC19. The docked
   `WorldState` is left untouched while on foot, so RE-BOARD restores the docked state exactly.
2. **The shopkeeper opens the existing `TradeScreen`.** Walking near the shopkeeper shows an INTERACT
   button that opens the same `TradeScreen` reached from the hub menus, unchanged. BACK from the shop
   returns to the walk-around (preserving the avatar), and RE-BOARD returns to the hub. OUTFIT was
   considered as the on-foot target and **rejected** — the shopkeeper is the trader, and shop-layout
   redesign is UC20's concern.

The pure layout/movement logic lives in `com.orbitalfrontier.walkaround` (libGDX-free, JVM-testable per
ADR 0001); the screen takes only the interior, params/logger, and `onReboard`/`onInteract` callbacks —
no world/save/PlayScreen coupling — which structurally enforces decision (1).

## Consequences

- **Easier:** shipping the prototype with no persistence/migration work; re-boarding can't corrupt the
  docked state because it never changes it; the mode is structurally isolated from the save layer.
- **Harder / follow-on:** if a later use case wants on-foot state to survive an app restart, that needs
  a new ADR + additive save-version bump (the door is open, not taken here). Outfitting on foot would
  need a future decision once the shop UI is settled (UC20).
- **Reversible:** because nothing is persisted and the mode is decoupled behind callbacks, replacing the
  prototype interior (real art, multiple rooms, more interactive objects) is a contained change that
  supersedes this ADR rather than unwinding save migrations.
