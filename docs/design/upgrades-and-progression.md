# Design Note — Upgrades & Progression

- **Status:** implemented for MVP (UC09 — model + slots + multi-ship shipped; balancing values still [TUNE])
- **Last updated:** 2026-06-08
- **Related:** PROJECT_BRIEF.md → in_scope #3, core_gameplay_loop (Improve); **[ADR 0008](../adr/0008-fleet-and-outfitting-persistence.md)** (fleet/outfitting model + persistence); [economy-and-resources.md](economy-and-resources.md) (credits, ships, cargo, junkyards), [ship-and-controls.md](ship-and-controls.md) (movement params, turrets, crew), [world-and-sector.md](world-and-sector.md) (scanner/comms, junkyard stations), [combat.md](combat.md) (weapon/defense upgrades), [save-and-persistence.md](save-and-persistence.md) (loadout state)

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
at stations**; what's available and the price is gated by **credits** and **reputation/faction
state**. There is **no unlock/research progression** for now.

> **Reputation now gates upgrade/ship acquisition as of UC48** ([ADR
> 0036](../adr/0036-reputation-gated-acquisition.md), supersedes the ADR 0013 deferral in part). An
> authored `unlockThreshold` per catalog item is gated against the player's standing with the **docked
> station's faction**, and the price is graded by the same `FactionPricing` seam UC46 uses for trade
> (exactly the base at neutral). Gating + pricing are derived **at read time** from live reputation, so
> they persist across reload and update as standing changes — no save schema change. A locked item stays
> **visible** with its standing requirement (it does not silently vanish), and an item already installed
> when standing later drops is **never confiscated**. (UC14, [ADR
> 0013](../adr/0013-factions-and-reputation.md), still owns the per-faction reputation model and mission
> gating.)

**Selling & refitting — junkyards (a station _kind_, not a new POI type).** Installed/used
upgrades can be **removed and sold only at junkyard-type stations** (not back to a normal
dealer). Junkyards are where **refits** happen, so no separate respec system is required.
**Implemented (UC09):** a junkyard is a `StationKind.JUNKYARD` capability on an ordinary
dockable `Station` — **not** a new `Poi` subtype — so docking/minimap/hub work unchanged
(see [ADR 0008](../adr/0008-fleet-and-outfitting-persistence.md), which supersedes the
earlier "NEW POI type" wording). Selling a used part refunds a fraction of its catalog
price (`Outfitting.USED_PART_REFUND_FRACTION`, a [TUNE] value). The MVP junkyard sits in
Gamma Verge and also stocks a couple of tier-I parts so a refit can happen on the spot.
**Implemented (UC47, [ADR 0035](../adr/0035-junkyard-buy-used-parts.md)):** junkyards now also
**buy-used** — a separate authored `Station.usedPartMarket` lists a subset of catalog parts offered at a
discount (`UsedPartParams.discountFraction`, default 0.6 — deliberately above the 0.5 sell refund so a
buy-used→sell round-trip always loses money, no arbitrage), installed through the **same** install flow
as a new part. Used stock is finite: a deterministic baseline per (junkyard, part) (seeded only on the
stable slugs, recomputed on load) minus the player's **persisted** purchases (`JunkyardStock`), so
`available = baseline − purchased` and a reload can never restock cheap parts. Used parts are **purely
cheaper** — no condition/wear (MVP decision) — and there is **no time-based restock** (deferred).

## Player-facing behavior

- **Outfitting** at stations: buy and install upgrades; preview stat before/after.
- **Shipyard:** buy new ships; switch the active ship while docked.
- **Junkyard:** sell/remove used parts and refit.

## Data & state

Persisted (see [save-and-persistence.md](save-and-persistence.md)):
- **Owned ships**, each with its **installed upgrades per slot**, and the **active ship**.
- (Wallet/cargo/fuel are owned by [economy-and-resources.md](economy-and-resources.md).)

## Dependencies & interactions

- **Spends** economy (credits); availability + price gated by **reputation** (UC48, ADR 0036).
- **Modifies** ship-and-controls (movement), combat (weapons/turrets/defense), world
  (scanner/sensors, comms), economy (cargo), and crew (capacity).
- **Junkyards** are a new **POI/station type** in [world-and-sector.md](world-and-sector.md)
  and a sell-channel in [economy-and-resources.md](economy-and-resources.md).

## Open questions

- **Balancing only (all [TUNE]):** exact **stat deltas** and **prices** per upgrade, the **per-ship
  slot counts**, and the **ship-purchase prices**. The structures shipped in UC09; the numbers are
  placeholders to tune.
- **Crew upgrade** mechanics (deferred).
- **Junkyard** used-part **buy-used** option and used-part pricing curve — **shipped (UC47, [ADR
  0035](../adr/0035-junkyard-buy-used-parts.md)):** data-driven flat discount + deterministic-baseline /
  persisted-depletion finite stock; purely cheaper (no condition/wear) and **no time-based restock**
  (restock cadence remains deferred). The discount fraction + stock bounds are [TUNE] values.
- **Reputation gating of upgrades/ships** — **shipped (UC48, [ADR
  0036](../adr/0036-reputation-gated-acquisition.md)):** authored `unlockThreshold` per item × the docked
  station's faction, gated against live standing and priced through the UC46 `FactionPricing` seam
  (exactly base at neutral), derived at read time (no schema change), locked-with-reason and
  no-confiscation. Per-station thresholds, allied/rival propagation, and continuous-curve tuning remain
  deferred; the thresholds + curve are [TUNE] values.
- Active-ship **switching while ships are parked apart**: the MVP presents the switched-to ship at the
  docked station (no idle-ship storage / travel model yet).

## Decided

- **No pilot/character XP or levels** — horizontal fit-out progression.
- **Crew upgradeable to a degree** (mechanics later).
- Upgrade **categories are fine as listed**; **slots depend on ship type**.
- **Ships have distinct roles/slot layouts.**
- **No tech/unlock tree** — acquisition is **cash- and reputation-based**.
- **Free slots; no respec** — refit at **junkyards**.
- **Used upgrades sold only at junkyards** (not back to dealers).
- **(UC09, ADR 0008)** Stats are **derived** (`ship type baseline + Σ loadout deltas`), never stored —
  capacity is reconstructed on load, so a fit change or retune can never strand a save.
- **(UC09)** A **junkyard is a station _kind_**, not a new POI type (supersedes the wording above).
- **(UC09)** The player owns a **fleet**; the **active ship** is switched while docked and each ship
  keeps its own loadout/cargo/fuel. In the MVP a fleet only **grows** (no removal/trade-in/storage).
- **(UC09)** Loadout slots are **gap-tolerant** (a mid-list removal leaves a real gap; indices are
  never compacted) and persisted per `(ship, slot_category, slot_index)`.

## References

Starsector / X4 ship-as-class & free-fit outfitting; Everspace 2 ship-as-class. See
PROJECT_BRIEF.md → Reference Points & Inspiration.
