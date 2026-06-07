# Design Note — Upgrades & Progression

- **Status:** in-progress (model decided; per-ship slot layouts & stat deltas open)
- **Last updated:** 2026-06-07
- **Related:** PROJECT_BRIEF.md → in_scope #3, core_gameplay_loop (Improve); [economy-and-resources.md](economy-and-resources.md) (credits, ships, cargo, junkyards), [ship-and-controls.md](ship-and-controls.md) (movement params, turrets, crew), [world-and-sector.md](world-and-sector.md) (scanner/comms, junkyard POIs), [combat.md](combat.md) (weapon/defense upgrades), [save-and-persistence.md](save-and-persistence.md) (loadout state)

## Summary

Progression is **horizontal fit-out**: there is **no pilot/character XP or levels**. The
player gets stronger by **earning credits → buying better ships and fitting upgrades**.
Ships are effectively **roles/classes** with different slot layouts; upgrades occupy a
**limited set of slots by category** (**free slots**, no linear tech tree). Acquisition is
**cash- and reputation-based** (no research/unlock progression). Upgrades can be installed
freely; **used parts can only be sold at junkyards** (you can't sell a used part back to
the dealer), and **refitting also happens at junkyards** — so **no respec mechanic** is
needed. This is the "Improve" pillar.

## Goals

- Progression where each upgrade is felt, and ships specialize toward a playstyle.
- Keep it simple: buy/fit, no XP grind, no tech-tree gating for MVP.
- Multiple viable builds (mining / courier / combat-later) via ship choice + fitting.

## Mechanics / ideas

**Progression model — no character XP.** Advancement = acquiring ships and upgrades with
credits (and reputation gating, post-MVP). There is **no pilot leveling**. _(Crew is
**upgradeable to a degree** — exact mechanics TBD later.)_

**Ships as roles/classes.** Each purchasable ship has a **role and a fixed slot layout**
(e.g. **miner** = more cargo + mining/utility slots; **courier** = cargo + speed;
**fighter** = weapon/turret slots — combat-focused, later). The number of slots per
category **depends on the ship type**. Owned ships are switched while docked (see
[economy-and-resources.md](economy-and-resources.md)).

**Upgrade slots — free slots by category.** Categories (provisional MVP set; refine as we
go): **weapons, communications, hull plating, engines, sensors/scanner, cargo, fuel tanks,
crew quarters**. Slots are filled freely within their category — **not** a branching tech
tree. Slot counts per category vary by ship.

**What upgrades change** (mapped to existing stats; redefine as we build):
- engines → movement params (`max_acceleration`, `max_speed`, rotation, etc.)
- cargo → cargo capacity
- sensors/scanner → scan range / hidden-contact detection
- communications → radio/comms (mission broadcast reach, etc.)
- fuel tanks → fuel capacity
- crew quarters → crew capacity (gates turrets)
- hull plating → hull/armor (sectional defense)
- weapons → fixed weapons & turret mounts (combat, later)

**Acquisition gating — cash + reputation, no tech tree.** Upgrades and ships are **bought
at stations**; what's available and the price is gated by **credits** and (post-MVP)
**reputation/faction state**. There is **no unlock/research progression** for now.

**Selling & refitting — junkyards (NEW POI type).** Installed/used upgrades can be
**removed and sold only at junkyard-type locations** (not back to a normal dealer).
Junkyards are where **refits** happen, so no separate respec system is required. _(Likely
also a place to buy cheaper used parts — TBD.)_

## Player-facing behavior

- **Outfitting** at stations: buy and install upgrades; preview stat before/after.
- **Shipyard:** buy new ships; switch the active ship while docked.
- **Junkyard:** sell/remove used parts and refit.

## Data & state

Persisted (see [save-and-persistence.md](save-and-persistence.md)):
- **Owned ships**, each with its **installed upgrades per slot**, and the **active ship**.
- (Wallet/cargo/fuel are owned by [economy-and-resources.md](economy-and-resources.md).)

## Dependencies & interactions

- **Spends** economy (credits); availability gated by **reputation** (post-MVP).
- **Modifies** ship-and-controls (movement), combat (weapons/turrets/defense), world
  (scanner/sensors, comms), economy (cargo), and crew (capacity).
- **Junkyards** are a new **POI/station type** in [world-and-sector.md](world-and-sector.md)
  and a sell-channel in [economy-and-resources.md](economy-and-resources.md).

## Open questions

- Exact **stat deltas** per upgrade (define as we build).
- **Per-ship slot layouts** and the **MVP ship roster** (which roles ship first).
- **Crew upgrade** mechanics (deferred).
- **Junkyard** specifics: locations, used-part pricing, buy-used option.
- **Reputation gating** detail (post-MVP).

## Decided

- **No pilot/character XP or levels** — horizontal fit-out progression.
- **Crew upgradeable to a degree** (mechanics later).
- Upgrade **categories are fine as listed**; **slots depend on ship type**.
- **Ships have distinct roles/slot layouts.**
- **No tech/unlock tree** — acquisition is **cash- and reputation-based**.
- **Free slots; no respec** — refit at **junkyards**.
- **Used upgrades sold only at junkyards** (not back to dealers).

## References

Starsector / X4 ship-as-class & free-fit outfitting; Everspace 2 ship-as-class. See
PROJECT_BRIEF.md → Reference Points & Inspiration.
